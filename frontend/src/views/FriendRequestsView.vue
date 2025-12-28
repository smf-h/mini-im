<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { apiGet, apiPost } from '../services/api'
import type { CreateFriendRequestByCodeResponse, FriendRequestEntity } from '../types/api'
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

const toFriendCode = ref('')
const content = ref('你好，我想加你好友')
const sendStatus = ref<string | null>(null)
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

async function sendFriendRequest() {
  sendStatus.value = null
  try {
    const code = toFriendCode.value.trim().toUpperCase()
    if (!/^[A-Z0-9]{6,16}$/.test(code)) {
      sendStatus.value = 'FriendCode 无效'
      return
    }
    sendStatus.value = '发送中…'
    await apiPost<CreateFriendRequestByCodeResponse>(`/friend/request/by-code`, {
      toFriendCode: code,
      message: content.value,
    })
    sendStatus.value = '已发送'
  } catch (e) {
    sendStatus.value = `发送失败: ${String(e)}`
  }
}

async function decide(requestId: string, action: 'accept' | 'reject') {
  errorMsg.value = null
  try {
    const resp = await apiPost<{ singleChatId: string | null }>(`/friend/request/decide`, { requestId, action })
    const req = items.value.find((x) => x.id === requestId)
    if (action === 'accept' && req) {
      await router.push(`/chat/${req.fromUserId}`)
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
      <div class="muted" style="font-size: 12px">inbox/outbox/all</div>
    </div>

    <div class="composer">
      <div class="row" style="gap: 10px; align-items: stretch">
        <input v-model="toFriendCode" class="input" placeholder="对方 FriendCode" style="max-width: 180px" />
        <input v-model="content" class="input" placeholder="验证消息(<=256)" />
        <button class="btn primary" @click="sendFriendRequest">发送</button>
      </div>
      <div v-if="sendStatus" class="muted" style="margin-top: 8px">{{ sendStatus }}</div>
    </div>

    <div class="list" @scroll="onScroll">
      <UiListItem v-for="r in items" :key="r.id">
        <template #left>
          <button class="avatarBtn" type="button" @click="router.push(`/u/${r.fromUserId}`)">
            <UiAvatar :text="displayUser(r.fromUserId)" :seed="r.fromUserId" :size="42" />
          </button>
        </template>
        <div class="main">
          <div class="titleRow">
            <div class="name">{{ displayUser(r.fromUserId) }} → {{ displayUser(r.toUserId) }}</div>
            <div class="time">{{ formatTime(r.createdAt) }}</div>
          </div>
          <div class="preview">{{ r.content ?? '' }}</div>
        </div>
        <template #right>
          <div class="rightCol">
            <div class="statusTag" :data-status="r.status">{{ r.status }}</div>
            <div v-if="box === 'inbox' && r.status === 'PENDING'" class="actions">
              <button class="textBtn reject" @click.stop="decide(r.id, 'reject')">拒绝</button>
              <button class="textBtn accept" @click.stop="decide(r.id, 'accept')">同意</button>
            </div>
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
.composer {
  padding: 12px 14px;
  border-top: 1px solid var(--divider);
  border-bottom: 1px solid var(--divider);
  background: rgba(0, 0, 0, 0.015);
}
.list {
  height: calc(100vh - 290px);
  overflow: auto;
  padding: 0;
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
}
.time {
  flex: none;
  font-size: 12px;
  color: var(--text-3);
}
.preview {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  color: var(--text-2);
}
.rightCol {
  display: grid;
  gap: 8px;
  justify-items: end;
}
.statusTag {
  font-size: 12px;
  color: var(--text-3);
}
.statusTag[data-status='PENDING'] {
  color: rgba(59, 130, 246, 0.9);
}
.statusTag[data-status='ACCEPTED'] {
  color: rgba(7, 193, 96, 0.95);
}
.statusTag[data-status='REJECTED'] {
  color: rgba(17, 17, 17, 0.46);
}
.actions {
  display: flex;
  gap: 10px;
  align-items: center;
}
.textBtn {
  border: 0;
  background: transparent;
  padding: 0;
  cursor: pointer;
  font-size: 13px;
}
.textBtn.accept {
  color: var(--primary);
  font-weight: 700;
}
.textBtn.reject {
  color: rgba(17, 17, 17, 0.55);
}
.textBtn:active {
  transform: scale(0.99);
}
.avatarBtn {
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}
</style>
