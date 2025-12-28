<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { apiGet } from '../services/api'
import type { MessageEntity, SingleChatMemberStateDto } from '../types/api'
import type { WsEnvelope } from '../types/ws'
import { useAuthStore } from '../stores/auth'
import { useWsStore } from '../stores/ws'
import { useUserStore } from '../stores/users'
import { formatTime } from '../utils/format'

type UiMessage = {
  clientMsgId: string
  serverMsgId?: string
  fromUserId: string
  toUserId: string
  content: string
  ts: number
  status: 'sending' | 'sent' | 'read' | 'received'
}

const route = useRoute()
const auth = useAuthStore()
const ws = useWsStore()
const users = useUserStore()

const peerUserId = computed(() => String(route.params.peerUserId ?? ''))
const peerName = computed(() => users.displayName(peerUserId.value))
const items = ref<UiMessage[]>([])
const loading = ref(false)
const done = ref(false)
const lastId = ref<string | null>(null)

const draft = ref('')
const errorMsg = ref<string | null>(null)

const wsCursor = ref(0)
const chatEl = ref<HTMLElement | null>(null)
const stickToBottom = ref(true)

let peerReadUpTo: bigint = 0n
let lastSentReadAck: bigint = 0n

function uuid() {
  return crypto.randomUUID()
}

function toBigIntOrNull(id?: string | null) {
  if (!id) return null
  try {
    return BigInt(id)
  } catch {
    return null
  }
}

function markOutgoingRead(upTo: bigint) {
  peerReadUpTo = upTo
  for (const m of items.value) {
    if (m.fromUserId !== auth.userId) continue
    const sid = toBigIntOrNull(m.serverMsgId)
    if (sid == null) continue
    if (sid <= upTo) {
      m.status = 'read'
    }
  }
}

function sendAckDelivered(serverMsgId: string, toUserId: string) {
  try {
    ws.send({
      type: 'ACK',
      ackType: 'delivered',
      clientMsgId: `ackd-${uuid()}`,
      serverMsgId,
      to: toUserId,
    })
  } catch {
    // ignore
  }
}

function sendAckRead(serverMsgId: string, toUserId: string) {
  const sid = toBigIntOrNull(serverMsgId)
  if (sid == null) return
  if (sid <= lastSentReadAck) return
  lastSentReadAck = sid
  try {
    ws.send({
      type: 'ACK',
      ackType: 'read',
      clientMsgId: `ackr-${uuid()}`,
      serverMsgId,
      to: toUserId,
    })
  } catch {
    // ignore
  }
}

function readUpToLatestVisible() {
  if (!auth.userId) return
  if (!peerUserId.value) return
  if (!isNearBottom()) return

  let max: bigint | null = null
  for (const m of items.value) {
    if (m.fromUserId !== peerUserId.value) continue
    const sid = toBigIntOrNull(m.serverMsgId)
    if (sid == null) continue
    if (max == null || sid > max) max = sid
  }
  if (max != null) {
    sendAckRead(max.toString(), peerUserId.value)
  }
}

function isNearBottom(thresholdPx = 120) {
  const el = chatEl.value
  if (!el) return true
  const remaining = el.scrollHeight - el.scrollTop - el.clientHeight
  return remaining <= thresholdPx
}

function scrollToBottom() {
  const el = chatEl.value
  if (!el) return
  el.scrollTop = el.scrollHeight
}

function resetAndLoad() {
  items.value = []
  lastId.value = null
  done.value = false
  void loadMore()
}

async function loadMore() {
  if (loading.value || done.value) return
  loading.value = true
  errorMsg.value = null

  const el = chatEl.value
  const preserveScroll = !!el && items.value.length > 0
  const prevScrollHeight = preserveScroll ? el.scrollHeight : 0
  const prevScrollTop = preserveScroll ? el.scrollTop : 0

  try {
    const qs = new URLSearchParams()
    qs.set('peerUserId', peerUserId.value)
    qs.set('limit', '20')
    if (lastId.value != null) {
      qs.set('lastId', lastId.value)
    }
    const data = await apiGet<MessageEntity[]>(`/single-chat/message/cursor?${qs.toString()}`)
    if (!data.length) {
      done.value = true
      return
    }
    lastId.value = data.length ? data[data.length - 1]!.id : null

    const mapped = data
      .map((m) => ({
        clientMsgId: m.clientMsgId ?? uuid(),
        serverMsgId: m.serverMsgId,
        fromUserId: m.fromUserId,
        toUserId: m.toUserId ?? peerUserId.value,
        content: m.content,
        ts: new Date(m.createdAt).getTime(),
        status: 'sent' as const,
      }))
      .reverse()

    const firstLoad = items.value.length === 0
    if (firstLoad) {
      items.value = mapped
    } else {
      items.value.unshift(...mapped)
    }

    await nextTick()
    if (firstLoad) {
      stickToBottom.value = true
      scrollToBottom()
      if (document.visibilityState === 'visible') {
        readUpToLatestVisible()
      }
    } else if (preserveScroll && el) {
      const delta = el.scrollHeight - prevScrollHeight
      el.scrollTop = prevScrollTop + delta
    }
  } catch (e) {
    errorMsg.value = String(e)
  } finally {
    loading.value = false
  }
}

function onScroll(e: Event) {
  const el = e.target as HTMLElement
  stickToBottom.value = isNearBottom()
  if (stickToBottom.value && document.visibilityState === 'visible') {
    readUpToLatestVisible()
  }
  if (el.scrollTop <= 60) {
    void loadMore()
  }
}

function applyWsEvent(ev: WsEnvelope) {
  if (ev.type === 'ACK' && (ev.ackType?.toUpperCase() === 'SAVED' || ev.ackType === 'saved')) {
    if (!ev.clientMsgId) return
    const target = items.value.find((m) => m.clientMsgId === ev.clientMsgId)
    if (!target) return
    target.status = 'sent'
    if (ev.serverMsgId) target.serverMsgId = ev.serverMsgId
    if (target.serverMsgId) {
      const sid = toBigIntOrNull(target.serverMsgId)
      if (sid != null && sid <= peerReadUpTo) {
        target.status = 'read'
      }
    }
    return
  }
  if (ev.type === 'ACK' && ev.ackType?.toLowerCase() === 'read') {
    if (!ev.serverMsgId) return
    if (ev.from !== peerUserId.value) return
    const sid = toBigIntOrNull(ev.serverMsgId)
    if (sid == null) return
    markOutgoingRead(sid)
    return
  }
  if (ev.type === 'ERROR' && ev.clientMsgId) {
    const target = items.value.find((m) => m.clientMsgId === ev.clientMsgId)
    if (target) {
      errorMsg.value = `发送失败: ${ev.reason ?? 'error'}`
    }
    return
  }

  if (ev.type === 'SINGLE_CHAT') {
    if (!ev.from || !ev.to) return
    if (ev.from !== peerUserId.value || ev.to !== auth.userId) return
    const shouldStick = stickToBottom.value || isNearBottom()
    const msg: UiMessage = {
      clientMsgId: ev.clientMsgId ?? uuid(),
      serverMsgId: ev.serverMsgId ?? undefined,
      fromUserId: ev.from,
      toUserId: ev.to,
      content: ev.body ?? '',
      ts: ev.ts ?? Date.now(),
      status: 'received',
    }
    items.value.push(msg)
    void nextTick(() => {
      if (shouldStick) {
        stickToBottom.value = true
        scrollToBottom()
      }
      if (document.visibilityState === 'visible') {
        readUpToLatestVisible()
      }
    })

    if (ev.serverMsgId) {
      sendAckDelivered(ev.serverMsgId, ev.from)
      if (shouldStick && document.visibilityState === 'visible') {
        sendAckRead(ev.serverMsgId, ev.from)
      }
    }
  }
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

async function refreshMemberState() {
  try {
    const st = await apiGet<SingleChatMemberStateDto>(
      `/single-chat/member/state?peerUserId=${encodeURIComponent(peerUserId.value)}`,
    )
    const peerLastRead = toBigIntOrNull(st.peerLastReadMsgId ?? null)
    if (peerLastRead != null) {
      markOutgoingRead(peerLastRead)
    }
  } catch {
    // ignore
  }
}

async function send() {
  errorMsg.value = null
  const content = draft.value.trim()
  if (!content) return
  draft.value = ''

  const clientMsgId = `m-${uuid()}`
  const now = Date.now()
  items.value.push({
    clientMsgId,
    fromUserId: auth.userId!,
    toUserId: peerUserId.value,
    content,
    ts: now,
    status: 'sending',
  })
  stickToBottom.value = true
  void nextTick(() => scrollToBottom())

  try {
    await ws.connect()
    ws.send({
      type: 'SINGLE_CHAT',
      clientMsgId,
      to: peerUserId.value,
      body: content,
      msgType: 'TEXT',
      ts: now,
    })
  } catch (e) {
    errorMsg.value = String(e)
  }
}

watch(
  () => peerUserId.value,
  () => {
    wsCursor.value = ws.events.length
    peerReadUpTo = 0n
    lastSentReadAck = 0n
    resetAndLoad()
    void users.ensureBasics([peerUserId.value])
    void refreshMemberState()
  },
)

function onFocus() {
  readUpToLatestVisible()
}

function onVisibilityChange() {
  if (document.visibilityState === 'visible') {
    readUpToLatestVisible()
  }
}

onMounted(() => {
  wsCursor.value = ws.events.length
  void ws.connect()
  void loadMore()
  void nextTick(() => scrollToBottom())
  void users.ensureBasics([peerUserId.value])
  void refreshMemberState()
  window.addEventListener('focus', onFocus)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  window.removeEventListener('focus', onFocus)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <div class="card" style="padding: 14px">
    <div class="row" style="justify-content: space-between; margin-bottom: 10px">
      <h2 style="margin: 0">聊天 {{ peerName }}</h2>
      <button class="btn" @click="resetAndLoad">刷新</button>
    </div>
    <div class="muted" style="margin-bottom: 10px">上滑加载历史；发送以 `ACK(saved)` 作为“已发送”。</div>

    <div ref="chatEl" class="chat" @scroll="onScroll">
      <div v-if="loading" class="muted" style="text-align: center; padding: 8px">加载中…</div>
      <div v-if="done && !loading" class="muted" style="text-align: center; padding: 8px">没有更多历史</div>

      <div v-for="m in items" :key="m.clientMsgId" class="msgRow" :class="{ me: m.fromUserId === auth.userId }">
        <div class="bubble" :class="{ me: m.fromUserId === auth.userId }">
          <div class="meta">
            <span class="muted">{{ m.fromUserId === auth.userId ? '我' : peerName }}</span>
            <span class="muted">{{ formatTime(m.ts) }}</span>
            <span v-if="m.fromUserId === auth.userId" class="status" :class="m.status">
              {{ m.status === 'sending' ? '发送中…' : m.status === 'read' ? '已读' : '未读' }}
            </span>
          </div>
          <div class="content">{{ m.content }}</div>
        </div>
      </div>
    </div>

    <div class="row" style="margin-top: 12px">
      <input v-model="draft" class="input" placeholder="输入消息…" @keydown.enter="send" />
      <button class="btn primary" @click="send">发送</button>
    </div>
    <div v-if="errorMsg" class="muted" style="color: var(--danger); margin-top: 10px">{{ errorMsg }}</div>
  </div>
</template>

<style scoped>
.chat {
  height: calc(100vh - 240px);
  overflow: auto;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
}
.msgRow {
  display: flex;
  justify-content: flex-start;
}
.msgRow.me {
  justify-content: flex-end;
}
.bubble {
  display: inline-block;
  width: fit-content;
  max-width: min(72%, 560px);
  padding: 10px 12px;
  border-radius: 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: #ffffff;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.06);
  position: relative;
}
.bubble.me {
  border-color: rgba(7, 193, 96, 0.55);
  background: var(--primary);
  color: #ffffff;
}
.bubble::after {
  content: '';
  position: absolute;
  top: 12px;
  left: -6px;
  width: 10px;
  height: 10px;
  background: #ffffff;
  border-left: 1px solid rgba(15, 23, 42, 0.06);
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
  transform: rotate(45deg);
}
.bubble.me::after {
  left: auto;
  right: -6px;
  background: var(--primary);
  border-left: 0;
  border-bottom: 0;
  border-right: 1px solid rgba(7, 193, 96, 0.45);
  border-top: 1px solid rgba(7, 193, 96, 0.45);
}
.bubble.me .muted {
  color: rgba(255, 255, 255, 0.86);
}
.meta {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 12px;
  margin-bottom: 4px;
}
.bubble.me .meta {
  justify-content: flex-end;
}
.content {
  white-space: pre-wrap;
  word-break: break-word;
}
.status.sending {
  color: var(--muted);
}
.status.sent {
  color: var(--muted);
}
.status.read {
  color: var(--ok);
}
.bubble.me .status {
  color: rgba(255, 255, 255, 0.9);
}
</style>
