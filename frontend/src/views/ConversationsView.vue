<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiGet } from '../services/api'
import type { SingleChatConversationDto } from '../types/api'
import { formatTime } from '../utils/format'
import { useAuthStore } from '../stores/auth'
import { useWsStore } from '../stores/ws'
import { useUserStore } from '../stores/users'
import type { WsEnvelope } from '../types/ws'
import UiAvatar from '../components/UiAvatar.vue'
import UiBadge from '../components/UiBadge.vue'
import UiListItem from '../components/UiListItem.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ws = useWsStore()
const users = useUserStore()

const items = ref<SingleChatConversationDto[]>([])
const loading = ref(false)
const errorMsg = ref<string | null>(null)
const done = ref(false)
const wsCursor = ref(0)

const cursor = computed(() => {
  const last = items.value.length ? items.value[items.value.length - 1] : undefined
  if (!last) return null
  return { lastUpdatedAt: last.updatedAt, lastId: last.singleChatId }
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
    const data = await apiGet<SingleChatConversationDto[]>(`/single-chat/conversation/cursor?${qs.toString()}`)
    if (!data.length) {
      done.value = true
      return
    }
    items.value.push(...data)
    void users.ensureBasics(data.map((x) => x.peerUserId))
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
  if (ev.type !== 'SINGLE_CHAT') return
  if (!auth.userId) return
  if (!ev.from || !ev.to) return
  if (ev.to !== auth.userId) return

  const isChatOpen = route.path.startsWith('/chat/') && route.params.peerUserId === ev.from

  const idx = items.value.findIndex((c) => c.peerUserId === ev.from)
  if (idx < 0) {
    resetAndLoad()
    return
  }

  const cur = items.value[idx]!
  const ts = ev.ts ?? Date.now()
  const updatedAt = new Date(ts).toISOString()
  const next: SingleChatConversationDto = {
    ...cur,
    updatedAt,
    unreadCount: isChatOpen ? 0 : (cur.unreadCount ?? 0) + 1,
    lastMessage: {
      serverMsgId: ev.serverMsgId ?? cur.lastMessage?.serverMsgId ?? '',
      fromUserId: ev.from,
      toUserId: ev.to,
      content: ev.body ?? '',
      createdAt: updatedAt,
    },
  }

  items.value.splice(idx, 1)
  items.value.unshift(next)
  void users.ensureBasics([ev.from])
}

function openUser(ev: MouseEvent, id: string) {
  ev.preventDefault()
  ev.stopPropagation()
  void router.push(`/u/${id}`)
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

onMounted(() => {
  wsCursor.value = ws.events.length
  void loadMore()
})
</script>

<template>
  <div class="page">
    <div class="header row" style="justify-content: space-between">
      <h2 style="margin: 0">会话</h2>
      <button class="btn" @click="resetAndLoad">刷新</button>
    </div>
    <div class="sub muted">按 `updatedAt` 倒序，滚动加载。</div>

    <div class="list" @scroll="onScroll">
      <UiListItem v-for="c in items" :key="c.singleChatId" :to="`/chat/${c.peerUserId}`">
        <template #left>
          <button class="avatarBtn" type="button" @click="openUser($event, c.peerUserId)">
            <UiAvatar :text="users.displayName(c.peerUserId)" :seed="c.peerUserId" :size="46" />
          </button>
        </template>
        <div class="main">
          <div class="titleRow">
            <div class="name">{{ users.displayName(c.peerUserId) }}</div>
            <div class="time">{{ formatTime(c.updatedAt) }}</div>
          </div>
          <div class="preview">{{ c.lastMessage?.content ?? '（暂无消息）' }}</div>
        </div>
        <template #right>
          <UiBadge :count="c.unreadCount ?? 0" />
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
.list {
  height: calc(100vh - 170px);
  overflow: auto;
  padding: 0;
}
.main {
  min-width: 0;
}
.titleRow {
  display: flex;
  align-items: baseline;
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
.avatarBtn {
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}
</style>
