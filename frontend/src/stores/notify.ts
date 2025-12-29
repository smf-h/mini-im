import { defineStore } from 'pinia'
import { ref } from 'vue'

export type ToastKind = 'message' | 'friend_request' | 'info' | 'error'

export type Toast = {
  id: string
  kind: ToastKind
  title: string
  text: string
  avatarText?: string
  avatarSeed?: string
  path?: string
  createdAt: number
}

export type ToastPushPayload = {
  key: string
  kind?: ToastKind
  title: string
  text: string
  avatarText?: string
  avatarSeed?: string
  path?: string
}

export const useNotifyStore = defineStore('notify', () => {
  const toasts = ref<Toast[]>([])

  const seen = new Set<string>()
  const seenQueue: string[] = []

  function markSeen(key: string) {
    if (seen.has(key)) return false
    seen.add(key)
    seenQueue.push(key)
    if (seenQueue.length > 500) {
      const old = seenQueue.shift()
      if (old) seen.delete(old)
    }
    return true
  }

  function remove(id: string) {
    const idx = toasts.value.findIndex((t) => t.id === id)
    if (idx >= 0) toasts.value.splice(idx, 1)
  }

  function push(payload: ToastPushPayload, ttlMs = 5000) {
    if (!markSeen(payload.key)) return
    const id = `${Date.now()}-${Math.random().toString(16).slice(2)}`
    toasts.value.push({
      id,
      kind: payload.kind ?? 'info',
      title: payload.title,
      text: payload.text,
      avatarText: payload.avatarText,
      avatarSeed: payload.avatarSeed,
      path: payload.path,
      createdAt: Date.now(),
    })
    if (toasts.value.length > 5) {
      toasts.value.splice(0, toasts.value.length - 5)
    }
    window.setTimeout(() => remove(id), ttlMs)
  }

  return { toasts, push, remove }
})
