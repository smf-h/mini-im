import { defineStore } from 'pinia'
import { reactive } from 'vue'
import { apiGet } from '../services/api'
import type { GroupBasicDto, Id } from '../types/api'

export type GroupBasic = {
  id: Id
  name: string
  avatarUrl?: string | null
}

export const useGroupStore = defineStore('groups', () => {
  const byId = reactive<Record<string, GroupBasic>>({})

  function upsertBasics(list: GroupBasicDto[]) {
    for (const g of list) {
      byId[String(g.id)] = g
    }
  }

  async function ensureBasics(ids: string[]) {
    const uniq = Array.from(new Set(ids.map((x) => x.trim()).filter((x) => x && !byId[x])))
    if (!uniq.length) return
    const qs = new URLSearchParams()
    qs.set('ids', uniq.join(','))
    const list = await apiGet<GroupBasicDto[]>(`/group/basic?${qs.toString()}`)
    upsertBasics(list)
  }

  function displayName(id: string) {
    const g = byId[id]
    return g?.name || id
  }

  return { byId, upsertBasics, ensureBasics, displayName }
})

