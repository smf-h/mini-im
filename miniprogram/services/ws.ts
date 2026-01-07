import { WS_URL } from "../config"
import { authStore } from "../stores/auth"
import { Emitter } from "../utils/emitter"
import type { WsEnvelope } from "../types/ws"

export type WsState = "idle" | "connecting" | "open" | "closed"

type WsSnapshot = {
  state: WsState
  authed: boolean
  lastError: string | null
}

const state: WsSnapshot = {
  state: "idle",
  authed: false,
  lastError: null,
}

let reconnectTimer: number | null = null
let intentionalClose = false
let reconnectAttempts = 0

const emitter = new Emitter<WsEnvelope>()
const stateEmitter = new Emitter<WsSnapshot>()

function emitState() {
  stateEmitter.emit({ ...state })
}

function nextReconnectDelayMs() {
  reconnectAttempts = Math.min(reconnectAttempts + 1, 10)
  const base = 500
  const max = 30_000
  const exp = Math.min(max, base * Math.pow(2, reconnectAttempts - 1))
  const jitter = Math.floor(Math.random() * 300)
  return exp + jitter
}

function scheduleReconnect() {
  if (intentionalClose) return
  if (reconnectTimer != null) return
  const delay = nextReconnectDelayMs()
  reconnectTimer = (setTimeout(() => {
    reconnectTimer = null
    void connect()
  }, delay) as unknown) as number
}

function buildWsUrl(token: string) {
  return `${WS_URL}${WS_URL.includes("?") ? "&" : "?"}token=${encodeURIComponent(token)}`
}

export function onWsEvent(fn: (e: WsEnvelope) => void) {
  return emitter.on(fn)
}

export function onWsState(fn: (s: WsSnapshot) => void) {
  return stateEmitter.on(fn)
}

export function getWsState(): WsSnapshot {
  return { ...state }
}

export function close() {
  intentionalClose = true
  if (reconnectTimer != null) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  state.authed = false
  state.state = "closed"
  emitState()
  try {
    wx.closeSocket()
  } catch {
    // ignore
  }
}

async function handleTokenExpired() {
  try {
    await authStore.refreshAccessToken()
  } catch {
    authStore.clear()
    close()
    return
  }
  close()
  intentionalClose = false
  await connect()
}

export async function connect() {
  authStore.hydrate()
  const token = authStore.accessToken
  if (!token) {
    throw new Error("missing_access_token")
  }

  if (state.state === "open" && state.authed) {
    return
  }

  intentionalClose = false
  state.state = "connecting"
  state.authed = false
  state.lastError = null
  emitState()

  await new Promise<void>((resolve, reject) => {
    try {
      wx.connectSocket({ url: buildWsUrl(token) })
    } catch (e) {
      reject(e)
      return
    }

    wx.onSocketOpen(() => {
      state.state = "open"
      emitState()
      try {
        send({ type: "AUTH", token })
      } catch {
        // ignore
      }
      resolve()
    })

    wx.onSocketError(() => {
      state.lastError = "ws_error"
      emitState()
      reject(new Error("ws_error"))
    })

    wx.onSocketClose(() => {
      state.authed = false
      state.state = "closed"
      emitState()
      scheduleReconnect()
    })

    wx.onSocketMessage((msg) => {
      try {
        const raw = typeof msg.data === "string" ? msg.data : String(msg.data)
        const parsed = JSON.parse(raw) as WsEnvelope
        emitter.emit(parsed)

        if (parsed.type === "AUTH_OK") {
          state.authed = true
          reconnectAttempts = 0
          emitState()
          return
        }
        if (parsed.type === "AUTH_FAIL") {
          state.lastError = parsed.reason ?? "auth_fail"
          state.authed = false
          emitState()
          if (parsed.reason === "session_invalid" || parsed.reason === "invalid_token") {
            authStore.clear()
            close()
            return
          }
          try {
            wx.closeSocket()
          } catch {
            // ignore
          }
          return
        }
        if (parsed.type === "ERROR" && parsed.reason === "token_expired") {
          void handleTokenExpired()
          return
        }
        if (parsed.type === "ERROR" && (parsed.reason === "kicked" || parsed.reason === "session_invalid")) {
          authStore.clear()
          close()
        }
      } catch {
        // ignore
      }
    })
  })
}

export function send(env: WsEnvelope) {
  if (state.state !== "open") {
    throw new Error("ws_not_connected")
  }
  wx.sendSocketMessage({ data: JSON.stringify(env) })
}

