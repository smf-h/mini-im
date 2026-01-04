<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiGet } from '../services/api'
import type { GroupMemberDto, MessageEntity } from '../types/api'
import { formatTime } from '../utils/format'
import { useAuthStore } from '../stores/auth'
import { useWsStore } from '../stores/ws'
import { useUserStore } from '../stores/users'
import { useGroupStore } from '../stores/groups'
import { useDndStore } from '../stores/dnd'
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
const dnd = useDndStore()

auth.hydrateFromStorage()

const groupId = computed(() => String(route.params.groupId ?? ''))
const groupName = computed(() => groups.displayName(groupId.value))
const groupMuted = computed(() => dnd.isGroupMuted(groupId.value))

const items = ref<UiMessage[]>([])
const loading = ref(false)
const errorMsg = ref<string | null>(null)
const done = ref(false)
const lastId = ref<string | null>(null)
const wsCursor = ref(0)

const draft = ref('')
const draftEl = ref<HTMLInputElement | null>(null)
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

const members = ref<GroupMemberDto[]>([])
const membersLoaded = ref(false)

const selfSpeakMutedUntilTs = computed(() => {
  const uid = auth.userId
  if (!uid) return null
  const me = members.value.find((m) => String(m.userId) === String(uid))
  const t = toTs(me?.speakMuteUntil ?? null)
  if (t == null) return null
  return t > Date.now() ? t : null
})

const selfSpeakMutedText = computed(() => {
  const t = selfSpeakMutedUntilTs.value
  if (t == null) return null
  const d = new Date(t)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `你已被禁言至 ${hh}:${mm}，期间不能发送群消息`
})

const selfSpeakMuted = computed(() => selfSpeakMutedUntilTs.value != null)
const draftPlaceholder = computed(() =>
  selfSpeakMuted.value ? '你已被禁言，暂时无法发送' : '输入消息…（输入 @ 可选群成员；也可手动 @123；回复可触发重要提醒）',
)

const mentionOpen = ref(false)
const mentionStart = ref<number | null>(null)
const mentionQuery = ref('')
const mentionActiveIndex = ref(0)
const mentionPicked = ref<Record<string, true>>({})
const mentionWrapEl = ref<HTMLElement | null>(null)

function uuid() {
  return crypto.randomUUID()
}

function toTs(v?: string | null) {
  if (!v) return null
  const t = new Date(v).getTime()
  if (!Number.isFinite(t)) return null
  return t
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

function memberDisplayName(userId: string) {
  const id = String(userId)
  const cached = users.displayName(id)
  if (cached && cached !== id) return cached
  const m = members.value.find((x) => x.userId === id)
  if (!m) return cached || id
  const nick = (m.nickname ?? '').trim()
  if (nick) return nick
  const uname = (m.username ?? '').trim()
  if (uname) return uname
  return cached || id
}

async function loadMembers() {
  if (!groupId.value) return
  membersLoaded.value = false
  members.value = []
  try {
    const list = await apiGet<GroupMemberDto[]>(`/group/member/list?groupId=${encodeURIComponent(groupId.value)}`)
    members.value = list ?? []
    membersLoaded.value = true
    void users.ensureBasics(members.value.map((m) => m.userId))
  } catch {
    membersLoaded.value = false
  }
}

const mentionCandidates = computed(() => {
  const q = mentionQuery.value.trim().toLowerCase()
  const list = members.value.filter((m) => m.userId !== auth.userId)
  if (!q) return list.slice(0, 30)
  return list
    .filter((m) => {
      const id = String(m.userId).toLowerCase()
      const name = memberDisplayName(m.userId).toLowerCase()
      return id.startsWith(q) || name.includes(q)
    })
    .slice(0, 30)
})

function closeMention() {
  mentionOpen.value = false
  mentionStart.value = null
  mentionQuery.value = ''
  mentionActiveIndex.value = 0
}

function updateMentionContext() {
  const el = draftEl.value
  if (!el) {
    closeMention()
    return
  }

  const caret = el.selectionStart ?? draft.value.length
  const text = draft.value

  const before = text.slice(0, caret)
  const at = before.lastIndexOf('@')
  if (at < 0) {
    closeMention()
    return
  }

  const prev = at === 0 ? ' ' : before[at - 1]!
  if (prev.trim() !== '') {
    closeMention()
    return
  }

  const tail = before.slice(at + 1)
  if (/\s/.test(tail)) {
    closeMention()
    return
  }

  mentionStart.value = at
  mentionQuery.value = tail
  mentionOpen.value = true
  mentionActiveIndex.value = 0
}

function pickMention(m: GroupMemberDto) {
  const el = draftEl.value
  if (!el) return
  const start = mentionStart.value
  if (start == null) return

  const caret = el.selectionStart ?? draft.value.length
  const name = memberDisplayName(m.userId)
  const insert = `@${name} `

  const left = draft.value.slice(0, start)
  const right = draft.value.slice(caret)
  draft.value = `${left}${insert}${right}`
  mentionPicked.value[String(m.userId)] = true

  void nextTick(() => {
    const nextCaret = left.length + insert.length
    el.focus()
    el.setSelectionRange(nextCaret, nextCaret)
    closeMention()
  })
}

function onDraftInput() {
  updateMentionContext()
}

function onDraftKeyDown(e: KeyboardEvent) {
  if (mentionOpen.value) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      const max = mentionCandidates.value.length
      if (!max) return
      mentionActiveIndex.value = (mentionActiveIndex.value + 1) % max
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      const max = mentionCandidates.value.length
      if (!max) return
      mentionActiveIndex.value = (mentionActiveIndex.value - 1 + max) % max
      return
    }
    if (e.key === 'Enter') {
      e.preventDefault()
      const picked = mentionCandidates.value[mentionActiveIndex.value]
      if (picked) pickMention(picked)
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      closeMention()
      return
    }
  }

  if (e.key === 'Enter') {
    e.preventDefault()
    void send()
  }
}

function onMentionPointerDown(e: PointerEvent) {
  if (!mentionOpen.value) return
  const el = mentionWrapEl.value
  if (!el) return
  if (e.target instanceof Node && el.contains(e.target)) return
  closeMention()
}

function parseMentions(text: string) {
  const out = new Set<string>()

  // 兼容：手动输入 @123
  const re = /@([1-9][0-9]*)/g
  for (;;) {
    const m = re.exec(text)
    if (!m) break
    out.add(m[1]!)
  }

  // 新增：通过成员选择器插入的 @昵称
  for (const uid of Object.keys(mentionPicked.value)) {
    const name = memberDisplayName(uid)
    if (!name) continue
    if (text.includes(`@${name}`)) {
      out.add(uid)
    }
  }

  return Array.from(out)
}

function startReply(m: UiMessage) {
  if (!m.serverMsgId) return
  replyTo.value = m
}

function cancelReply() {
  replyTo.value = null
}

function openUser(id: string) {
  void router.push(`/contacts/u/${id}`)
}

async function toggleGroupMute() {
  if (!groupId.value) return
  try {
    await dnd.toggleGroup(groupId.value)
  } catch (e) {
    errorMsg.value = `设置免打扰失败: ${String(e)}`
  }
}

async function send() {
  errorMsg.value = null
  if (selfSpeakMuted.value) {
    errorMsg.value = selfSpeakMutedText.value ?? '你已被禁言，暂时无法发送'
    return
  }
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
  mentionPicked.value = {}
  closeMention()

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
    if (ev.body) target.content = ev.body
    return
  }

  if (ev.type === 'ERROR' && ev.clientMsgId) {
    const target = items.value.find((m) => m.clientMsgId === ev.clientMsgId)
    if (target) {
      if (ev.reason === 'group_speak_muted') {
        errorMsg.value = selfSpeakMutedText.value ?? '你已被禁言，暂时无法发送'
      } else {
        errorMsg.value = `发送失败: ${ev.reason ?? 'error'}`
      }
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
  void dnd.hydrate()
  void loadMembers()
  window.addEventListener('focus', onFocus)
  document.addEventListener('visibilitychange', onVisibilityChange)
  window.addEventListener('pointerdown', onMentionPointerDown, true)
})

onUnmounted(() => {
  window.removeEventListener('focus', onFocus)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('pointerdown', onMentionPointerDown, true)
})

watch(
  () => groupId.value,
  () => {
    mentionPicked.value = {}
    closeMention()
    void loadMembers()
  },
)
</script>

<template>
  <div class="chatStage">
    <header class="chatHeader">
      <div class="headerMain">
        <div class="title">{{ groupName || `群聊 ${groupId}` }}</div>
        <div v-if="selfSpeakMutedText" class="sub muted" style="color: var(--danger)">{{ selfSpeakMutedText }}</div>
      </div>
      <div class="row">
        <button class="btn" @click="toggleGroupMute">{{ groupMuted ? '已免打扰' : '免打扰' }}</button>
        <button class="btn" @click="router.push(`/chats/group/${groupId}/profile`)">群资料</button>
        <button class="btn" @click="resetAndLoad">刷新</button>
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

    <footer class="chatFooter">
      <div ref="mentionWrapEl" class="draftWrap">
        <div v-if="mentionOpen" class="mentionMenu" role="listbox" aria-label="选择群成员">
          <div v-if="!membersLoaded" class="mentionEmpty muted">加载成员中…</div>
          <button
            v-for="(m, idx) in mentionCandidates"
            :key="m.userId"
            class="mentionItem"
            type="button"
            :data-active="idx === mentionActiveIndex"
            @click="pickMention(m)"
          >
            <UiAvatar :text="memberDisplayName(m.userId)" :seed="m.userId" :size="28" />
            <div class="mentionMeta">
              <div class="mentionName">{{ memberDisplayName(m.userId) }}</div>
              <div class="mentionId muted">uid={{ m.userId }}</div>
            </div>
          </button>
          <div v-if="membersLoaded && !mentionCandidates.length" class="mentionEmpty muted">没有匹配的成员</div>
        </div>

        <input
          ref="draftEl"
          v-model="draft"
          class="input"
          :disabled="selfSpeakMuted"
          :placeholder="draftPlaceholder"
          @input="onDraftInput"
          @keydown="onDraftKeyDown"
        />
      </div>
      <button class="btn primary" :disabled="selfSpeakMuted" @click="send">发送</button>
    </footer>

    <div v-if="errorMsg" class="error">{{ errorMsg }}</div>
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
.bubble.me {
  background: #07c160;
  border-color: rgba(7, 193, 96, 0.3);
  color: #ffffff;
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
.bubble.important:not(.me) {
  border-color: rgba(59, 130, 246, 0.25);
  box-shadow: 0 2px 8px rgba(59, 130, 246, 0.12);
}
.meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.bubble:not(.me) .muted {
  color: rgba(15, 23, 42, 0.5);
}
.bubble.me .muted {
  color: rgba(255, 255, 255, 0.75);
}
.content {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 15px;
  line-height: 1.5;
}
.status {
  margin-left: auto;
  font-size: 11px;
  color: rgba(15, 23, 42, 0.5);
}
.bubble.me .status {
  color: rgba(255, 255, 255, 0.75);
}
.tag {
  padding: 2px 8px;
  height: 18px;
  border-radius: 999px;
  background: rgba(59, 130, 246, 0.15);
  border: 1px solid rgba(59, 130, 246, 0.2);
  color: rgba(30, 64, 175, 0.95);
  line-height: 14px;
  font-size: 11px;
  font-weight: 700;
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
.bubble.me .miniBtn {
  border-color: rgba(255, 255, 255, 0.3);
  background: rgba(255, 255, 255, 0.2);
  color: rgba(255, 255, 255, 0.95);
}
.bubble.me .miniBtn:hover {
  background: rgba(255, 255, 255, 0.3);
}
.replyBar {
  margin: 10px 16px 0;
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
  flex-shrink: 0;
  margin-top: 2px;
}
.msgRow:not(.me) .avatarBtn {
  margin-right: 10px;
}
.avatarBtn.me {
  margin-left: 10px;
}
.chatFooter {
  display: flex;
  gap: 10px;
  padding: 12px 16px;
  background: var(--surface);
  border-top: 1px solid var(--divider);
}
.draftWrap {
  flex: 1;
  min-width: 0;
  position: relative;
}
.mentionMenu {
  position: absolute;
  left: 0;
  right: 0;
  bottom: calc(100% + 8px);
  max-height: 260px;
  overflow: auto;
  border-radius: 14px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(10px);
  box-shadow: 0 14px 40px rgba(15, 23, 42, 0.14);
  padding: 6px;
}
.mentionItem {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 12px;
  border: 1px solid transparent;
  background: transparent;
  cursor: pointer;
  text-align: left;
}
.mentionItem:hover {
  background: rgba(0, 0, 0, 0.03);
}
.mentionItem[data-active='true'] {
  background: rgba(7, 193, 96, 0.12);
  border-color: rgba(7, 193, 96, 0.24);
}
.mentionMeta {
  min-width: 0;
}
.mentionName {
  font-weight: 850;
  font-size: 13px;
  color: rgba(15, 23, 42, 0.92);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mentionId {
  margin-top: 2px;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mentionEmpty {
  padding: 8px 10px;
  font-size: 12px;
}
.error {
  padding: 10px 16px;
  color: var(--danger);
  background: rgba(250, 81, 81, 0.06);
  border-top: 1px solid rgba(250, 81, 81, 0.14);
  font-size: 12px;
}
</style>
