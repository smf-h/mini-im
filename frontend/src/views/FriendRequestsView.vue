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

const toUserId = ref('')
const content = ref('hi-friend-request')
const sendStatus = ref<string | null>(null)
const sendClientMsgId = ref<string | null>(null)
const wsCursor = ref(0)

const lastId = computed(() => (items.value.length ? items.value[items.value.length - 1]!.id : null))

function uuid() {
  return crypto.randomUUID()
}

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
  const to = toUserId.value.trim()
  if (!/^[1-9][0-9]*$/.test(to)) {
    sendStatus.value = 'toUserId 无效'
    return
  }
  if (to === auth.userId) {
    sendStatus.value = '不能给自己发申请'
    return
  }

  const clientMsgId = `fr-${uuid()}`
  sendClientMsgId.value = clientMsgId
  sendStatus.value = '发送中…'

  try {
    await ws.connect()
    ws.send({
      type: 'FRIEND_REQUEST',
      clientMsgId,
      to,
      body: content.value,
      ts: Date.now(),
    })
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
  if (ev.type === 'ACK' && (ev.ackType?.toUpperCase() === 'SAVED' || ev.ackType === 'saved')) {
    if (sendClientMsgId.value && ev.clientMsgId === sendClientMsgId.value) {
      sendStatus.value = '已发送'
      return
    }
  }
  if (ev.type === 'ERROR') {
    if (sendClientMsgId.value && ev.clientMsgId === sendClientMsgId.value) {
      sendStatus.value = `发送失败: ${ev.reason ?? 'error'}`
      return
    }
  }
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
  <div class="card" style="padding: 14px">
    <div class="row" style="justify-content: space-between; margin-bottom: 10px">
      <h2 style="margin: 0">好友申请</h2>
      <button class="btn" @click="resetAndLoad">刷新</button>
    </div>

    <div class="card" style="padding: 12px; margin-bottom: 12px">
      <div class="row" style="gap: 10px">
        <input v-model="toUserId" class="input" placeholder="toUserId" style="max-width: 140px" />
        <input v-model="content" class="input" placeholder="验证消息(<=256)" />
        <button class="btn primary" @click="sendFriendRequest">发送申请</button>
      </div>
      <div v-if="sendStatus" class="muted" style="margin-top: 8px">{{ sendStatus }}</div>
    </div>

    <div class="row" style="gap: 8px; margin-bottom: 10px">
      <button class="btn" :class="{ primary: box === 'inbox' }" @click="box = 'inbox'">inbox</button>
      <button class="btn" :class="{ primary: box === 'outbox' }" @click="box = 'outbox'">outbox</button>
      <button class="btn" :class="{ primary: box === 'all' }" @click="box = 'all'">all</button>
    </div>

    <div class="list" @scroll="onScroll">
      <div v-for="r in items" :key="r.id" class="item">
        <div class="row" style="justify-content: space-between; margin-bottom: 6px">
          <div style="font-weight: 600">#{{ r.id }}</div>
          <div class="muted" style="font-size: 12px">{{ formatTime(r.createdAt) }}</div>
        </div>
        <div class="reqRow">
          <div class="avatar" aria-hidden="true"></div>
          <div class="main">
            <div class="top">
              <div class="name">{{ displayUser(r.fromUserId) }} → {{ displayUser(r.toUserId) }}</div>
              <div class="muted" style="font-size: 12px">status={{ r.status }}</div>
            </div>
            <div class="preview">{{ r.content ?? '' }}</div>
          </div>
        </div>

        <div v-if="box === 'inbox' && r.status === 'PENDING'" class="row" style="margin-top: 10px; justify-content: flex-end">
          <button class="btn danger" @click="decide(r.id, 'reject')">拒绝</button>
          <button class="btn primary" @click="decide(r.id, 'accept')">同意</button>
        </div>
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
  height: calc(100vh - 290px);
  overflow: auto;
  display: grid;
  gap: 10px;
  padding: 6px;
}
.item {
  padding: 12px;
  border-radius: 12px;
  border: 1px solid var(--border);
  background: var(--panel);
  transition: transform 120ms ease, box-shadow 120ms ease;
}
.item:hover {
  transform: translateY(-1px);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.08);
}
.reqRow {
  display: grid;
  grid-template-columns: 36px 1fr;
  gap: 12px;
  align-items: start;
}
.avatar {
  width: 36px;
  height: 36px;
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
  margin-bottom: 6px;
}
.name {
  font-weight: 650;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  color: var(--text);
}
</style>
