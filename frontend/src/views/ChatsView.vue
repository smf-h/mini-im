<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import UiAvatar from '../components/UiAvatar.vue'
import UiBadge from '../components/UiBadge.vue'
import UiListItem from '../components/UiListItem.vue'
import UiSegmented from '../components/UiSegmented.vue'
import GlobalActionMenu from '../components/GlobalActionMenu.vue'
import { apiGet } from '../services/api'
import type { GroupConversationDto, SingleChatConversationDto } from '../types/api'
import type { WsEnvelope } from '../types/ws'
import { formatTime } from '../utils/format'
import { useAuthStore } from '../stores/auth'
import { useWsStore } from '../stores/ws'
import { useUserStore } from '../stores/users'
import { useGroupStore } from '../stores/groups'
import { useDndStore } from '../stores/dnd'

type Tab = 'dm' | 'group'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ws = useWsStore()
const users = useUserStore()
const groups = useGroupStore()
const dnd = useDndStore()

const tab = ref<Tab>('dm')

const dmItems = ref<SingleChatConversationDto[]>([])
const dmLoading = ref(false)
const dmDone = ref(false)
const dmError = ref<string | null>(null)

const groupItems = ref<GroupConversationDto[]>([])
const groupLoading = ref(false)
const groupDone = ref(false)
const groupError = ref<string | null>(null)

const listEl = ref<HTMLElement | null>(null)
const wsCursor = ref(0)
const actionOpen = ref(false)
const actionWrapEl = ref<HTMLElement | null>(null)

const tabOptions = [
  { value: 'dm', label: '单聊' },
  { value: 'group', label: '群聊' },
]

const dmCursor = computed(() => {
  const last = dmItems.value.length ? dmItems.value[dmItems.value.length - 1] : undefined
  if (!last) return null
  return { lastUpdatedAt: last.updatedAt, lastId: last.singleChatId }
})

const groupCursor = computed(() => {
  const last = groupItems.value.length ? groupItems.value[groupItems.value.length - 1] : undefined
  if (!last) return null
  return { lastUpdatedAt: last.updatedAt, lastId: last.groupId }
})

function isDmOpen(peerId: string) {
  return route.path === `/chats/dm/${peerId}`
}

function isGroupOpen(groupId: string) {
  return route.path === `/chats/group/${groupId}`
}

async function loadMoreDm() {
  if (dmLoading.value || dmDone.value) return
  dmLoading.value = true
  dmError.value = null
  try {
    const qs = new URLSearchParams()
    qs.set('limit', '30')
    if (dmCursor.value) {
      qs.set('lastUpdatedAt', dmCursor.value.lastUpdatedAt)
      qs.set('lastId', String(dmCursor.value.lastId))
    }
    const data = await apiGet<SingleChatConversationDto[]>(`/single-chat/conversation/cursor?${qs.toString()}`)
    if (!data.length) {
      dmDone.value = true
      return
    }
    dmItems.value.push(...data)
    void users.ensureBasics(data.map((x) => x.peerUserId))
  } catch (e) {
    dmError.value = String(e)
  } finally {
    dmLoading.value = false
  }
}

async function loadMoreGroup() {
  if (groupLoading.value || groupDone.value) return
  groupLoading.value = true
  groupError.value = null
  try {
    const qs = new URLSearchParams()
    qs.set('limit', '30')
    if (groupCursor.value) {
      qs.set('lastUpdatedAt', groupCursor.value.lastUpdatedAt)
      qs.set('lastId', String(groupCursor.value.lastId))
    }
    const data = await apiGet<GroupConversationDto[]>(`/group/conversation/cursor?${qs.toString()}`)
    if (!data.length) {
      groupDone.value = true
      return
    }
    groupItems.value.push(...data)
    groups.upsertBasics(data.map((x) => ({ id: x.groupId, name: x.name })))
  } catch (e) {
    groupError.value = String(e)
  } finally {
    groupLoading.value = false
  }
}

function resetDm() {
  dmItems.value = []
  dmDone.value = false
  void loadMoreDm()
}

function resetGroup() {
  groupItems.value = []
  groupDone.value = false
  void loadMoreGroup()
}

function onScroll() {
  const el = listEl.value
  if (!el) return
  if (el.scrollTop + el.clientHeight < el.scrollHeight - 120) return
  if (tab.value === 'dm') {
    void loadMoreDm()
  } else {
    void loadMoreGroup()
  }
}

function openUser(ev: MouseEvent, id: string) {
  ev.preventDefault()
  ev.stopPropagation()
  void router.push(`/contacts/u/${id}`)
}

function openGroupProfile(ev: MouseEvent, gid: string) {
  ev.preventDefault()
  ev.stopPropagation()
  void router.push(`/chats/group/${gid}/profile`)
}

function uuid() {
  return crypto.randomUUID()
}

async function sendReadAck(serverMsgId: string, to: string) {
  if (!serverMsgId) return
  try {
    await ws.connect()
    ws.send({
      type: 'ACK',
      ackType: 'read',
      clientMsgId: `ackr-${uuid()}`,
      serverMsgId,
      to,
    })
  } catch {
    // ignore
  }
}

function clearDmUnread(peerUserId: string) {
  const idx = dmItems.value.findIndex((c) => c.peerUserId === peerUserId)
  if (idx < 0) return
  const cur = dmItems.value[idx]!
  if ((cur.unreadCount ?? 0) <= 0) return
  dmItems.value.splice(idx, 1, { ...cur, unreadCount: 0 })
}

function clearGroupUnread(groupId: string) {
  const idx = groupItems.value.findIndex((c) => c.groupId === groupId)
  if (idx < 0) return
  const cur = groupItems.value[idx]!
  if ((cur.unreadCount ?? 0) <= 0 && (cur.mentionUnreadCount ?? 0) <= 0) return
  groupItems.value.splice(idx, 1, { ...cur, unreadCount: 0, mentionUnreadCount: 0 })
}

function applyWsEvent(ev: WsEnvelope) {
  if (!auth.userId) return

  if (ev.type === 'SINGLE_CHAT') {
    if (!ev.from || !ev.to) return
    if (ev.to !== auth.userId) return

    const idx = dmItems.value.findIndex((c) => c.peerUserId === ev.from)
    if (idx < 0) {
      resetDm()
      return
    }

    const cur = dmItems.value[idx]!
    const ts = ev.ts ?? Date.now()
    const updatedAt = new Date(ts).toISOString()
    const next: SingleChatConversationDto = {
      ...cur,
      updatedAt,
      unreadCount: isDmOpen(ev.from) ? 0 : (cur.unreadCount ?? 0) + 1,
      lastMessage: {
        serverMsgId: ev.serverMsgId ?? cur.lastMessage?.serverMsgId ?? '',
        fromUserId: ev.from,
        toUserId: ev.to,
        content: ev.body ?? '',
        createdAt: updatedAt,
      },
    }

    dmItems.value.splice(idx, 1)
    dmItems.value.unshift(next)
    void users.ensureBasics([ev.from])
    return
  }

  if (ev.type === 'GROUP_CHAT') {
    if (!ev.groupId) return
    if (!ev.from) return

    const idx = groupItems.value.findIndex((c) => c.groupId === ev.groupId)
    if (idx < 0) {
      resetGroup()
      return
    }

    const cur = groupItems.value[idx]!
    const ts = ev.ts ?? Date.now()
    const updatedAt = new Date(ts).toISOString()
    const next: GroupConversationDto = {
      ...cur,
      updatedAt,
      unreadCount: isGroupOpen(ev.groupId) || ev.from === auth.userId ? 0 : (cur.unreadCount ?? 0) + 1,
      mentionUnreadCount: isGroupOpen(ev.groupId)
        ? 0
        : ev.important
          ? (cur.mentionUnreadCount ?? 0) + 1
          : cur.mentionUnreadCount ?? 0,
      lastMessage: {
        serverMsgId: ev.serverMsgId ?? cur.lastMessage?.serverMsgId ?? '',
        fromUserId: ev.from,
        content: ev.body ?? '',
        createdAt: updatedAt,
      },
    }

    groupItems.value.splice(idx, 1)
    groupItems.value.unshift(next)
  }
}

function openActions() {
  actionOpen.value = !actionOpen.value
}

function onGlobalPointerDown(e: PointerEvent) {
  if (!actionOpen.value) return
  const el = actionWrapEl.value
  if (!el) return
  if (e.target instanceof Node && el.contains(e.target)) return
  actionOpen.value = false
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
  () => tab.value,
  () => {
    const el = listEl.value
    if (el) el.scrollTop = 0
    if (tab.value === 'dm' && !dmItems.value.length && !dmDone.value) {
      void loadMoreDm()
    }
    if (tab.value === 'group' && !groupItems.value.length && !groupDone.value) {
      void loadMoreGroup()
    }
  },
)

watch(
  () => route.path,
  () => {
    actionOpen.value = false

    if (!auth.userId) return

    const dmPrefix = '/chats/dm/'
    if (route.path.startsWith(dmPrefix)) {
      tab.value = 'dm'
      const peer = String(route.params.peerUserId ?? '')
      if (!peer) return
      clearDmUnread(peer)

      const c = dmItems.value.find((x) => x.peerUserId === peer)
      const last = c?.lastMessage
      if (!last?.serverMsgId) return
      if (last.fromUserId === auth.userId) return
      void sendReadAck(last.serverMsgId, peer)
      return
    }

    const groupPrefix = '/chats/group/'
    if (route.path.startsWith(groupPrefix)) {
      tab.value = 'group'
      const gid = String(route.params.groupId ?? '')
      if (!gid) return
      clearGroupUnread(gid)

      const c = groupItems.value.find((x) => x.groupId === gid)
      const last = c?.lastMessage
      if (!last?.serverMsgId) return
      void sendReadAck(last.serverMsgId, auth.userId)
    }
  },
)

onMounted(() => {
  wsCursor.value = ws.events.length
  void ws.connect()
  void dnd.hydrate()
  void loadMoreDm()
  window.addEventListener('pointerdown', onGlobalPointerDown, true)
})

onUnmounted(() => {
  window.removeEventListener('pointerdown', onGlobalPointerDown, true)
})
</script>

<template>
  <div class="module">
    <aside class="listPanel">
      <div class="panelTop">
        <div class="panelTitle">会话</div>
        <div class="panelRight">
          <div class="panelHint">{{ tab === 'dm' ? '单聊' : '群聊' }}</div>
          <div ref="actionWrapEl" class="actionsWrap">
            <button class="plusBtn" type="button" aria-label="添加" @click="openActions">+</button>
            <GlobalActionMenu :open="actionOpen" @close="actionOpen = false" />
          </div>
        </div>
      </div>
      <div class="panelTabs">
        <UiSegmented v-model="tab" :options="tabOptions" />
      </div>

      <div ref="listEl" class="list" @scroll="onScroll">
        <template v-if="tab === 'dm'">
          <UiListItem v-for="c in dmItems" :key="c.singleChatId" :to="`/chats/dm/${c.peerUserId}`">
            <template #left>
              <button class="avatarBtn" type="button" @click="openUser($event, c.peerUserId)">
                <UiAvatar :text="users.displayName(c.peerUserId)" :seed="c.peerUserId" :size="42" />
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
              <div class="rightCol">
                <button
                  class="muteBtn"
                  type="button"
                  :data-active="dnd.isDmMuted(c.peerUserId)"
                  title="免打扰：仅屏蔽 toast"
                  @click.stop="void dnd.toggleDm(c.peerUserId)"
                >
                  🔕
                </button>
                <UiBadge :count="c.unreadCount ?? 0" />
              </div>
            </template>
          </UiListItem>

          <div v-if="dmError" class="tail error">{{ dmError }}</div>
          <div class="tail muted">
            <span v-if="dmLoading">加载中…</span>
            <span v-else-if="dmDone">没有更多了</span>
            <span v-else>下滑加载更多</span>
          </div>
        </template>

        <template v-else>
          <UiListItem v-for="c in groupItems" :key="c.groupId" :to="`/chats/group/${c.groupId}`">
            <template #left>
              <button class="avatarBtn" type="button" @click="openGroupProfile($event, c.groupId)">
                <UiAvatar :text="c.name || groups.displayName(c.groupId)" :seed="c.groupId" :size="42" />
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
                <button
                  class="muteBtn"
                  type="button"
                  :data-active="dnd.isGroupMuted(c.groupId)"
                  title="免打扰：仅屏蔽 toast（important/@我 不屏蔽）"
                  @click.stop="void dnd.toggleGroup(c.groupId)"
                >
                  🔕
                </button>
                <div v-if="(c.mentionUnreadCount ?? 0) > 0" class="mentionTag">@</div>
                <UiBadge :count="c.unreadCount ?? 0" />
              </div>
            </template>
          </UiListItem>

          <div v-if="groupError" class="tail error">{{ groupError }}</div>
          <div class="tail muted">
            <span v-if="groupLoading">加载中…</span>
            <span v-else-if="groupDone">没有更多了</span>
            <span v-else>下滑加载更多</span>
          </div>
        </template>
      </div>
    </aside>

    <section class="stage">
      <RouterView />
    </section>
  </div>
</template>

<style scoped>
.module {
  height: 100%;
  display: flex;
  background: var(--bg);
}
.listPanel {
  width: 320px;
  flex: none;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--divider);
  background: var(--bg-list);
}
.panelTop {
  padding: 14px 14px 10px;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  border-bottom: 1px solid var(--divider);
}
.panelRight {
  display: flex;
  align-items: center;
  gap: 10px;
}
.panelTitle {
  font-weight: 900;
  font-size: 16px;
}
.panelHint {
  font-size: 12px;
  color: var(--text-3);
}
.actionsWrap {
  position: relative;
}
.plusBtn {
  width: 30px;
  height: 30px;
  border-radius: 12px;
  border: 1px solid var(--divider);
  background: rgba(7, 193, 96, 0.12);
  color: rgba(7, 193, 96, 0.92);
  cursor: pointer;
  font-weight: 900;
}
.plusBtn:hover {
  background: rgba(7, 193, 96, 0.16);
}
.panelTabs {
  padding: 10px 14px;
  border-bottom: 1px solid var(--divider);
}
.list {
  flex: 1;
  overflow: auto;
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
.stage {
  flex: 1;
  min-width: 0;
  height: 100%;
  overflow: hidden;
}
.tail {
  padding: 10px 12px;
  text-align: center;
  font-size: 12px;
}
.tail.error {
  color: var(--danger);
}
.rightCol {
  display: grid;
  gap: 6px;
  justify-items: end;
}
.muteBtn {
  width: 22px;
  height: 22px;
  border-radius: 999px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  background: transparent;
  display: grid;
  place-items: center;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  color: rgba(15, 23, 42, 0.6);
  opacity: 0.5;
}
.muteBtn:hover {
  opacity: 0.85;
  background: rgba(0, 0, 0, 0.04);
}
.muteBtn[data-active='true'] {
  opacity: 1;
  color: rgba(15, 23, 42, 0.92);
  border-color: rgba(7, 193, 96, 0.35);
  background: rgba(7, 193, 96, 0.12);
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
</style>
