import { defineStore } from 'pinia'
import { reactive } from 'vue'
import { apiGet } from '../services/api'
import type { Id } from '../types/api'

export type UserBasic = {
  id: Id
  username: string
  nickname: string | null
}

export const useUserStore = defineStore('users', () => {
  const byId = reactive<Record<string, UserBasic>>({})

  async function ensureBasics(ids: string[]) {
    const uniq = Array.from(new Set(ids.map((x) => x.trim()).filter((x) => x && !byId[x])))
    if (!uniq.length) return
    const qs = new URLSearchParams()
    qs.set('ids', uniq.join(','))
    const list = await apiGet<UserBasic[]>(`/user/basic?${qs.toString()}`)
    for (const u of list) {
      byId[String(u.id)] = u
    }
  }

  function displayName(id: string) {
    const u = byId[id]
    if (!u) return id
    return (u.nickname && u.nickname.trim()) || u.username || id
  }

  return { byId, ensureBasics, displayName }
})

