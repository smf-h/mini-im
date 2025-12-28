<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { apiGet } from '../services/api'
import type { SingleChatConversationDto } from '../types/api'
import { formatTime } from '../utils/format'
import { useAuthStore } from '../stores/auth'
import { useWsStore } from '../stores/ws'
import { useUserStore } from '../stores/users'
import type { WsEnvelope } from '../types/ws'

const route = useRoute()
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
  <div class="card" style="padding: 14px">
    <div class="row" style="justify-content: space-between; margin-bottom: 10px">
      <h2 style="margin: 0">会话</h2>
      <button class="btn" @click="resetAndLoad">刷新</button>
    </div>
    <div class="muted" style="margin-bottom: 10px">按 `updatedAt` 倒序，滚动加载。</div>

    <div class="list" @scroll="onScroll">
      <div v-for="c in items" :key="c.singleChatId" class="item">
        <RouterLink :to="`/chat/${c.peerUserId}`" class="itemLink">
          <div class="convRow">
            <div class="avatar" aria-hidden="true"></div>
            <div class="main">
              <div class="top">
                <div class="name">
                  {{ users.displayName(c.peerUserId) }}
                  <span v-if="(c.unreadCount ?? 0) > 0" class="badge">{{ c.unreadCount }}</span>
                </div>
                <div class="time muted">{{ formatTime(c.updatedAt) }}</div>
              </div>
              <div class="preview muted">{{ c.lastMessage?.content ?? '（暂无消息）' }}</div>
            </div>
          </div>
        </RouterLink>
      </div>

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
.list {
  height: calc(100vh - 170px);
  overflow: auto;
  display: grid;
  gap: 10px;
  padding: 6px;
}
.item {
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--panel);
  transition: transform 120ms ease, box-shadow 120ms ease;
}
.itemLink {
  display: block;
  padding: 12px;
}
.item:hover {
  transform: translateY(-1px);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.08);
}
.convRow {
  display: grid;
  grid-template-columns: 44px 1fr;
  gap: 12px;
  align-items: center;
}
.avatar {
  width: 44px;
  height: 44px;
  border-radius: 999px;
  background: rgba(7, 193, 96, 0.12);
  border: 1px solid rgba(7, 193, 96, 0.18);
}
.main {
  min-width: 0;
}
.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 4px;
}
.name {
  font-weight: 650;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.time {
  flex: none;
  font-size: 12px;
}
.preview {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.badge {
  min-width: 18px;
  height: 18px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--danger);
  color: #fff;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
}
</style>
