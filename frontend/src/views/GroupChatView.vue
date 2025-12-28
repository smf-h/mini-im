<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiGet } from '../services/api'
import type { MessageEntity } from '../types/api'
import { formatTime } from '../utils/format'
import { useAuthStore } from '../stores/auth'
import { useWsStore } from '../stores/ws'
import { useUserStore } from '../stores/users'
import { useGroupStore } from '../stores/groups'
import type { WsEnvelope } from '../types/ws'
import UiAvatar from '../components/UiAvatar.vue'

type UiMessage = {
  clientMsgId: string
  serverMsgId?: string
  fromUserId: string
  content: string
  ts: number
  status: 'sending' | 'sent' | 'received'
  important?: boolean
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ws = useWsStore()
const users = useUserStore()
const groups = useGroupStore()

const groupId = computed(() => String(route.params.groupId ?? ''))
const groupName = computed(() => groups.displayName(groupId.value))

const items = ref<UiMessage[]>([])
const loading = ref(false)
const errorMsg = ref<string | null>(null)
const done = ref(false)
const lastId = ref<string | null>(null)
const wsCursor = ref(0)

const draft = ref('')
const replyTo = ref<UiMessage | null>(null)
const replyPreview = computed(() => {
  const m = replyTo.value
  if (!m) return ''
  const who = m.fromUserId === auth.userId ? '我' : users.displayName(m.fromUserId)
  const text = m.content.replace(/\s+/g, ' ').trim()
  const brief = text.length > 60 ? `${text.slice(0, 60)}…` : text
  return `${who}: ${brief}`
})
const chatEl = ref<HTMLElement | null>(null)
const stickToBottom = ref(true)

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

function sendAckDelivered(serverMsgId: string, toUserId: string) {
  try {
    ws.send({
      type: 'ACK',
      ackType: 'delivered',
      clientMsgId: `gackd-${uuid()}`,
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
      clientMsgId: `gackr-${uuid()}`,
      serverMsgId,
      to: toUserId,
    })
  } catch {
    // ignore
  }
}

function readUpToLatestVisible() {
  if (!auth.userId) return
  if (!isNearBottom()) return

  let max: { sid: bigint; fromUserId: string } | null = null
  for (const m of items.value) {
    if (m.fromUserId === auth.userId) continue
    const sid = toBigIntOrNull(m.serverMsgId)
    if (sid == null) continue
    if (max == null || sid > max.sid) max = { sid, fromUserId: m.fromUserId }
  }
  if (max) {
    sendAckRead(max.sid.toString(), max.fromUserId)
  }
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
    qs.set('groupId', groupId.value)
    qs.set('limit', '20')
    if (lastId.value != null) {
      qs.set('lastId', lastId.value)
    }
    const data = await apiGet<MessageEntity[]>(`/group/message/cursor?${qs.toString()}`)
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

    const ids = Array.from(new Set(mapped.map((x) => x.fromUserId)))
    void users.ensureBasics(ids)

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

function parseMentions(text: string) {
  const out: string[] = []
  const re = /@([1-9][0-9]*)/g
  for (;;) {
    const m = re.exec(text)
    if (!m) break
    out.push(m[1]!)
  }
  return Array.from(new Set(out))
}

function startReply(m: UiMessage) {
  if (!m.serverMsgId) return
  replyTo.value = m
}

function cancelReply() {
  replyTo.value = null
}

function openUser(id: string) {
  void router.push(`/u/${id}`)
}

async function send() {
  errorMsg.value = null
  const content = draft.value.trim()
  if (!content) return
  const replyToServerMsgId = replyTo.value?.serverMsgId ?? null
  draft.value = ''
  replyTo.value = null

  const clientMsgId = `gm-${uuid()}`
  const now = Date.now()
  items.value.push({
    clientMsgId,
    fromUserId: auth.userId!,
    content,
    ts: now,
    status: 'sending',
  })
  stickToBottom.value = true
  void nextTick(() => scrollToBottom())

  const mentions = parseMentions(content)

  try {
    await ws.connect()
    ws.send({
      type: 'GROUP_CHAT',
      clientMsgId,
      groupId: groupId.value,
      body: content,
      msgType: 'TEXT',
      ts: now,
      mentions,
      replyToServerMsgId,
    })
  } catch (e) {
    errorMsg.value = String(e)
  }
}

function applyWsEvent(ev: WsEnvelope) {
  if (ev.type === 'ACK' && (ev.ackType?.toUpperCase() === 'SAVED' || ev.ackType === 'saved')) {
    if (!ev.clientMsgId) return
    const target = items.value.find((m) => m.clientMsgId === ev.clientMsgId)
    if (!target) return
    target.status = 'sent'
    if (ev.serverMsgId) target.serverMsgId = ev.serverMsgId
    return
  }

  if (ev.type === 'ERROR' && ev.clientMsgId) {
    const target = items.value.find((m) => m.clientMsgId === ev.clientMsgId)
    if (target) {
      errorMsg.value = `发送失败: ${ev.reason ?? 'error'}`
    }
    return
  }

  if (ev.type === 'GROUP_CHAT') {
    if (!ev.groupId || ev.groupId !== groupId.value) return
    if (!ev.from) return

    const shouldStick = stickToBottom.value || isNearBottom()
    const msg: UiMessage = {
      clientMsgId: ev.clientMsgId ?? uuid(),
      serverMsgId: ev.serverMsgId ?? undefined,
      fromUserId: ev.from,
      content: ev.body ?? '',
      ts: ev.ts ?? Date.now(),
      status: 'received',
      important: !!ev.important,
    }
    items.value.push(msg)
    void users.ensureBasics([ev.from])

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
  void groups.ensureBasics([groupId.value])
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
      <h2 style="margin: 0">群聊 {{ groupName }}</h2>
      <div class="row">
        <button class="btn" @click="router.push(`/group/${groupId}/profile`)">群资料</button>
        <button class="btn" @click="resetAndLoad">刷新</button>
      </div>
    </div>
    <div class="muted" style="margin-bottom: 10px">上滑加载历史；发送以 `ACK(saved)` 作为“已发送”。</div>

    <div ref="chatEl" class="chat" @scroll="onScroll">
      <div v-if="loading" class="muted" style="text-align: center; padding: 8px">加载中…</div>
      <div v-if="done && !loading" class="muted" style="text-align: center; padding: 8px">没有更多历史</div>

      <div v-for="m in items" :key="m.clientMsgId" class="msgRow" :class="{ me: m.fromUserId === auth.userId }">
        <button
          v-if="m.fromUserId !== auth.userId"
          class="avatarBtn"
          type="button"
          @click="openUser(m.fromUserId)"
        >
          <UiAvatar :text="users.displayName(m.fromUserId)" :seed="m.fromUserId" :size="36" />
        </button>

        <div class="bubble" :class="{ me: m.fromUserId === auth.userId, important: m.important }">
          <div class="meta">
            <span class="muted">{{ m.fromUserId === auth.userId ? '我' : users.displayName(m.fromUserId) }}</span>
            <span class="muted">{{ formatTime(new Date(m.ts).toISOString()) }}</span>
            <span v-if="m.important && m.fromUserId !== auth.userId" class="tag">@我</span>
            <button v-if="m.serverMsgId" class="miniBtn" @click.stop="startReply(m)">回复</button>
            <span v-if="m.fromUserId === auth.userId" class="status" :class="m.status">
              {{ m.status === 'sending' ? '发送中…' : '已发送' }}
            </span>
          </div>
          <div class="content">{{ m.content }}</div>
        </div>

        <button v-if="m.fromUserId === auth.userId" class="avatarBtn me" type="button" @click="openUser(m.fromUserId)">
          <UiAvatar :text="'我'" :seed="m.fromUserId" :size="36" />
        </button>
      </div>
    </div>

    <div v-if="replyTo" class="replyBar">
      <div class="replyText">回复 {{ replyPreview }}</div>
      <button class="miniBtn" @click="cancelReply">取消</button>
    </div>

    <div class="row" style="margin-top: 12px">
      <input v-model="draft" class="input" placeholder="输入消息…（@123 或回复 可触发重要提醒）" @keydown.enter="send" />
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
  max-width: min(560px, 78%);
  padding: 10px 12px;
  border-radius: 14px;
  border: 1px solid rgba(15, 23, 42, 0.1);
  background: #ffffff;
  box-shadow: 0 6px 16px rgba(15, 23, 42, 0.06);
}
.bubble.me {
  background: rgba(7, 193, 96, 0.18);
  border-color: rgba(7, 193, 96, 0.24);
}
.bubble.important:not(.me) {
  border-color: rgba(59, 130, 246, 0.32);
  box-shadow: 0 8px 20px rgba(59, 130, 246, 0.12);
}
.meta {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  margin-bottom: 6px;
}
.content {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.4;
}
.status {
  margin-left: auto;
  font-size: 12px;
  color: rgba(15, 23, 42, 0.72);
}
.tag {
  padding: 0 8px;
  height: 18px;
  border-radius: 999px;
  background: rgba(59, 130, 246, 0.18);
  border: 1px solid rgba(59, 130, 246, 0.24);
  color: rgba(30, 64, 175, 0.9);
  line-height: 18px;
}
.miniBtn {
  border: 1px solid rgba(15, 23, 42, 0.12);
  background: rgba(255, 255, 255, 0.9);
  padding: 0 10px;
  height: 22px;
  border-radius: 999px;
  color: rgba(15, 23, 42, 0.72);
  cursor: pointer;
}
.miniBtn:hover {
  background: rgba(15, 23, 42, 0.04);
}
.replyBar {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--border);
  background: rgba(15, 23, 42, 0.03);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.replyText {
  color: rgba(15, 23, 42, 0.78);
  font-size: 13px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.avatarBtn {
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
  align-self: flex-end;
}
.avatarBtn.me {
  margin-left: 10px;
}
</style>
