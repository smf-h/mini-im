<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { apiGet, apiPost } from '../services/api'
import type { FriendRequestEntity } from '../types/api'
import type { WsEnvelope } from '../types/ws'
import { useAuthStore } from '../stores/auth'
import { useWsStore } from '../stores/ws'
import { useUserStore } from '../stores/users'
import { formatTime } from '../utils/format'
import UiAvatar from '../components/UiAvatar.vue'
import UiListItem from '../components/UiListItem.vue'
import UiSegmented from '../components/UiSegmented.vue'

type Box = 'inbox' | 'outbox' | 'all'

const router = useRouter()
const auth = useAuthStore()
const ws = useWsStore()
const users = useUserStore()

const box = ref<Box>('inbox')
const items = ref<FriendRequestEntity[]>([])
const loading = ref(false)
const done = ref(false)
const errorMsg = ref<string | null>(null)

const wsCursor = ref(0)

const boxOptions = [
  { value: 'inbox', label: '收到的' },
  { value: 'outbox', label: '发出的' },
  { value: 'all', label: '全部' },
]

const lastId = computed(() => (items.value.length ? items.value[items.value.length - 1]!.id : null))

async function loadMore() {
  if (loading.value || done.value) return
  loading.value = true
  errorMsg.value = null
  try {
    const qs = new URLSearchParams()
    qs.set('box', box.value)
    qs.set('limit', '20')
    if (lastId.value != null) {
      qs.set('lastId', String(lastId.value))
    }
    const data = await apiGet<FriendRequestEntity[]>(`/friend/request/cursor?${qs.toString()}`)
    if (!data.length) {
      done.value = true
      return
    }
    items.value.push(...data)
    void users.ensureBasics([...new Set(data.flatMap((x) => [x.fromUserId, x.toUserId]))])
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

async function decide(requestId: string, action: 'accept' | 'reject') {
  errorMsg.value = null
  try {
    const resp = await apiPost<{ singleChatId: string | null }>(`/friend/request/decide`, { requestId, action })
    const req = items.value.find((x) => x.id === requestId)
    if (action === 'accept' && req) {
      await router.push(`/chats/dm/${req.fromUserId}`)
      return resp
    }
    resetAndLoad()
    return resp
  } catch (e) {
    errorMsg.value = String(e)
    return null
  }
}

function applyWsEvent(ev: WsEnvelope) {
  if (ev.type === 'FRIEND_REQUEST') {
    if (!ev.to || ev.to !== auth.userId) return
    resetAndLoad()
  }
}

function displayUser(id: string) {
  if (id === auth.userId) return '我'
  return users.displayName(id)
}

function primaryUserId(r: FriendRequestEntity) {
  if (box.value === 'inbox') return r.fromUserId
  if (box.value === 'outbox') return r.toUserId
  return r.fromUserId
}

function titleText(r: FriendRequestEntity) {
  if (box.value === 'inbox') return displayUser(r.fromUserId)
  if (box.value === 'outbox') return `发给 ${displayUser(r.toUserId)}`
  return `${displayUser(r.fromUserId)}`
}

function statusLabel(status: FriendRequestEntity['status']) {
  if (status === 'ACCEPTED') return '已添加'
  if (status === 'REJECTED') return '已拒绝'
  if (status === 'PENDING') return '待处理'
  return String(status)
}

function statusTone(status: FriendRequestEntity['status']) {
  if (status === 'ACCEPTED') return 'ok'
  if (status === 'REJECTED') return 'neutral'
  if (status === 'PENDING') return 'pending'
  return 'neutral'
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

watch(
  () => box.value,
  () => {
    resetAndLoad()
  },
)

onMounted(() => {
  wsCursor.value = ws.events.length
  void ws.connect()
  void loadMore()
})
</script>

<template>
  <div class="page">
    <div class="header row" style="justify-content: space-between">
      <h2 style="margin: 0">好友申请</h2>
      <button class="btn" @click="resetAndLoad">刷新</button>
    </div>

    <div class="sub row" style="justify-content: space-between">
      <UiSegmented v-model="box" :options="boxOptions" />
      <div class="muted" style="font-size: 12px">添加好友请点左侧通讯录的 +</div>
    </div>

    <div class="list" @scroll="onScroll">
      <UiListItem v-for="r in items" :key="r.id">
        <template #left>
          <button class="avatarBtn" type="button" @click="router.push(`/contacts/u/${primaryUserId(r)}`)">
            <UiAvatar :text="displayUser(primaryUserId(r))" :seed="primaryUserId(r)" :size="40" />
          </button>
        </template>
        <div class="main">
          <div class="titleRow">
            <div class="name">
              <template v-if="box === 'all'">
                <span class="who">{{ displayUser(r.fromUserId) }}</span>
                <svg class="dirIcon" viewBox="0 0 24 24" aria-hidden="true">
                  <path
                    fill="currentColor"
                    d="M13.5 5.25a.75.75 0 0 1 .75-.75h6a.75.75 0 0 1 .75.75v6a.75.75 0 0 1-1.5 0V7.06L11.78 14.78a.75.75 0 0 1-1.06-1.06L18.44 5.999h-4.19a.75.75 0 0 1-.75-.75Z"
                  />
                  <path
                    fill="currentColor"
                    d="M5.5 7.25A2.75 2.75 0 0 1 8.25 4.5h3a.75.75 0 0 1 0 1.5h-3c-.69 0-1.25.56-1.25 1.25v8.5c0 .69.56 1.25 1.25 1.25h8.5c.69 0 1.25-.56 1.25-1.25v-3a.75.75 0 0 1 1.5 0v3a2.75 2.75 0 0 1-2.75 2.75h-8.5A2.75 2.75 0 0 1 5.5 15.75v-8.5Z"
                  />
                </svg>
                <span class="who">{{ displayUser(r.toUserId) }}</span>
              </template>
              <template v-else>
                {{ titleText(r) }}
              </template>
            </div>
            <div class="time">{{ formatTime(r.createdAt) }}</div>
          </div>
          <div class="preview">{{ r.content ?? '' }}</div>
        </div>
        <template #right>
          <div class="rightCol">
            <template v-if="box === 'inbox' && r.status === 'PENDING'">
              <button class="btnSmall" @click.stop="decide(r.id, 'reject')">拒绝</button>
              <button class="btnSmall primary" @click.stop="decide(r.id, 'accept')">同意</button>
            </template>
            <div v-else class="statusPill" :data-tone="statusTone(r.status)">{{ statusLabel(r.status) }}</div>
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
  background: var(--bg-main, var(--bg));
}
.header {
  padding: 14px 16px 10px;
}
.sub {
  padding: 0 16px 10px;
}
.list {
  flex: 1;
  overflow: auto;
  padding: 0;
  background: var(--surface);
  border-top: 1px solid var(--divider);
}
.main {
  min-width: 0;
}
.titleRow {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 4px;
}
.name {
  font-weight: 750;
  font-size: 15px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.who {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}
.dirIcon {
  flex: none;
  width: 16px;
  height: 16px;
  color: rgba(15, 23, 42, 0.52);
}
.time {
  flex: none;
  font-size: 12px;
  color: var(--text-3);
}
.preview {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--text-2);
}
.rightCol {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
}
.statusPill {
  font-size: 12px;
  white-space: nowrap;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(17, 17, 17, 0.04);
  color: rgba(17, 17, 17, 0.62);
}
.statusPill[data-tone='ok'] {
  border-color: rgba(7, 193, 96, 0.22);
  background: rgba(7, 193, 96, 0.1);
  color: rgba(7, 193, 96, 0.92);
  font-weight: 750;
}
.statusPill[data-tone='pending'] {
  border-color: rgba(245, 158, 11, 0.22);
  background: rgba(245, 158, 11, 0.12);
  color: rgba(154, 52, 18, 0.8);
  font-weight: 750;
}
.btnSmall {
  border: 1px solid rgba(17, 17, 17, 0.16);
  background: rgba(255, 255, 255, 0.92);
  padding: 0 10px;
  height: 28px;
  border-radius: 999px;
  cursor: pointer;
  font-size: 13px;
  color: rgba(17, 17, 17, 0.78);
}
.btnSmall.primary {
  border-color: rgba(7, 193, 96, 0.32);
  background: var(--primary);
  color: #ffffff;
  font-weight: 800;
}
.btnSmall:hover {
  background: rgba(0, 0, 0, 0.03);
}
.btnSmall.primary:hover {
  background: var(--primary-hover, #06ad56);
}
.btnSmall:active {
  transform: scale(0.99);
}
.avatarBtn {
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}
</style>
