import csv
import json
import re
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path


@dataclass(frozen=True)
class Milestone:
    ts_yyyymmddhhmm: int
    name: str


MILESTONES = (
    Milestone(202601090120, "BP(慢消费者背压)"),
    Milestone(202601121939, "ACK-EL(ACK回切隔离)"),
    Milestone(202601131424, "POSTDB(尾延迟治理/对照)"),
    Milestone(202601131631, "OPENLOOP+ACKBATCH(固定速率+ACK合并)"),
    Milestone(202601150002, "ENSUREMEM(单聊成员去热路径)"),
)

TS_RE = re.compile(r"(?:_|-)(\d{8})_(\d{6})")


def parse_ts(path: Path) -> datetime | None:
    m = TS_RE.search(path.as_posix())
    if not m:
        return None
    return datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S")


def run_key(dt: datetime | None) -> int | None:
    if not dt:
        return None
    return int(dt.strftime("%Y%m%d%H%M"))


def milestone_tags(dt: datetime | None) -> str:
    key = run_key(dt)
    if key is None:
        return ""
    return ";".join(m.name for m in MILESTONES if key >= m.ts_yyyymmddhhmm)


def scenario_from_path(path: Path) -> str:
    s = path.as_posix()
    if "/ws-cluster-5x-test_" in s:
        return "ws-cluster-5x-test"
    if "/test-run-" in s:
        return "test-run"
    if "/bp-multi/" in s:
        return "bp-multi"
    return "other"


def safe_get(d: dict, *keys, default=None):
    cur = d
    for k in keys:
        if not isinstance(cur, dict):
            return default
        cur = cur.get(k)
    return default if cur is None else cur


def fmt_ratio(v: float | None) -> str:
    if v is None:
        return ""
    return f"{v * 100:.2f}%"


def fmt_s(ms: float | None) -> str:
    if ms is None or ms == "":
        return "-"
    if isinstance(ms, str):
        try:
            ms = float(ms)
        except ValueError:
            return "-"
    return f"{ms / 1000:.3f}s"


def load_json(path: Path) -> dict | list:
    raw = path.read_bytes()
    for enc in ("utf-8-sig", "utf-8", "utf-16"):
        try:
            return json.loads(raw.decode(enc))
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
    return json.loads(raw.decode("utf-8", errors="replace"))


def main() -> int:
    root = Path(".").resolve()
    files = sorted({p for p in root.glob("logs/**/*single*e2e*.json") if p.is_file()})

    rows: list[dict] = []
    parse_errors = 0

    for p in files:
        rel = p.relative_to(root).as_posix()
        dt = parse_ts(p)
        ts_iso = dt.isoformat(sep=" ", timespec="seconds") if dt else ""
        scenario = scenario_from_path(p)

        try:
            data = load_json(p)
        except Exception:
            parse_errors += 1
            continue

        if isinstance(data, dict) and "repeats" in data and "e2eMs" in data and "mode" not in data:
            p50 = safe_get(data, "e2eMs", "p50")
            p95 = safe_get(data, "e2eMs", "p95")
            p99 = safe_get(data, "e2eMs", "p99")
            sent_per_sec = safe_get(data, "sentPerSecAvg")
            ws_err = safe_get(data, "wsErrorAvg")
            open_loop = safe_get(data, "openLoop")
            msg_interval = safe_get(data, "msgIntervalMs")
            rows.append(
                {
                    "ts": ts_iso,
                    "scenario": scenario,
                    "file": rel,
                    "schema": "avg",
                    "openLoop": bool(open_loop) if open_loop is not None else "",
                    "clients": "",
                    "durationSeconds": "",
                    "msgIntervalMs": msg_interval or "",
                    "inflight": "",
                    "bodyBytes": "",
                    "slowConsumerPct": "",
                    "slowConsumerDelayMs": "",
                    "noReadPct": "",
                    "flapPct": "",
                    "reconnect": "",
                    "attempted": "",
                    "attemptedPerSec": safe_get(data, "attemptedPerSecAvg") or "",
                    "sent": "",
                    "sentPerSec": sent_per_sec or "",
                    "skippedHard": "",
                    "ackSaved": "",
                    "recvUnique": "",
                    "deliveredPerSec": "",
                    "deliverRate": "",
                    "ackSavedRate": "",
                    "wsError": ws_err or "",
                    "wsErrorRate": "",
                    "dup": "",
                    "reorder": "",
                    "reorderByFrom": "",
                    "reorderByServerMsgId": "",
                    "e2eInvalid": safe_get(data, "e2eInvalidAvg") or "",
                    "e2e_p50_ms": p50 or "",
                    "e2e_p95_ms": p95 or "",
                    "e2e_p99_ms": p99 or "",
                    "milestones": milestone_tags(dt),
                    "flags": "avg-file",
                }
            )
            continue

        if not isinstance(data, dict):
            continue
        if data.get("mode") != "SINGLE_E2E":
            continue

        single = data.get("singleChat") or {}

        clients = data.get("clients")
        duration = data.get("durationSeconds")
        msg_interval = data.get("msgIntervalMs")
        inflight = data.get("inflight")
        body_bytes = data.get("bodyBytes")
        slow_pct = data.get("slowConsumerPct")
        slow_delay = data.get("slowConsumerDelayMs")
        noread_pct = data.get("noReadPct")
        flap_pct = data.get("flapPct")
        reconnect = data.get("reconnect")
        open_loop = data.get("openLoop")

        attempted = single.get("attempted", single.get("sent"))
        attempted_per_sec = single.get("attemptedPerSec", single.get("sentPerSec"))
        sent = single.get("sent")
        sent_per_sec = single.get("sentPerSec")
        skipped_hard = single.get("skippedHard")
        ack_saved = single.get("ackSaved")
        recv_unique = single.get("recvUnique", single.get("recv"))

        delivered_per_sec = None
        if duration and recv_unique is not None:
            delivered_per_sec = recv_unique / duration

        deliver_rate = None
        if sent and recv_unique is not None:
            deliver_rate = recv_unique / sent if sent else None

        ack_saved_rate = None
        if sent and ack_saved is not None:
            ack_saved_rate = ack_saved / sent if sent else None

        ws_err = safe_get(data, "errors", "wsError")
        ws_err_rate = None
        if sent and ws_err is not None:
            ws_err_rate = ws_err / sent if sent else None

        dup = single.get("dup")
        reorder = single.get("reorder")
        reorder_by_from = single.get("reorderByFrom")
        reorder_by_server_msg_id = single.get("reorderByServerMsgId")
        e2e_invalid = single.get("e2eInvalid")

        e2e = single.get("e2eMs") or {}
        p50 = e2e.get("p50")
        p95 = e2e.get("p95")
        p99 = e2e.get("p99")

        flags = []
        if deliver_rate is not None and deliver_rate > 1.01:
            flags.append("deliver>100%")
        if e2e_invalid:
            flags.append(f"e2eInvalid={e2e_invalid}")
        if dup:
            flags.append(f"dup={dup}")
        if reorder:
            flags.append(f"reorder={reorder}")

        rows.append(
            {
                "ts": ts_iso,
                "scenario": scenario,
                "file": rel,
                "schema": "run",
                "openLoop": bool(open_loop) if open_loop is not None else False,
                "clients": clients,
                "durationSeconds": duration,
                "msgIntervalMs": msg_interval,
                "inflight": inflight,
                "bodyBytes": body_bytes,
                "slowConsumerPct": slow_pct,
                "slowConsumerDelayMs": slow_delay,
                "noReadPct": noread_pct,
                "flapPct": flap_pct,
                "reconnect": reconnect,
                "attempted": attempted,
                "attemptedPerSec": attempted_per_sec,
                "sent": sent,
                "sentPerSec": sent_per_sec,
                "skippedHard": skipped_hard,
                "ackSaved": ack_saved,
                "ackSavedRate": ack_saved_rate,
                "recvUnique": recv_unique,
                "deliveredPerSec": delivered_per_sec,
                "deliverRate": deliver_rate,
                "wsError": ws_err,
                "wsErrorRate": ws_err_rate,
                "dup": dup,
                "reorder": reorder,
                "reorderByFrom": reorder_by_from,
                "reorderByServerMsgId": reorder_by_server_msg_id,
                "e2eInvalid": e2e_invalid,
                "e2e_p50_ms": p50,
                "e2e_p95_ms": p95,
                "e2e_p99_ms": p99,
                "milestones": milestone_tags(dt),
                "flags": ";".join(flags),
            }
        )

    rows.sort(key=lambda r: (r["ts"], r["file"]))

    out_csv = Path("helloagents/wiki/single_chat_perf_timeline.csv")
    out_md = Path("helloagents/wiki/single_chat_perf_timeline.md")
    out_csv.parent.mkdir(parents=True, exist_ok=True)

    fieldnames = [
        "ts",
        "scenario",
        "file",
        "schema",
        "openLoop",
        "clients",
        "durationSeconds",
        "msgIntervalMs",
        "inflight",
        "bodyBytes",
        "slowConsumerPct",
        "slowConsumerDelayMs",
        "noReadPct",
        "flapPct",
        "reconnect",
        "attempted",
        "attemptedPerSec",
        "sent",
        "sentPerSec",
        "skippedHard",
        "ackSaved",
        "ackSavedRate",
        "recvUnique",
        "deliveredPerSec",
        "deliverRate",
        "wsError",
        "wsErrorRate",
        "dup",
        "reorder",
        "reorderByFrom",
        "reorderByServerMsgId",
        "e2eInvalid",
        "e2e_p50_ms",
        "e2e_p95_ms",
        "e2e_p99_ms",
        "milestones",
        "flags",
    ]

    with out_csv.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        for r in rows:
            w.writerow({k: ("" if r.get(k) is None else r.get(k)) for k in fieldnames})

    def to_float(v):
        if v is None or v == "":
            return None
        try:
            return float(v)
        except Exception:
            return None

    def quantile(values: list[float], p: float) -> float | None:
        values = sorted(values)
        if not values:
            return None
        if len(values) == 1:
            return values[0]
        k = (len(values) - 1) * p
        f = int(k)
        c = min(f + 1, len(values) - 1)
        if c == f:
            return values[f]
        return values[f] + (values[c] - values[f]) * (k - f)

    def fmt_pct(v: float | None) -> str:
        if v is None:
            return ""
        return f"{v * 100:.2f}%"

    # Comparable slice (for quick narrative): 5 instances / 5000 clients
    baseline = None
    baseline_candidates = []
    for r in rows:
        if r.get("schema") != "run":
            continue
        if r.get("scenario") != "ws-cluster-5x-test":
            continue
        if str(r.get("clients")) != "5000":
            continue
        if str(r.get("openLoop")) != "False":
            continue
        if r.get("flags"):
            continue
        offered = to_float(r.get("sentPerSec"))
        ws_err = to_float(r.get("wsErrorRate")) or 0.0
        deliver = to_float(r.get("deliverRate")) or 0.0
        if offered is None:
            continue
        if not (700 <= offered <= 800):
            continue
        if ws_err > 0.05:
            continue
        if deliver > 1.01:
            continue
        baseline_candidates.append(r)
    if baseline_candidates:
        baseline = sorted(baseline_candidates, key=lambda x: x.get("ts", ""))[0]

    current = []
    for r in rows:
        if r.get("schema") != "run":
            continue
        if r.get("scenario") != "ws-cluster-5x-test":
            continue
        if str(r.get("clients")) != "5000":
            continue
        if str(r.get("openLoop")) != "True":
            continue
        if str(r.get("msgIntervalMs")) != "3000":
            continue
        if r.get("flags"):
            continue
        ws_err = to_float(r.get("wsErrorRate")) or 0.0
        deliver = to_float(r.get("deliverRate")) or 0.0
        if ws_err > 0.05:
            continue
        if deliver > 1.01:
            continue
        current.append(r)

    def summarize_run_set(run_set: list[dict]) -> dict:
        offered = []
        delivered = []
        deliver_rate = []
        ws_err = []
        ack_saved_rate = []
        p50 = []
        p95 = []
        p99 = []
        for r in run_set:
            offered.append(to_float(r.get("attemptedPerSec") or r.get("sentPerSec")) or 0.0)
            d = to_float(r.get("deliveredPerSec"))
            if d is not None:
                delivered.append(d)
            deliver_rate.append(to_float(r.get("deliverRate")) or 0.0)
            ws_err.append(to_float(r.get("wsErrorRate")) or 0.0)
            asr = to_float(r.get("ackSavedRate"))
            if asr is not None:
                ack_saved_rate.append(asr)
            p50_ms = to_float(r.get("e2e_p50_ms"))
            p95_ms = to_float(r.get("e2e_p95_ms"))
            p99_ms = to_float(r.get("e2e_p99_ms"))
            if p50_ms is not None:
                p50.append(p50_ms / 1000)
            if p95_ms is not None:
                p95.append(p95_ms / 1000)
            if p99_ms is not None:
                p99.append(p99_ms / 1000)
        return {
            "n": len(run_set),
            "offered_median": quantile(offered, 0.5),
            "delivered_median": quantile(delivered, 0.5),
            "deliver_pct_median": quantile([x * 100 for x in deliver_rate], 0.5),
            "ws_err_pct_median": quantile([x * 100 for x in ws_err], 0.5),
            "ack_saved_pct_median": quantile([x * 100 for x in ack_saved_rate], 0.5),
            "p50_median": quantile(p50, 0.5),
            "p95_median": quantile(p95, 0.5),
            "p99_median": quantile(p99, 0.5),
        }

    current_summary = summarize_run_set(current)

    md_lines: list[str] = []
    md_lines.append("# 单聊性能时间线（全量汇总）")
    md_lines.append("")
    md_lines.append("本文件由日志自动汇总生成，方便把**单聊延迟/投递速度/错误率**按时间拉通对比。")
    md_lines.append("")
    md_lines.append("## 快速结论（可比切片）")
    md_lines.append("")
    md_lines.append("为避免口径混乱，这里先给一个“尽量可比”的对照：")
    md_lines.append("")
    md_lines.append("- 基线（closed-loop，5实例/5000 clients，sentPerSec≈700~800，wsError<5% 且无污染 flags）：取最早一条满足条件的记录")
    md_lines.append("- 当前（open-loop，5实例/5000 clients，MsgIntervalMs=3000，wsError<5% 且无污染 flags）：取满足条件的全部记录的**中位数**")
    md_lines.append("")
    if baseline:
        md_lines.append(f"- 基线记录：`{baseline.get('file')}`")
        md_lines.append(
            "  - offered/sent≈{offered:.2f} msg/s，delivered≈{delivered:.2f} msg/s，deliver≈{deliver}，wsError≈{wsErr}，ackSaved≈{ackSaved}".format(
                offered=to_float(baseline.get("sentPerSec")) or 0.0,
                delivered=to_float(baseline.get("deliveredPerSec")) or 0.0,
                deliver=fmt_pct(to_float(baseline.get("deliverRate"))),
                wsErr=fmt_pct(to_float(baseline.get("wsErrorRate"))),
                ackSaved=fmt_pct(to_float(baseline.get("ackSavedRate"))),
            )
        )
        md_lines.append(
            "  - E2E p50/p95/p99：{p50:.3f}s/{p95:.3f}s/{p99:.3f}s".format(
                p50=(to_float(baseline.get("e2e_p50_ms")) or 0.0) / 1000,
                p95=(to_float(baseline.get("e2e_p95_ms")) or 0.0) / 1000,
                p99=(to_float(baseline.get("e2e_p99_ms")) or 0.0) / 1000,
            )
        )
    else:
        md_lines.append("- 基线记录：未找到满足“sentPerSec≈700~800 且 wsError<5% 且 flags 为空”的 closed-loop 记录")
    md_lines.append(
        "- 当前（open-loop clean，中位数，n={n}）：offered≈{offered:.2f} msg/s，delivered≈{delivered:.2f} msg/s，deliver≈{deliver}，wsError≈{wsErr}，ackSaved≈{ackSaved}".format(
            n=current_summary.get("n", 0),
            offered=current_summary.get("offered_median") or 0.0,
            delivered=current_summary.get("delivered_median") or 0.0,
            deliver=f"{(current_summary.get('deliver_pct_median') or 0.0):.2f}%",
            wsErr=f"{(current_summary.get('ws_err_pct_median') or 0.0):.2f}%",
            ackSaved=f"{(current_summary.get('ack_saved_pct_median') or 0.0):.2f}%",
        )
    )
    md_lines.append(
        "- 当前（open-loop clean，中位数）E2E p50/p95/p99：{p50:.3f}s/{p95:.3f}s/{p99:.3f}s".format(
            p50=current_summary.get("p50_median") or 0.0,
            p95=current_summary.get("p95_median") or 0.0,
            p99=current_summary.get("p99_median") or 0.0,
        )
    )
    md_lines.append("")
    md_lines.append("注意：上述“基线 vs 当前”不是严格 A/B（部分历史记录缺失 msgInterval/openLoop 字段），但 offered load 接近时可用来观察趋势。全量明细以表格/CSV 为准。")
    md_lines.append("")
    md_lines.append("## 关键改动里程碑（用于对照）")
    md_lines.append("")
    md_lines.append("- `helloagents/history/2026-01/202601121939_ws_ack_eventloop_isolation/`：单聊 ACK 回切隔离（减少 eventLoop 排队）")
    md_lines.append("- `helloagents/history/2026-01/202601131424_ws_single_dbqueue_postdb_offload/`：单聊尾延迟治理（对照/实验 + writer 串行优化）")
    md_lines.append("- `helloagents/history/2026-01/202601131631_ws_openloop_ack_isolation/`：open-loop 固定速率压测 + delivered/read ACK 合并开关")
    md_lines.append("")
    md_lines.append("## 归因建议（哪些改动最可能在起作用）")
    md_lines.append("")
    md_lines.append("- `ACK-EL(ACK回切隔离)`：属于“减少一次 eventLoop 排队”的确定性收益；它直接作用在 ACK(saved) 返回链路，通常会同时改善 `ackSaved%` 与 E2E 分位数收敛。")
    md_lines.append("- `POSTDB(尾延迟治理/对照)`：对尾延迟更敏感（P95/P99），但在高噪声/过载场景会被其他瓶颈（DB 排队/写放大/推送链路）掩盖，需要用 open-loop clean 口径做回归。")
    md_lines.append("- `OPENLOOP+ACKBATCH`：open-loop 是“测量口径改进”，不应被当作服务端性能提升；ACK 合并主要影响 delivered/read ACK 压力，在 SINGLE_E2E(ACK saved) 场景里很可能看不到收益。")
    md_lines.append("- `BP(慢消费者背压)`：在 slow/noRead 场景才体现价值（防止内存上涨+延迟爆炸）；在 normal 场景主要体现为“过载时更快失败/更可控”，而不是让 P99 变小。")
    md_lines.append("")
    md_lines.append("## 数据来源")
    md_lines.append("")
    md_lines.append("- 生成命令：`python scripts/perf/gen_single_chat_perf_timeline.py`")
    md_lines.append(
        f"- 扫描文件：`logs/**/*single*e2e*.json`（共 {len(files)} 个命中，实际解析到 {len(rows)} 条记录；解析失败 {parse_errors} 个）"
    )
    md_lines.append(f"- 明细 CSV：`{out_csv.as_posix()}`（建议用 Excel/Numbers 或 PowerShell `Import-Csv` 做筛选/排序）")
    md_lines.append("")
    md_lines.append("## 指标口径（表格列解释）")
    md_lines.append("")
    md_lines.append("- offered msg/s：发送端“尝试发送”速率（open-loop 时=attemptedPerSec；closed-loop 时基本等于 sentPerSec）")
    md_lines.append("- delivered msg/s：接收端 `recvUnique/durationSeconds`（只统计接收端观测到的唯一消息）")
    md_lines.append("- deliver%：`recvUnique/sent`（>100% 通常意味着**离线补发/旧消息污染**，需要看 `flags`）")
    md_lines.append("- wsError%：`errors.wsError/sent`（发送过程中 WS ERROR 的比例；<5% 是你之前给的可接受阈值）")
    md_lines.append("- E2E：以脚本统计的 `singleChat.e2eMs` 为准（单位 ms；下表以秒展示 p50/p95/p99）")
    md_lines.append("")
    md_lines.append("## 里程碑标签（milestones）")
    md_lines.append("")
    for m in MILESTONES:
        md_lines.append(f"- {m.name}：`{m.ts_yyyymmddhhmm}`")
    md_lines.append("")
    md_lines.append("## 全量记录（按时间升序）")
    md_lines.append("")
    md_lines.append(
        "| 时间 | 来源 | 文件 | clients | dur(s) | interval(ms) | openLoop | offered msg/s | delivered msg/s | deliver% | ackSaved% | wsError% | E2E p50/p95/p99 | dup/reorder | flags | milestones |"
    )
    md_lines.append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|")

    for r in rows:
        if r["schema"] == "avg":
            e2e = f"{fmt_s(r.get('e2e_p50_ms'))}/{fmt_s(r.get('e2e_p95_ms'))}/{fmt_s(r.get('e2e_p99_ms'))}"
            md_lines.append(
                "| {ts} | {scenario} | `{file}` |  |  | {interval} | {openLoop} | {offered} |  |  |  |  | {e2e} |  | {flags} | {milestones} |".format(
                    ts=r.get("ts", ""),
                    scenario=r.get("scenario", ""),
                    file=r.get("file", ""),
                    interval=r.get("msgIntervalMs", ""),
                    openLoop="Y" if r.get("openLoop") else "N",
                    offered=r.get("sentPerSec") or r.get("attemptedPerSec") or "",
                    e2e=e2e,
                    flags=r.get("flags", ""),
                    milestones=r.get("milestones", ""),
                )
            )
            continue

        offered = r.get("attemptedPerSec") if r.get("openLoop") else r.get("sentPerSec")
        delivered = r.get("deliveredPerSec")
        deliver_pct = r.get("deliverRate")
        ack_saved_pct = r.get("ackSavedRate")
        ws_err_pct = r.get("wsErrorRate")

        e2e = f"{fmt_s(r.get('e2e_p50_ms'))}/{fmt_s(r.get('e2e_p95_ms'))}/{fmt_s(r.get('e2e_p99_ms'))}"

        dup_reorder = ""
        if r.get("dup") or r.get("reorder"):
            dup_reorder = f"dup={r.get('dup') or 0},reorder={r.get('reorder') or 0}"

        offered_str = ""
        if offered is not None and offered != "":
            try:
                offered_str = f"{float(offered):.2f}"
            except Exception:
                offered_str = str(offered)

        delivered_str = ""
        if delivered is not None and delivered != "":
            try:
                delivered_str = f"{float(delivered):.2f}"
            except Exception:
                delivered_str = str(delivered)

        md_lines.append(
            "| {ts} | {scenario} | `{file}` | {clients} | {dur} | {interval} | {openLoop} | {offered} | {delivered} | {deliver_pct} | {ack_saved_pct} | {ws_err_pct} | {e2e} | {dup_reorder} | {flags} | {milestones} |".format(
                ts=r.get("ts", ""),
                scenario=r.get("scenario", ""),
                file=r.get("file", ""),
                clients=r.get("clients", ""),
                dur=r.get("durationSeconds", ""),
                interval=r.get("msgIntervalMs", ""),
                openLoop="Y" if r.get("openLoop") else "N",
                offered=offered_str,
                delivered=delivered_str,
                deliver_pct=fmt_ratio(deliver_pct),
                ack_saved_pct=fmt_ratio(ack_saved_pct),
                ws_err_pct=fmt_ratio(ws_err_pct),
                e2e=e2e,
                dup_reorder=dup_reorder,
                flags=r.get("flags", ""),
                milestones=r.get("milestones", ""),
            )
        )

    out_md.write_text("\n".join(md_lines) + "\n", encoding="utf-8")

    print(f"OK: parsed_files={len(files)} records={len(rows)} parse_errors={parse_errors}")
    print(f"WROTE: {out_csv.as_posix()}")
    print(f"WROTE: {out_md.as_posix()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
