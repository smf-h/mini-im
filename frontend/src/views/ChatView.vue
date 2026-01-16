<script setup lang="ts">
// ChatView：单聊窗口（仿微信），包含消息流、已读状态与通话相关入口。
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiGet } from '../services/api'
import type { CallRecordDto, MessageEntity, SingleChatMemberStateDto } from '../types/api'
import type { WsEnvelope } from '../types/ws'
import { useAuthStore } from '../stores/auth'
import { useWsStore } from '../stores/ws'
import { useUserStore } from '../stores/users'
import { useDndStore } from '../stores/dnd'
import { useCallStore } from '../stores/call'
import { formatTime } from '../utils/format'
import UiAvatar from '../components/UiAvatar.vue'

type UiMessage = {
  clientMsgId: string
  serverMsgId?: string
  fromUserId: string
  toUserId: string
  content: string
  ts: number
  status: 'sending' | 'sent' | 'read' | 'received'
  revoked?: boolean
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ws = useWsStore()
const users = useUserStore()
const dnd = useDndStore()
const call = useCallStore()

const peerUserId = computed(() => String(route.params.peerUserId ?? ''))
const peerName = computed(() => users.displayName(peerUserId.value))
const dmMuted = computed(() => dnd.isDmMuted(peerUserId.value))
const items = ref<UiMessage[]>([])
const loading = ref(false)
const done = ref(false)
const lastId = ref<string | null>(null)

const draft = ref('')
const errorMsg = ref<string | null>(null)
const revokeClientMsgIds = new Set<string>()

const wsCursor = ref(0)
const chatEl = ref<HTMLElement | null>(null)
const stickToBottom = ref(true)

const showCallHistory = ref(false)
const callLoading = ref(false)
const callError = ref<string | null>(null)
const callRecords = ref<CallRecordDto[]>([])

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

function openUser(id: string) {
  void router.push(`/contacts/u/${id}`)
}

async function toggleDmMute() {
  if (!peerUserId.value) return
  try {
    await dnd.toggleDm(peerUserId.value)
  } catch (e) {
    errorMsg.value = `设置免打扰失败: ${String(e)}`
  }
}

async function startVideoCall() {
  if (!peerUserId.value) return
  if (call.busy) {
    errorMsg.value = '当前已有进行中的通话'
    return
  }
  try {
    await call.startVideoCall(peerUserId.value)
  } catch (e) {
    errorMsg.value = `发起通话失败: ${String(e)}`
  }
}

function callStatusText(s: CallRecordDto['status']) {
  switch (s) {
    case 'RINGING':
      return '响铃中'
    case 'ACCEPTED':
      return '已接听'
    case 'REJECTED':
      return '已拒绝'
    case 'CANCELED':
      return '已取消'
    case 'ENDED':
      return '已结束'
    case 'MISSED':
      return '未接听'
    case 'FAILED':
      return '失败'
    default:
      return String(s)
  }
}

function callDirectionText(d: CallRecordDto['direction']) {
  return d === 'OUT' ? '呼出' : '呼入'
}

function durationText(sec?: number | null) {
  if (sec == null) return ''
  const s = Math.max(0, Math.floor(sec))
  const mm = Math.floor(s / 60)
  const ss = s % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${mm}:${pad(ss)}`
}

async function loadCallRecords() {
  if (!peerUserId.value) return
  if (!auth.userId) return
  callLoading.value = true
  callError.value = null
  try {
    const qs = new URLSearchParams()
    qs.set('limit', '50')
    const data = await apiGet<CallRecordDto[]>(`/call/record/cursor?${qs.toString()}`)
    callRecords.value = (data ?? []).filter((r) => String(r.peerUserId ?? '') === peerUserId.value)
  } catch (e) {
    callError.value = String(e)
  } finally {
    callLoading.value = false
  }
}

async function openCallHistory() {
  showCallHistory.value = true
  await loadCallRecords()
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
        revoked: m.status === 4,
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
    if (ev.body) target.content = ev.body
    if (target.serverMsgId) {
      const sid = toBigIntOrNull(target.serverMsgId)
      if (sid != null && sid <= peerReadUpTo) {
        target.status = 'read'
      }
    }
    return
  }
  if (ev.type === 'ACK' && ev.ackType?.toLowerCase() === 'revoked') {
    if (ev.clientMsgId) revokeClientMsgIds.delete(ev.clientMsgId)
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
      return
    }
    if (revokeClientMsgIds.has(ev.clientMsgId)) {
      revokeClientMsgIds.delete(ev.clientMsgId)
      errorMsg.value = `撤回失败: ${ev.reason ?? 'error'}`
    }
    return
  }

  if (ev.type === 'MESSAGE_REVOKED') {
    if (!ev.serverMsgId) return
    const myId = auth.userId
    if (!myId) return
    const ok =
      (ev.from === myId && ev.to === peerUserId.value) || (ev.from === peerUserId.value && ev.to === myId)
    if (!ok) return
    const target = items.value.find((m) => m.serverMsgId === ev.serverMsgId)
    if (!target) return
    target.content = '已撤回'
    target.revoked = true
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
      revoked: ev.body === '已撤回',
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
    revoked: false,
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

function canRevoke(m: UiMessage) {
  if (m.fromUserId !== auth.userId) return false
  if (!m.serverMsgId) return false
  if (m.revoked) return false
  return Date.now() - m.ts <= 2 * 60 * 1000
}

async function revokeMessage(m: UiMessage) {
  if (!m.serverMsgId) return
  errorMsg.value = null
  const clientMsgId = `revoke-${uuid()}`
  revokeClientMsgIds.add(clientMsgId)
  try {
    await ws.connect()
    ws.send({
      type: 'MESSAGE_REVOKE',
      clientMsgId,
      serverMsgId: m.serverMsgId,
      ts: Date.now(),
    })
  } catch (e) {
    revokeClientMsgIds.delete(clientMsgId)
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
  void dnd.hydrate()
  void refreshMemberState()
  void loadCallRecords()
  window.addEventListener('focus', onFocus)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  window.removeEventListener('focus', onFocus)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})

watch(
  () => call.phase,
  () => {
    if (!peerUserId.value) return
    if (String(call.peerUserId ?? '') !== peerUserId.value) return
    if (call.phase !== 'ended') return
    window.setTimeout(() => void loadCallRecords(), 800)
  },
)
</script>

<template>
  <div class="chatStage">
    <header class="chatHeader">
      <div class="headerMain">
        <div class="title">{{ peerName || peerUserId }}</div>
      </div>
      <div class="headerActions">
        <button class="iconBtn" :disabled="call.busy" title="视频通话" aria-label="视频通话" @click="startVideoCall">
          <svg class="iconSvg" viewBox="0 0 24 24" aria-hidden="true">
            <path
              fill="currentColor"
              d="M15 8a3 3 0 0 1 3 3v2.2l2.4 1.6a1 1 0 0 1 .6.9v-7a1 1 0 0 0-1.6-.8L18 9.4V11a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3h9Zm0 2H6a1 1 0 0 0-1 1v2a1 1 0 0 0 1 1h9a1 1 0 0 0 1-1V11a1 1 0 0 0-1-1Z"
            />
          </svg>
        </button>
        <button class="iconBtn" title="通话记录" aria-label="通话记录" @click="openCallHistory">
          <svg class="iconSvg" viewBox="0 0 24 24" aria-hidden="true">
            <path
              fill="currentColor"
              d="M12 4a8 8 0 1 1 0 16a8 8 0 0 1 0-16Zm0 2a6 6 0 1 0 0 12a6 6 0 0 0 0-12Zm1 2v4.2l2.7 1.6a1 1 0 0 1-1 1.7L11 13V8a1 1 0 1 1 2 0Z"
            />
          </svg>
        </button>
        <button class="iconBtn" :data-active="dmMuted" title="免打扰" aria-label="免打扰" @click="toggleDmMute">
          <svg class="iconSvg" viewBox="0 0 24 24" aria-hidden="true">
            <path
              fill="currentColor"
              d="M12 22a2 2 0 0 0 2-2h-4a2 2 0 0 0 2 2Zm6-6V11a6 6 0 0 0-4-5.7V4a2 2 0 1 0-4 0v1.3A6 6 0 0 0 6 11v5l-2 2v1h16v-1l-2-2Zm-2 1H8v-6a4 4 0 0 1 8 0v6Z"
            />
            <path
              v-if="dmMuted"
              fill="currentColor"
              d="M4.3 3.3a1 1 0 0 1 1.4 0l16 16a1 1 0 1 1-1.4 1.4l-16-16a1 1 0 0 1 0-1.4Z"
            />
          </svg>
        </button>
        <button class="iconBtn" title="对方主页" aria-label="对方主页" @click="openUser(peerUserId)">
          <svg class="iconSvg" viewBox="0 0 24 24" aria-hidden="true">
            <path
              fill="currentColor"
              d="M12 12a4 4 0 1 0 0-8a4 4 0 0 0 0 8Zm0 2c-4.4 0-8 2-8 4.5V20h16v-1.5c0-2.5-3.6-4.5-8-4.5Z"
            />
          </svg>
        </button>
        <button class="iconBtn" title="刷新" aria-label="刷新" @click="resetAndLoad">
          <svg class="iconSvg" viewBox="0 0 24 24" aria-hidden="true">
            <path
              fill="currentColor"
              d="M17.65 6.35A7.95 7.95 0 0 0 12 4V1L7 6l5 5V7a5 5 0 1 1-5 5H5a7 7 0 1 0 12.65-5.65Z"
            />
          </svg>
        </button>
      </div>
    </header>

    <div ref="chatEl" class="chatBody" @scroll="onScroll">
      <div v-if="loading" class="muted" style="text-align: center; padding: 8px">加载中…</div>
      <div v-if="done && !loading" class="muted" style="text-align: center; padding: 8px">没有更多历史</div>

      <div v-for="m in items" :key="m.clientMsgId" class="msgRow" :class="{ me: m.fromUserId === auth.userId }">
        <button
          v-if="m.fromUserId !== auth.userId"
          class="avatarBtn"
          type="button"
          @click="openUser(peerUserId)"
        >
          <UiAvatar :text="peerName" :seed="peerUserId" :size="36" />
        </button>

        <div class="bubble" :class="{ me: m.fromUserId === auth.userId }">
          <div class="meta">
            <span class="muted">{{ m.fromUserId === auth.userId ? '我' : peerName }}</span>
            <span class="muted">{{ formatTime(m.ts) }}</span>
            <button v-if="canRevoke(m)" class="miniBtn danger" @click.stop="revokeMessage(m)">撤回</button>
            <span v-if="m.fromUserId === auth.userId" class="status" :class="m.status">
              {{ m.status === 'sending' ? '发送中…' : m.status === 'read' ? '已读' : '未读' }}
            </span>
          </div>
          <div class="content">{{ m.content }}</div>
        </div>

        <button v-if="m.fromUserId === auth.userId" class="avatarBtn me" type="button" @click="openUser(auth.userId!)">
          <UiAvatar text="我" :seed="auth.userId" :size="36" />
        </button>
      </div>
    </div>

    <footer class="chatFooter">
      <input v-model="draft" class="input" placeholder="输入消息…" @keydown.enter="send" />
      <button class="btn primary" @click="send">发送</button>
    </footer>

    <div v-if="errorMsg" class="error">{{ errorMsg }}</div>

    <div v-if="showCallHistory" class="modalMask" @click.self="showCallHistory = false">
      <div class="modalCard" role="dialog" aria-modal="true" aria-label="通话记录">
        <div class="modalTop">
          <div>
            <div class="modalTitle">通话记录</div>
            <div class="muted" style="font-size: 12px; margin-top: 4px">仅展示与当前好友的最近记录。</div>
          </div>
          <button class="x" type="button" aria-label="关闭" @click="showCallHistory = false">×</button>
        </div>

        <div class="modalBody">
          <div v-if="callLoading" class="tail muted">加载中…</div>
          <div v-else-if="callError" class="tail error">{{ callError }}</div>
          <div v-else-if="callRecords.length === 0" class="tail muted">暂无通话记录</div>

          <div v-else class="callList">
            <div v-for="r in callRecords" :key="r.id" class="callRow">
              <div class="callLeft">
                <div class="callMain">
                  <span class="pill">{{ callDirectionText(r.direction) }}</span>
                  <span class="callStatus">{{ callStatusText(r.status) }}</span>
                  <span v-if="r.durationSeconds != null" class="muted">时长 {{ durationText(r.durationSeconds) }}</span>
                </div>
                <div class="callSub muted">
                  <span v-if="r.startedAt">{{ formatTime(r.startedAt) }}</span>
                  <span v-if="r.failReason"> · {{ r.failReason }}</span>
                </div>
              </div>
              <div class="callRight muted">callId={{ r.callId }}</div>
            </div>
          </div>
        </div>

        <div class="modalActions">
          <button class="btn" type="button" @click="loadCallRecords">刷新</button>
          <button class="btn primary" type="button" @click="showCallHistory = false">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chatStage {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg);
}
.chatHeader {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  background: var(--surface);
  border-bottom: 1px solid var(--divider);
}
.headerActions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
.headerMain {
  min-width: 0;
}
.title {
  font-weight: 900;
  font-size: 16px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sub {
  margin-top: 2px;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chatBody {
  flex: 1;
  overflow: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: var(--bg-soft);
  border-top: 1px solid rgba(0, 0, 0, 0.03);
}
.msgRow {
  display: flex;
  justify-content: flex-start;
  align-items: flex-start;
}
.msgRow.me {
  justify-content: flex-end;
}
.avatarBtn {
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
  flex-shrink: 0;
  margin-top: 2px;
}
.msgRow:not(.me) .avatarBtn {
  margin-right: 10px;
}
.avatarBtn.me {
  margin-left: 10px;
}
.bubble {
  display: inline-block;
  width: fit-content;
  max-width: min(68%, 480px);
  padding: 8px 12px 10px;
  border-radius: 12px;
  border: 1px solid rgba(15, 23, 42, 0.06);
  background: #ffffff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08);
  position: relative;
}
.bubble.me {
  border-color: rgba(7, 193, 96, 0.3);
  background: #07c160;
  color: #ffffff;
}
.bubble::after {
  content: '';
  position: absolute;
  top: 12px;
  left: -5px;
  width: 8px;
  height: 8px;
  background: #ffffff;
  border-left: 1px solid rgba(15, 23, 42, 0.05);
  border-bottom: 1px solid rgba(15, 23, 42, 0.05);
  transform: rotate(45deg);
}
.bubble.me::after {
  left: auto;
  right: -5px;
  background: #07c160;
  border-left: 0;
  border-bottom: 0;
  border-right: 1px solid rgba(7, 193, 96, 0.3);
  border-top: 1px solid rgba(7, 193, 96, 0.3);
}
.bubble.me .muted {
  color: rgba(255, 255, 255, 0.75);
}
.bubble:not(.me) .muted {
  color: rgba(15, 23, 42, 0.5);
}
.meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
  margin-bottom: 6px;
  gap: 8px;
}
.bubble.me .meta {
  flex-direction: row-reverse;
}
.miniBtn {
  border: 1px solid rgba(15, 23, 42, 0.1);
  background: rgba(255, 255, 255, 0.9);
  padding: 2px 10px;
  height: 20px;
  border-radius: 999px;
  font-size: 11px;
  color: rgba(15, 23, 42, 0.65);
  cursor: pointer;
}
.miniBtn:hover {
  background: rgba(15, 23, 42, 0.05);
  border-color: rgba(15, 23, 42, 0.15);
}
.miniBtn.danger {
  border-color: rgba(239, 68, 68, 0.25);
  color: rgba(220, 38, 38, 0.95);
}
.bubble.me .miniBtn {
  border-color: rgba(255, 255, 255, 0.3);
  background: rgba(255, 255, 255, 0.2);
  color: rgba(255, 255, 255, 0.95);
}
.bubble.me .miniBtn:hover {
  background: rgba(255, 255, 255, 0.3);
}
.bubble.me .miniBtn.danger {
  border-color: rgba(255, 255, 255, 0.35);
  color: rgba(255, 255, 255, 0.95);
}
.bubble.me .miniBtn.danger:hover {
  background: rgba(255, 255, 255, 0.3);
}
.content {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 15px;
  line-height: 1.5;
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
.chatFooter {
  display: flex;
  gap: 10px;
  padding: 12px 16px;
  background: var(--surface);
  border-top: 1px solid var(--divider);
}
.modalMask {
  position: fixed;
  inset: 0;
  z-index: 9998;
  background: rgba(15, 23, 42, 0.35);
  display: grid;
  place-items: center;
}
.modalCard {
  width: min(640px, calc(100vw - 32px));
  max-height: min(640px, calc(100vh - 40px));
  padding: 14px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(12px);
  border-radius: 16px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  box-shadow: 0 22px 60px rgba(15, 23, 42, 0.28);
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.modalTop {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.modalTitle {
  font-weight: 900;
  font-size: 16px;
}
.x {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(255, 255, 255, 0.88);
  cursor: pointer;
  font-size: 18px;
  line-height: 30px;
  color: rgba(15, 23, 42, 0.68);
}
.x:hover {
  background: #ffffff;
}
.modalBody {
  flex: 1;
  overflow: auto;
  border-radius: 14px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(255, 255, 255, 0.86);
}
.tail {
  padding: 12px;
  text-align: center;
  font-size: 12px;
  color: rgba(15, 23, 42, 0.62);
}
.tail.error {
  color: var(--danger);
}
.callList {
  display: flex;
  flex-direction: column;
}
.callRow {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
  align-items: center;
  padding: 10px 12px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
}
.callMain {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.pill {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 800;
  background: rgba(7, 193, 96, 0.12);
  color: rgba(7, 193, 96, 0.95);
  border: 1px solid rgba(7, 193, 96, 0.18);
}
.callStatus {
  font-weight: 850;
  color: rgba(15, 23, 42, 0.9);
  font-size: 13px;
}
.callSub {
  margin-top: 4px;
  font-size: 12px;
}
.callRight {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.55);
  white-space: nowrap;
}
.modalActions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
.modalActions .btn.primary {
  border-color: rgba(7, 193, 96, 0.35);
  background: rgba(7, 193, 96, 0.16);
  color: rgba(7, 193, 96, 0.95);
}
.error {
  padding: 10px 16px;
  color: var(--danger);
  background: rgba(250, 81, 81, 0.06);
  border-top: 1px solid rgba(250, 81, 81, 0.14);
  font-size: 12px;
}
</style>
