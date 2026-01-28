import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { WS_URL } from '../config'
import type { WsEnvelope } from '../types/ws'
import { useAuthStore } from './auth'
import { router } from '../router'

type WsState = 'idle' | 'connecting' | 'open' | 'closed'
type Waiter = { resolve: () => void; reject: (e: unknown) => void; timeoutId: number }

export const useWsStore = defineStore('ws', () => {
  const state = ref<WsState>('idle')
  const lastError = ref<string | null>(null)
  const authed = ref(false)
  const events = ref<WsEnvelope[]>([])

  let socket: WebSocket | null = null
  let reconnectTimer: number | null = null
  let intentionalClose = false
  let waiters: Waiter[] = []
  let connectPromise: Promise<void> | null = null
  let reconnectAttempts = 0

  const connected = computed(() => state.value === 'open')

  function pushEvent(ev: WsEnvelope) {
    events.value.push(ev)
    if (events.value.length > 500) {
      events.value.splice(0, events.value.length - 500)
    }
  }

  function close() {
    intentionalClose = true
    if (reconnectTimer != null) {
      window.clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    authed.value = false
    state.value = 'closed'
    flushWaiters(new Error('ws_closed'))
    try {
      socket?.close()
    } catch {
      // ignore
    }
    socket = null
  }

  function gotoLogin() {
    try {
      if (router.currentRoute.value.path !== '/login') {
        void router.push('/login')
      }
    } catch {
      // ignore
    }
  }

  function flushWaiters(err: unknown) {
    if (!waiters.length) return
    const cur = waiters
    waiters = []
    for (const w of cur) {
      window.clearTimeout(w.timeoutId)
      w.reject(err)
    }
  }

  function resolveWaiters() {
    if (!waiters.length) return
    const cur = waiters
    waiters = []
    for (const w of cur) {
      window.clearTimeout(w.timeoutId)
      w.resolve()
    }
  }

  function awaitAuthed(timeoutMs = 8000): Promise<void> {
    if (authed.value) return Promise.resolve()
    return new Promise((resolve, reject) => {
      const timeoutId = window.setTimeout(() => {
        waiters = waiters.filter((w) => w.timeoutId !== timeoutId)
        reject(new Error('auth_timeout'))
      }, timeoutMs)
      waiters.push({ resolve, reject, timeoutId })
    })
  }

  function nextReconnectDelayMs() {
    // 指数退避 + 抖动，避免网络抖动/服务端重启时前端疯狂重连刷屏
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
    const delayMs = nextReconnectDelayMs()
    reconnectTimer = window.setTimeout(async () => {
      reconnectTimer = null
      try {
        await connect()
      } catch {
        scheduleReconnect()
      }
    }, delayMs)
  }

  async function connect() {
    if (connectPromise) return connectPromise

    const auth = useAuthStore()
    auth.hydrateFromStorage()

    const token = auth.accessToken
    if (!token) {
      throw new Error('missing_access_token')
    }
    if (socket && state.value === 'open') {
      if (authed.value) return
      try {
        send({ type: 'AUTH', token })
      } catch {
        // ignore
      }
      await awaitAuthed()
      return
    }

    connectPromise = (async () => {
      intentionalClose = false
      state.value = 'connecting'
      authed.value = false
      lastError.value = null

      socket = new WebSocket(WS_URL)

      socket.onopen = () => {
        state.value = 'open'
        try {
          send({ type: 'AUTH', token })
        } catch {
          // ignore
        }
      }

      socket.onmessage = (e) => {
        try {
          const parsed = JSON.parse(String(e.data)) as WsEnvelope
          pushEvent(parsed)
          if (parsed.type === 'AUTH_OK') {
            authed.value = true
            reconnectAttempts = 0
            resolveWaiters()
          }
          if (parsed.type === 'AUTH_FAIL') {
            lastError.value = parsed.reason ?? 'auth_fail'
            flushWaiters(new Error(lastError.value))
            if (parsed.reason === 'session_invalid' || parsed.reason === 'invalid_token') {
              auth.clear()
              close()
              gotoLogin()
              return
            }
            try {
              socket?.close()
            } catch {
              // ignore
            }
          }
          if (parsed.type === 'ERROR' && parsed.reason === 'token_expired') {
            flushWaiters(new Error('token_expired'))
            void handleTokenExpired()
          }
          if (parsed.type === 'ERROR' && (parsed.reason === 'auth_timeout' || parsed.reason === 'unauthorized')) {
            lastError.value = parsed.reason ?? 'error'
            flushWaiters(new Error(lastError.value))
            try {
              socket?.close()
            } catch {
              // ignore
            }
          }
          if (parsed.type === 'ERROR' && (parsed.reason === 'kicked' || parsed.reason === 'session_invalid')) {
            auth.clear()
            close()
            gotoLogin()
          }
        } catch {
          // ignore
        }
      }

      socket.onclose = () => {
        authed.value = false
        state.value = 'closed'
        flushWaiters(new Error('ws_closed'))
        scheduleReconnect()
      }

      socket.onerror = () => {
        lastError.value = 'ws_error'
        flushWaiters(new Error('ws_error'))
      }

      try {
        await awaitAuthed()
      } catch (e) {
        lastError.value = String(e)
        try {
          socket?.close()
        } catch {
          // ignore
        }
        throw e
      }
    })()

    try {
      await connectPromise
    } finally {
      connectPromise = null
    }
  }

  async function handleTokenExpired() {
    const auth = useAuthStore()
    try {
      await auth.refreshAccessToken()
      close()
      intentionalClose = false
      await connect()
    } catch {
      auth.clear()
      close()
      gotoLogin()
    }
  }

  function send(env: WsEnvelope) {
    if (!socket || state.value !== 'open') {
      throw new Error('ws_not_connected')
    }
    socket.send(JSON.stringify(env))
  }

  // Vite HMR：避免热更新导致旧 WS 连接残留，出现“反复连接 / 多连接并存”
  try {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const hot = (import.meta as any).hot
    if (hot?.dispose) {
      hot.dispose(() => close())
    }
  } catch {
    // ignore
  }

  return {
    state,
    connected,
    authed,
    lastError,
    events,
    connect,
    close,
    send,
  }
})
