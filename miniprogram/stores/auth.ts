import type { LoginResponse, RefreshResponse, ResultEnvelope } from "../types/api"
import { requestJson } from "../services/http"

const STORAGE_KEY = "mini-im.miniprogram.auth.v1"

type AuthStorage = {
  userId: string
  accessToken: string
  refreshToken: string
  accessTokenExpiresAtMs: number
}

type AuthState = {
  userId: string | null
  accessToken: string | null
  refreshToken: string | null
  accessTokenExpiresAtMs: number | null
}

const state: AuthState = {
  userId: null,
  accessToken: null,
  refreshToken: null,
  accessTokenExpiresAtMs: null,
}

export const authStore = {
  get userId() {
    return state.userId
  },
  get accessToken() {
    return state.accessToken
  },
  get refreshToken() {
    return state.refreshToken
  },
  get isLoggedIn() {
    return !!state.userId && !!state.accessToken
  },

  hydrate() {
    if (state.userId && state.accessToken) return
    try {
      const raw = wx.getStorageSync(STORAGE_KEY)
      if (!raw) return
      const parsed = JSON.parse(String(raw)) as AuthStorage
      state.userId = String(parsed.userId)
      state.accessToken = String(parsed.accessToken)
      state.refreshToken = String(parsed.refreshToken)
      state.accessTokenExpiresAtMs = Number(parsed.accessTokenExpiresAtMs)
    } catch {
      try {
        wx.removeStorageSync(STORAGE_KEY)
      } catch {
        // ignore
      }
    }
  },

  persist() {
    if (!state.userId || !state.accessToken || !state.refreshToken || !state.accessTokenExpiresAtMs) return
    const st: AuthStorage = {
      userId: state.userId,
      accessToken: state.accessToken,
      refreshToken: state.refreshToken,
      accessTokenExpiresAtMs: state.accessTokenExpiresAtMs,
    }
    wx.setStorageSync(STORAGE_KEY, JSON.stringify(st))
  },

  clear() {
    state.userId = null
    state.accessToken = null
    state.refreshToken = null
    state.accessTokenExpiresAtMs = null
    try {
      wx.removeStorageSync(STORAGE_KEY)
    } catch {
      // ignore
    }
  },

  async login(username: string, password: string) {
    const env = await requestJson<ResultEnvelope<LoginResponse>>("/auth/login", {
      method: "POST",
      data: { username, password },
      auth: false,
    })
    if (!env.ok) {
      throw new Error(env.message || "login_failed")
    }
    const data = env.data
    state.userId = String(data.userId)
    state.accessToken = data.accessToken
    state.refreshToken = data.refreshToken
    state.accessTokenExpiresAtMs = Date.now() + data.accessTokenExpiresInSeconds * 1000
    this.persist()
  },

  async refreshAccessToken(): Promise<string> {
    this.hydrate()
    if (!state.refreshToken) {
      throw new Error("missing_refresh_token")
    }
    const env = await requestJson<ResultEnvelope<RefreshResponse>>("/auth/refresh", {
      method: "POST",
      data: { refreshToken: state.refreshToken },
      auth: false,
    })
    if (!env.ok) {
      throw new Error(env.message || "refresh_failed")
    }
    const data = env.data
    state.userId = String(data.userId)
    state.accessToken = data.accessToken
    state.accessTokenExpiresAtMs = Date.now() + data.accessTokenExpiresInSeconds * 1000
    this.persist()
    return data.accessToken
  },
}

