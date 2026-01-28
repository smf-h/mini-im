import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, apiPost } from '../services/api'
import { useAuthStore } from './auth'
import type { DndListResponse } from '../types/api'

const STORAGE_PREFIX = 'dnd:v1:'

type DndStorage = {
  dm: Record<string, true>
  group: Record<string, true>
}

function storageKey(userId: string) {
  return `${STORAGE_PREFIX}${userId}`
}

function safeParseStorage(raw: string | null): DndStorage | null {
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Partial<DndStorage>
    const dm = parsed.dm && typeof parsed.dm === 'object' ? (parsed.dm as Record<string, true>) : {}
    const group = parsed.group && typeof parsed.group === 'object' ? (parsed.group as Record<string, true>) : {}
    return { dm, group }
  } catch {
    return null
  }
}

export const useDndStore = defineStore('dnd', () => {
  const dm = ref<Record<string, true>>({})
  const group = ref<Record<string, true>>({})
  const hydratedFor = ref<string | null>(null)

  function hydrateLocal(userId: string) {
    const raw = localStorage.getItem(storageKey(userId))
    const parsed = safeParseStorage(raw)
    if (!parsed) return
    dm.value = parsed.dm ?? {}
    group.value = parsed.group ?? {}
  }

  function persistLocal(userId: string) {
    const payload: DndStorage = { dm: dm.value, group: group.value }
    localStorage.setItem(storageKey(userId), JSON.stringify(payload))
  }

  function isDmMuted(peerUserId: string) {
    return !!dm.value[String(peerUserId)]
  }

  function isGroupMuted(groupId: string) {
    return !!group.value[String(groupId)]
  }

  async function refreshFromServer() {
    const auth = useAuthStore()
    auth.hydrateFromStorage()
    if (!auth.userId) return

    const data = await apiGet<DndListResponse>('/dnd/list')
    const nextDm: Record<string, true> = {}
    const nextGroup: Record<string, true> = {}

    for (const id of data.dmPeerUserIds ?? []) {
      nextDm[String(id)] = true
    }
    for (const id of data.groupIds ?? []) {
      nextGroup[String(id)] = true
    }

    dm.value = nextDm
    group.value = nextGroup
    persistLocal(auth.userId)
  }

  async function hydrate() {
    const auth = useAuthStore()
    auth.hydrateFromStorage()
    if (!auth.userId) return
    if (hydratedFor.value === auth.userId) return

    hydratedFor.value = auth.userId
    hydrateLocal(auth.userId)

    try {
      await refreshFromServer()
    } catch {
      // 网络失败时保留本地缓存兜底
    }
  }

  async function setDm(peerUserId: string, muted: boolean) {
    const auth = useAuthStore()
    auth.hydrateFromStorage()

    const key = String(peerUserId)
    const before = isDmMuted(key)

    if (muted) dm.value[key] = true
    else delete dm.value[key]

    if (auth.userId) persistLocal(auth.userId)

    if (!auth.userId) return
    try {
      await apiPost('/dnd/dm/set', { peerUserId: key, muted })
    } catch (e) {
      if (before) dm.value[key] = true
      else delete dm.value[key]
      persistLocal(auth.userId)
      throw e
    }
  }

  async function toggleDm(peerUserId: string) {
    const key = String(peerUserId)
    await setDm(key, !isDmMuted(key))
  }

  async function setGroup(groupId: string, muted: boolean) {
    const auth = useAuthStore()
    auth.hydrateFromStorage()

    const key = String(groupId)
    const before = isGroupMuted(key)

    if (muted) group.value[key] = true
    else delete group.value[key]

    if (auth.userId) persistLocal(auth.userId)

    if (!auth.userId) return
    try {
      await apiPost('/dnd/group/set', { groupId: key, muted })
    } catch (e) {
      if (before) group.value[key] = true
      else delete group.value[key]
      persistLocal(auth.userId)
      throw e
    }
  }

  async function toggleGroup(groupId: string) {
    const key = String(groupId)
    await setGroup(key, !isGroupMuted(key))
  }

  return {
    dm,
    group,
    hydrate,
    refreshFromServer,
    isDmMuted,
    isGroupMuted,
    setDm,
    toggleDm,
    setGroup,
    toggleGroup,
  }
})

