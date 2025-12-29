import { HTTP_BASE } from '../config'
import type { ResultEnvelope } from '../types/api'

export class ApiError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.code = code
  }
}

export async function rawJson<T>(path: string, init?: RequestInit): Promise<{ status: number; json: T }> {
  const headers = new Headers(init?.headers ?? undefined)
  if (!headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const res = await fetch(`${HTTP_BASE}${path}`, {
    ...init,
    headers,
  })
  const json = (await res.json()) as T
  return { status: res.status, json }
}

export function unwrapResult<T>(envelope: ResultEnvelope<T>): T {
  if (!envelope.ok) {
    throw new ApiError(envelope.code, envelope.message || 'api_error')
  }
  return envelope.data
}
