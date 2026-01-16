import ws from "k6/ws";
import { check, sleep } from "k6";
import crypto from "k6/crypto";
import encoding from "k6/encoding";
import { Counter, Trend } from "k6/metrics";

const connectOk = new Counter("ws_connect_ok");
const connectFail = new Counter("ws_connect_fail");
const authOk = new Counter("ws_auth_ok");
const authFail = new Counter("ws_auth_fail");
const sent = new Counter("im_sent_total");
const ackSaved = new Counter("im_ack_saved_total");
const recv = new Counter("im_recv_total");
const recvUnique = new Counter("im_recv_unique_total");
const dup = new Counter("im_dup_total");
const reorder = new Counter("im_reorder_total");
const e2eLatencyMs = new Trend("im_e2e_latency_ms");
const serverError = new Counter("ws_server_error");

function b64urlFromB64(b64) {
  return b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function b64urlJson(obj) {
  const b64 = encoding.b64encode(JSON.stringify(obj), "std");
  return b64urlFromB64(b64);
}

function signHs256Base64Url(data, secret) {
  const sigB64 = crypto.hmac("sha256", secret, data, "base64");
  return b64urlFromB64(sigB64);
}

function issueAccessToken({ userId, sessionVersion, issuer, jwtSecret, ttlSeconds }) {
  const nowSec = Math.floor(Date.now() / 1000);
  const header = { alg: "HS256", typ: "JWT" };
  const payload = {
    uid: userId,
    typ: "access",
    sv: sessionVersion,
    iss: issuer,
    iat: nowSec,
    exp: nowSec + ttlSeconds,
  };

  const h = b64urlJson(header);
  const p = b64urlJson(payload);
  const data = `${h}.${p}`;
  const s = signHs256Base64Url(data, jwtSecret);
  return `${data}.${s}`;
}

function parseWsUrls() {
  const raw = (__ENV.WS_URLS || "ws://127.0.0.1:9001/ws").trim();
  return raw.split(";").map((s) => s.trim()).filter((s) => s.length > 0);
}

function pickWsUrlForRole(isSender) {
  const urls = parseWsUrls();
  if (urls.length === 0) return "ws://127.0.0.1:9001/ws";
  if (urls.length === 1) return urls[0];
  if (__ENV.ROLE_PINNED === "1") return isSender ? urls[0] : urls[1];
  return urls[Math.floor(Math.random() * urls.length)];
}

export const options = {
  vus: Number(__ENV.VUS || 200),
  duration: __ENV.DURATION || "5m",
};

function parseDurationMs(s) {
  const t = (s || "").trim();
  const m = /^(\d+)(ms|s|m|h)$/.exec(t);
  if (!m) return 5 * 60 * 1000;
  const n = Number(m[1]);
  const unit = m[2];
  if (unit === "ms") return n;
  if (unit === "s") return n * 1000;
  if (unit === "m") return n * 60 * 1000;
  if (unit === "h") return n * 60 * 60 * 1000;
  return 5 * 60 * 1000;
}

export default function () {
  const jwtSecret = __ENV.JWT_SECRET || "change-me-please-change-me-please-change-me";
  const issuer = __ENV.JWT_ISSUER || "mini-im";
  const ttlSeconds = Number(__ENV.JWT_TTL_SECONDS || 24 * 3600);
  const baseUserId = Number(__ENV.USER_BASE || 100000);
  const sessionVersion = Number(__ENV.SESSION_VERSION || 0);

  const msgIntervalMs = Number(__ENV.MSG_INTERVAL_MS || 50);
  const warmupMs = Number(__ENV.WARMUP_MS || 1000);
  const sessionMs = Number(__ENV.SESSION_MS || 0) || parseDurationMs(__ENV.DURATION || "5m");

  const isSender = __VU % 2 === 0;
  const userId = baseUserId + __VU;
  const peerUserId = isSender ? userId + 1 : userId - 1;
  const token = issueAccessToken({ userId, sessionVersion, issuer, jwtSecret, ttlSeconds });

  const wsUrl = pickWsUrlForRole(isSender);
  const url = `${wsUrl}?token=${encodeURIComponent(token)}`;

  const res = ws.connect(url, {}, function (socket) {
    let authed = false;
    let seq = 0;
    const seenClientMsgId = new Set();
    const lastSeqByFrom = {};

    function mkClientMsgId() {
      seq += 1;
      return `${userId}-${seq}`;
    }

    socket.on("open", function () {
      socket.send(JSON.stringify({ type: "AUTH", token }));
    });

    socket.on("message", function (raw) {
      let msg;
      try {
        msg = JSON.parse(raw);
      } catch (_) {
        return;
      }

      if (msg.type === "AUTH_OK") {
        authed = true;
        authOk.add(1);
        return;
      }
      if (msg.type === "AUTH_FAIL" || (msg.type === "ERROR" && msg.reason)) {
        authFail.add(1);
        return;
      }
      if (msg.type === "ERROR") {
        serverError.add(1);
        return;
      }

      if (isSender) {
        if (msg.type === "ACK" && msg.ackType === "saved") ackSaved.add(1);
        return;
      }

      if (msg.type === "SINGLE_CHAT") {
        recv.add(1);

        const clientMsgId = msg.clientMsgId || "";
        if (clientMsgId.length > 0) {
          if (seenClientMsgId.has(clientMsgId)) dup.add(1);
          else {
            seenClientMsgId.add(clientMsgId);
            recvUnique.add(1);
          }
        }

        const from = msg.from;
        let body = msg.body;
        if (typeof body !== "string") return;

        try {
          const payload = JSON.parse(body);
          if (payload && typeof payload.sendTs === "number") {
            e2eLatencyMs.add(Date.now() - payload.sendTs);
          }
          if (payload && typeof payload.seq === "number" && typeof from === "number") {
            const last = lastSeqByFrom[from] || 0;
            if (payload.seq <= last) reorder.add(1);
            if (payload.seq > last) lastSeqByFrom[from] = payload.seq;
          }
        } catch (_) {
          return;
        }
      }
    });

    socket.setTimeout(function () {
      if (!authed) return;
      if (!isSender) return;
      const clientMsgId = mkClientMsgId();
      const payload = JSON.stringify({ sendTs: Date.now(), from: userId, seq });
      socket.send(
        JSON.stringify({
          type: "SINGLE_CHAT",
          to: peerUserId,
          clientMsgId,
          body: payload,
        }),
      );
      sent.add(1);
    }, warmupMs);

    socket.setInterval(function () {
      if (!authed) return;
      if (!isSender) return;
      const clientMsgId = mkClientMsgId();
      const payload = JSON.stringify({ sendTs: Date.now(), from: userId, seq });
      socket.send(
        JSON.stringify({
          type: "SINGLE_CHAT",
          to: peerUserId,
          clientMsgId,
          body: payload,
        }),
      );
      sent.add(1);
    }, msgIntervalMs);

    socket.setTimeout(function () {
      socket.close();
    }, sessionMs + 2000);
  });

  const ok = check(res, { "ws upgrade status is 101": (r) => r && r.status === 101 });
  if (ok) connectOk.add(1);
  else connectFail.add(1);

  sleep(0.001);
}
