import type { ResultEnvelope } from '../types/api'
import { rawJson, unwrapResult } from './http'
import { useAuthStore } from '../stores/auth'

type RequestOptions = {
  auth?: boolean
  retryOn401?: boolean
}

export async function apiGet<T>(path: string, opts?: RequestOptions): Promise<T> {
  return apiRequest<T>(path, { method: 'GET' }, opts)
}

export async function apiPost<T>(path: string, body?: unknown, opts?: RequestOptions): Promise<T> {
  return apiRequest<T>(
    path,
    {
      method: 'POST',
      body: body == null ? undefined : JSON.stringify(body),
    },
    opts,
  )
}

export async function apiRequest<T>(path: string, init: RequestInit, opts?: RequestOptions): Promise<T> {
  const authRequired = opts?.auth !== false
  const retryOn401 = opts?.retryOn401 !== false

  const auth = useAuthStore()
  auth.hydrateFromStorage()

  const headers = new Headers(init.headers ?? {})
  if (authRequired && auth.accessToken) {
    headers.set('Authorization', `Bearer ${auth.accessToken}`)
  }

  const { status, json } = await rawJson<ResultEnvelope<T>>(path, { ...init, headers })

  // 项目里未登录通常以业务 code=40100 返回（HTTP 可能仍是 200），这里也触发 refresh 重试一次。
  if (authRequired && retryOn401 && !json.ok && json.code === 40100) {
    try {
      await auth.refreshAccessToken()
    } catch {
      auth.clear()
      throw new Error('unauthorized')
    }
    return apiRequest<T>(path, init, { ...opts, retryOn401: false })
  }

  if (status === 401 && authRequired && retryOn401) {
    try {
      await auth.refreshAccessToken()
    } catch {
      auth.clear()
      throw new Error('unauthorized')
    }
    return apiRequest<T>(path, init, { ...opts, retryOn401: false })
  }

  return unwrapResult(json)
}
