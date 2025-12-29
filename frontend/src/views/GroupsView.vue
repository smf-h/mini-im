<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiGet, apiPost } from '../services/api'
import type { CreateGroupResponse, GroupConversationDto, GroupJoinRequestResponse } from '../types/api'
import { formatTime } from '../utils/format'
import { useAuthStore } from '../stores/auth'
import { useWsStore } from '../stores/ws'
import { useGroupStore } from '../stores/groups'
import type { WsEnvelope } from '../types/ws'
import UiAvatar from '../components/UiAvatar.vue'
import UiBadge from '../components/UiBadge.vue'
import UiListItem from '../components/UiListItem.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ws = useWsStore()
const groups = useGroupStore()

const items = ref<GroupConversationDto[]>([])
const loading = ref(false)
const errorMsg = ref<string | null>(null)
const done = ref(false)
const wsCursor = ref(0)

const createName = ref('')
const createStatus = ref<string | null>(null)

const joinGroupCode = ref('')
const joinMessage = ref('你好，我想加入该群')
const joinStatus = ref<string | null>(null)

const createNameEl = ref<HTMLInputElement | null>(null)
const joinCodeEl = ref<HTMLInputElement | null>(null)

const cursor = computed(() => {
  const last = items.value.length ? items.value[items.value.length - 1] : undefined
  if (!last) return null
  return { lastUpdatedAt: last.updatedAt, lastId: last.groupId }
})

async function loadMore() {
  if (loading.value || done.value) return
  loading.value = true
  errorMsg.value = null
  try {
    const qs = new URLSearchParams()
    qs.set('limit', '20')
    if (cursor.value) {
      qs.set('lastUpdatedAt', cursor.value.lastUpdatedAt)
      qs.set('lastId', String(cursor.value.lastId))
    }
    const data = await apiGet<GroupConversationDto[]>(`/group/conversation/cursor?${qs.toString()}`)
    if (!data.length) {
      done.value = true
      return
    }
    items.value.push(...data)
    groups.upsertBasics(data.map((x) => ({ id: x.groupId, name: x.name })))
  } catch (e) {
    errorMsg.value = String(e)
  } finally {
    loading.value = false
  }
}

function resetAndLoad() {
  items.value = []
  done.value = false
  void loadMore()
}

function onScroll(e: Event) {
  const el = e.target as HTMLElement
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 80) {
    void loadMore()
  }
}

function applyWsEvent(ev: WsEnvelope) {
  if (ev.type !== 'GROUP_CHAT') return
  if (!auth.userId) return
  if (!ev.groupId) return
  if (!ev.from) return

  const isChatOpen = route.path.startsWith('/group/') && route.params.groupId === ev.groupId

  const idx = items.value.findIndex((c) => c.groupId === ev.groupId)
  if (idx < 0) {
    resetAndLoad()
    return
  }

  const cur = items.value[idx]!
  const ts = ev.ts ?? Date.now()
  const updatedAt = new Date(ts).toISOString()
  const next: GroupConversationDto = {
    ...cur,
    updatedAt,
    unreadCount: isChatOpen || ev.from === auth.userId ? 0 : (cur.unreadCount ?? 0) + 1,
    mentionUnreadCount: isChatOpen ? 0 : (ev.important ? (cur.mentionUnreadCount ?? 0) + 1 : cur.mentionUnreadCount ?? 0),
    lastMessage: {
      serverMsgId: ev.serverMsgId ?? cur.lastMessage?.serverMsgId ?? '',
      fromUserId: ev.from,
      content: ev.body ?? '',
      createdAt: updatedAt,
    },
  }

  items.value.splice(idx, 1)
  items.value.unshift(next)
}

watch(
  () => ws.events.length,
  () => {
    for (let i = wsCursor.value; i < ws.events.length; i++) {
      applyWsEvent(ws.events[i]!)
    }
    wsCursor.value = ws.events.length
  },
)

async function createGroup() {
  createStatus.value = null
  const name = createName.value.trim()
  if (!name) {
    createStatus.value = '请输入群名'
    return
  }
  try {
    const resp = await apiPost<CreateGroupResponse>(`/group/create`, { name, memberUserIds: [] })
    createStatus.value = '创建成功'
    createName.value = ''
    resetAndLoad()
    void router.push(`/chats/group/${resp.groupId}`)
  } catch (e) {
    createStatus.value = String(e)
  }
}

async function requestJoin() {
  joinStatus.value = null
  const code = joinGroupCode.value.trim().toUpperCase()
  if (!/^[A-Z0-9]{6,16}$/.test(code)) {
    joinStatus.value = '群码无效'
    return
  }
  try {
    await apiPost<GroupJoinRequestResponse>(`/group/join/request`, { groupCode: code, message: joinMessage.value })
    joinStatus.value = '已提交申请，等待审批'
    joinGroupCode.value = ''
  } catch (e) {
    joinStatus.value = String(e)
  }
}

function openGroupProfile(ev: MouseEvent, gid: string) {
  ev.preventDefault()
  ev.stopPropagation()
  void router.push(`/chats/group/${gid}/profile`)
}

onMounted(() => {
  wsCursor.value = ws.events.length
  void ws.connect()
  void loadMore()
})

function applyRouteAction() {
  const action = String(route.query.action ?? '')
  if (action === 'create') {
    void nextTick(() => createNameEl.value?.focus())
    return
  }
  if (action === 'join') {
    void nextTick(() => joinCodeEl.value?.focus())
  }
}

watch(
  () => route.query.action,
  () => {
    applyRouteAction()
  },
)

onMounted(() => {
  applyRouteAction()
})
</script>

<template>
  <div class="page">
    <div class="header row" style="justify-content: space-between">
      <h2 style="margin: 0">群聊</h2>
      <button class="btn" @click="resetAndLoad">刷新</button>
    </div>

    <div class="create">
      <div class="muted" style="margin-bottom: 8px">创建群后成员通过“群码申请入群”，由群主/管理员审批。</div>
      <div class="row" style="gap: 10px">
        <input ref="createNameEl" v-model="createName" class="input" placeholder="群名" />
        <button class="btn primary" @click="createGroup">创建</button>
      </div>
      <div v-if="createStatus" class="muted" style="margin-top: 8px">{{ createStatus }}</div>
    </div>

    <div class="join">
      <div class="muted" style="margin-bottom: 8px">通过群码申请加入：</div>
      <div class="row" style="gap: 10px; align-items: stretch">
        <input
          ref="joinCodeEl"
          v-model="joinGroupCode"
          class="input"
          placeholder="GroupCode"
          style="max-width: 180px"
        />
        <input v-model="joinMessage" class="input" placeholder="验证信息(<=256)" />
        <button class="btn" @click="requestJoin">申请</button>
      </div>
      <div v-if="joinStatus" class="muted" style="margin-top: 8px">{{ joinStatus }}</div>
    </div>

    <div class="sub muted">按 `updatedAt` 倒序，滚动加载。</div>

    <div class="list" @scroll="onScroll">
      <UiListItem v-for="c in items" :key="c.groupId" :to="`/chats/group/${c.groupId}`">
        <template #left>
          <button class="avatarBtn" type="button" @click="openGroupProfile($event, c.groupId)">
            <UiAvatar :text="c.name || groups.displayName(c.groupId)" :seed="c.groupId" :size="46" />
          </button>
        </template>
        <div class="main">
          <div class="titleRow">
            <div class="name">{{ c.name || groups.displayName(c.groupId) }}</div>
            <div class="time">{{ formatTime(c.updatedAt) }}</div>
          </div>
          <div class="preview">{{ c.lastMessage?.content ?? '（暂无消息）' }}</div>
        </div>
        <template #right>
          <div class="rightCol">
            <div v-if="(c.mentionUnreadCount ?? 0) > 0" class="mentionTag">@</div>
            <UiBadge :count="c.unreadCount ?? 0" />
          </div>
        </template>
      </UiListItem>

      <div class="muted" style="padding: 10px; text-align: center">
        <span v-if="loading">加载中…</span>
        <span v-else-if="done">没有更多了</span>
        <span v-else>下滑加载更多</span>
      </div>
    </div>

    <div v-if="errorMsg" class="muted" style="color: var(--danger); margin-top: 10px">{{ errorMsg }}</div>
  </div>
</template>

<style scoped>
.page {
  height: 100%;
  display: flex;
  flex-direction: column;
  border-radius: var(--radius-card);
  background: var(--surface);
  border: 1px solid var(--divider);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}
.header {
  padding: 14px 14px 10px;
}
.sub {
  padding: 0 14px 10px;
}
.create {
  padding: 12px 14px;
  border-top: 1px solid var(--divider);
  border-bottom: 1px solid var(--divider);
  background: rgba(0, 0, 0, 0.015);
}
.join {
  padding: 12px 14px;
  border-bottom: 1px solid var(--divider);
}
.list {
  flex: 1;
  overflow: auto;
  padding: 0;
}
.main {
  min-width: 0;
}
.titleRow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 4px;
}
.name {
  font-weight: 750;
  font-size: 16px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.time {
  flex: none;
  font-size: 12px;
  color: var(--text-3);
}
.preview {
  font-size: 13px;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rightCol {
  display: grid;
  gap: 6px;
  justify-items: end;
}
.mentionTag {
  width: 18px;
  height: 18px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  background: rgba(59, 130, 246, 0.16);
  border: 1px solid rgba(59, 130, 246, 0.22);
  color: rgba(30, 64, 175, 0.9);
  font-weight: 900;
  font-size: 12px;
}
.avatarBtn {
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}
</style>
