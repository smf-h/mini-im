import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { LoginResponse, RefreshResponse, ResultEnvelope } from '../types/api'
import { rawJson, unwrapResult } from '../services/http'

const STORAGE_KEY = 'mini-im.auth.v1'

type AuthStorage = {
  userId: string
  accessToken: string
  refreshToken: string
  accessTokenExpiresAtMs: number
}

export const useAuthStore = defineStore('auth', () => {
  const userId = ref<string | null>(null)
  const accessToken = ref<string | null>(null)
  const refreshToken = ref<string | null>(null)
  const accessTokenExpiresAtMs = ref<number | null>(null)

  const isLoggedIn = computed(() => !!accessToken.value && !!userId.value)

  function hydrateFromStorage() {
    if (accessToken.value && userId.value) return
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return
    try {
      const parsed = JSON.parse(raw) as AuthStorage
      userId.value = String(parsed.userId)
      accessToken.value = parsed.accessToken
      refreshToken.value = parsed.refreshToken
      accessTokenExpiresAtMs.value = parsed.accessTokenExpiresAtMs
    } catch {
      localStorage.removeItem(STORAGE_KEY)
    }
  }

  function persist() {
    if (!userId.value || !accessToken.value || !refreshToken.value || !accessTokenExpiresAtMs.value) return
    const st: AuthStorage = {
      userId: userId.value,
      accessToken: accessToken.value,
      refreshToken: refreshToken.value,
      accessTokenExpiresAtMs: accessTokenExpiresAtMs.value,
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(st))
  }

  function clear() {
    userId.value = null
    accessToken.value = null
    refreshToken.value = null
    accessTokenExpiresAtMs.value = null
    localStorage.removeItem(STORAGE_KEY)
  }

  async function login(username: string, password: string) {
    const { json } = await rawJson<ResultEnvelope<LoginResponse>>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    })
    const data = unwrapResult(json)
    userId.value = String(data.userId)
    accessToken.value = data.accessToken
    refreshToken.value = data.refreshToken
    accessTokenExpiresAtMs.value = Date.now() + data.accessTokenExpiresInSeconds * 1000
    persist()
  }

  async function refreshAccessToken(): Promise<string> {
    hydrateFromStorage()
    if (!refreshToken.value) {
      throw new Error('missing_refresh_token')
    }
    const { json } = await rawJson<ResultEnvelope<RefreshResponse>>('/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken: refreshToken.value }),
    })
    const data = unwrapResult(json)
    userId.value = String(data.userId)
    accessToken.value = data.accessToken
    accessTokenExpiresAtMs.value = Date.now() + data.accessTokenExpiresInSeconds * 1000
    persist()
    return data.accessToken
  }

  return {
    userId,
    accessToken,
    refreshToken,
    accessTokenExpiresAtMs,
    isLoggedIn,
    hydrateFromStorage,
    login,
    refreshAccessToken,
    clear,
  }
})
