import { HTTP_BASE } from "../config"
import { authStore } from "../stores/auth"
import type { ResultEnvelope } from "../types/api"

type RequestOptions = {
  auth?: boolean
  retryOn401?: boolean
}

type RequestInit = {
  method: "GET" | "POST"
  data?: unknown
  headers?: Record<string, string>
  auth?: boolean
}

function buildUrl(path: string) {
  if (path.startsWith("http://") || path.startsWith("https://")) {
    return path
  }
  if (!path.startsWith("/")) {
    return `${HTTP_BASE}/${path}`
  }
  return `${HTTP_BASE}${path}`
}

export async function requestJson<T>(path: string, init: RequestInit, opts?: RequestOptions): Promise<T> {
  const authRequired = opts?.auth !== false && init.auth !== false
  const retryOn401 = opts?.retryOn401 !== false

  authStore.hydrate()

  const headers: Record<string, string> = { ...(init.headers ?? {}) }
  headers["Content-Type"] = headers["Content-Type"] ?? "application/json"
  if (authRequired && authStore.accessToken) {
    headers["Authorization"] = `Bearer ${authStore.accessToken}`
  }

  const { statusCode, data } = await new Promise<{ statusCode: number; data: unknown }>((resolve, reject) => {
    wx.request({
      url: buildUrl(path),
      method: init.method,
      data: init.data,
      header: headers,
      timeout: 15000,
      success: (res) => resolve({ statusCode: res.statusCode, data: res.data }),
      fail: reject,
    })
  })

  const json = data as ResultEnvelope<unknown>
  if (authRequired && retryOn401 && json && typeof json === "object" && json.ok === false && json.code === 40100) {
    try {
      await authStore.refreshAccessToken()
    } catch {
      authStore.clear()
      throw new Error("unauthorized")
    }
    return requestJson<T>(path, init, { ...opts, retryOn401: false })
  }

  if (authRequired && retryOn401 && statusCode === 401) {
    try {
      await authStore.refreshAccessToken()
    } catch {
      authStore.clear()
      throw new Error("unauthorized")
    }
    return requestJson<T>(path, init, { ...opts, retryOn401: false })
  }

  return json as unknown as T
}

export async function apiGet<T>(path: string, opts?: RequestOptions): Promise<T> {
  const env = await requestJson<ResultEnvelope<T>>(path, { method: "GET" }, opts)
  if (!env.ok) throw new Error(env.message || "request_failed")
  return env.data
}

export async function apiPost<T>(path: string, data?: unknown, opts?: RequestOptions): Promise<T> {
  const env = await requestJson<ResultEnvelope<T>>(path, { method: "POST", data: data ?? {} }, opts)
  if (!env.ok) throw new Error(env.message || "request_failed")
  return env.data
}

