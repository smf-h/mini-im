<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useWsStore } from '../stores/ws'
import { useUserStore } from '../stores/users'
import { useGroupStore } from '../stores/groups'
import { useNotifyStore } from '../stores/notify'
import { useDndStore } from '../stores/dnd'
import { useCallStore } from '../stores/call'
import type { WsEnvelope } from '../types/ws'
import UiAvatar from './UiAvatar.vue'
import UiToastContainer from './UiToastContainer.vue'
import CallOverlay from './CallOverlay.vue'

const auth = useAuthStore()
const ws = useWsStore()
const users = useUserStore()
const groups = useGroupStore()
const notify = useNotifyStore()
const dnd = useDndStore()
const call = useCallStore()
const route = useRoute()
const router = useRouter()

const showLayout = computed(() => auth.isLoggedIn && route.path !== '/login')

const wsBadge = computed(() => {
  if (!ws.connected) return '离线'
  if (!ws.authed) return '已连(未鉴权)'
  return '在线'
})

const wsCursor = ref(0)
const showMeMenu = ref(false)
const meWrapEl = ref<HTMLElement | null>(null)

async function ensureWs() {
  try {
    await ws.connect()
  } catch {
    // ignore
  }
}

function logout() {
  ws.close()
  auth.clear()
  void router.push('/login')
}

function isDmRouteWithPeer(peerId: string) {
  return route.path === `/chats/dm/${peerId}`
}

function isGroupRouteWithId(groupId: string) {
  return route.path === `/chats/group/${groupId}`
}

function normalizeText(v: unknown) {
  if (v == null) return ''
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  try {
    return JSON.stringify(v)
  } catch {
    return String(v)
  }
}

function getEnvelopeBody(ev: WsEnvelope) {
  const anyEv = ev as unknown as Record<string, unknown>
  return normalizeText(anyEv.body ?? anyEv.content ?? anyEv.message)
}

function formatToastSnippet(body: unknown) {
  const raw = normalizeText(body).trim()
  const snippet = raw.length > 60 ? `${raw.slice(0, 60)}…` : raw
  return snippet || '（空消息）'
}

async function handleWsEvent(ev: WsEnvelope) {
  if (!auth.userId) return

  if (ev.type?.startsWith('CALL_')) {
    await call.handleWsEvent(ev)
    return
  }

  if (ev.type === 'SINGLE_CHAT') {
    if (!ev.from || !ev.to) return
    if (ev.to !== auth.userId) return
    if (isDmRouteWithPeer(ev.from)) return
    if (dnd.isDmMuted(ev.from)) return

    const key = `toast:single_chat:${ev.serverMsgId ?? ev.clientMsgId ?? `${ev.from}:${ev.ts ?? ''}`}`
    try {
      await users.ensureBasics([ev.from])
    } catch {
      // ignore
    }
    const name = users.displayName(ev.from)
    notify.push({
      key,
      kind: 'message',
      title: name || String(ev.from),
      text: formatToastSnippet(getEnvelopeBody(ev)),
      avatarSeed: ev.from,
      avatarText: name || String(ev.from),
      path: `/chats/dm/${ev.from}`,
    })
    return
  }

  if (ev.type === 'GROUP_CHAT') {
    if (!ev.groupId) return
    if (!ev.from) return
    if (!ev.important) return
    if (isGroupRouteWithId(ev.groupId)) return

    try {
      await groups.ensureBasics([ev.groupId])
      await users.ensureBasics([ev.from])
    } catch {
      // ignore
    }

    const groupName = groups.displayName(ev.groupId)
    const fromName = users.displayName(ev.from)
    const raw = getEnvelopeBody(ev).trim()
    notify.push({
      key: `toast:group_important:${ev.serverMsgId ?? ev.clientMsgId ?? `${ev.groupId}:${ev.from}:${ev.ts ?? ''}`}`,
      kind: 'message',
      title: groupName || `群聊 ${ev.groupId}`,
      text: raw ? `${fromName}: ${formatToastSnippet(raw)}` : `来自 ${fromName}`,
      avatarSeed: ev.from,
      avatarText: fromName || String(ev.from),
      path: `/chats/group/${ev.groupId}`,
    })
    return
  }

  if (ev.type === 'GROUP_JOIN_REQUEST') {
    if (!ev.groupId) return
    if (!ev.from) return

    try {
      await groups.ensureBasics([ev.groupId])
      await users.ensureBasics([ev.from])
    } catch {
      // ignore
    }

    const groupName = groups.displayName(ev.groupId)
    const fromName = users.displayName(ev.from)
    notify.push({
      key: `toast:group_join_request:${ev.serverMsgId ?? ev.clientMsgId ?? `${ev.groupId}:${ev.from}:${ev.ts ?? ''}`}`,
      kind: 'friend_request',
      title: groupName || `群 ${ev.groupId}`,
      text: `入群申请：${fromName}${getEnvelopeBody(ev) ? ` - ${formatToastSnippet(getEnvelopeBody(ev))}` : ''}`,
      avatarSeed: ev.groupId,
      avatarText: groupName || `群 ${ev.groupId}`,
      path: `/chats/group/${ev.groupId}/profile`,
    })
    return
  }

  if (ev.type === 'GROUP_JOIN_DECISION') {
    if (!ev.groupId) return
    if (!ev.to || ev.to !== auth.userId) return

    try {
      await groups.ensureBasics([ev.groupId])
    } catch {
      // ignore
    }

    const groupName = groups.displayName(ev.groupId)
    const decision = (getEnvelopeBody(ev) || '').toUpperCase()
    const text = decision === 'ACCEPTED' ? '入群申请已通过' : decision === 'REJECTED' ? '入群申请被拒绝' : '入群申请状态更新'
    notify.push({
      key: `toast:group_join_decision:${ev.serverMsgId ?? ev.clientMsgId ?? `${ev.groupId}:${ev.ts ?? ''}`}`,
      kind: 'message',
      title: groupName || `群 ${ev.groupId}`,
      text,
      avatarSeed: ev.groupId,
      avatarText: groupName || `群 ${ev.groupId}`,
      path: `/chats/group/${ev.groupId}/profile`,
    })
    return
  }

  if (ev.type === 'FRIEND_REQUEST') {
    if (route.path.startsWith('/contacts/new-friends')) return
    if (!ev.from || !ev.to) return
    if (ev.to !== auth.userId) return

    const key = `toast:friend_request:${ev.serverMsgId ?? ev.clientMsgId ?? `${ev.from}:${ev.ts ?? ''}`}`
    try {
      await users.ensureBasics([ev.from])
    } catch {
      // ignore
    }
    const name = users.displayName(ev.from)
    const raw = getEnvelopeBody(ev).trim()
    notify.push({
      key,
      kind: 'friend_request',
      title: '好友申请',
      text: raw ? `${name}: ${formatToastSnippet(raw)}` : `来自 ${name}`,
      avatarSeed: ev.from,
      avatarText: name || String(ev.from),
      path: '/contacts/new-friends',
    })
  }
}

async function drainWsEvents() {
  while (wsCursor.value < ws.events.length) {
    const ev = ws.events[wsCursor.value]
    wsCursor.value++
    if (ev) {
      await handleWsEvent(ev)
    }
  }
}

function onGlobalPointerDown(e: PointerEvent) {
  if (!showMeMenu.value) return
  const el = meWrapEl.value
  if (!el) return
  if (e.target instanceof Node && el.contains(e.target)) return
  showMeMenu.value = false
}

function go(path: string) {
  showMeMenu.value = false
  void router.push(path)
}

if (auth.isLoggedIn && route.path !== '/login') {
  void ensureWs()
}

watch(
  () => auth.userId,
  () => {
    void dnd.hydrate()
  },
  { immediate: true },
)

watch(
  () => ws.events.length,
  () => {
    void drainWsEvents()
  },
)

watch(
  () => route.path,
  () => {
    showMeMenu.value = false
  },
)

onMounted(() => {
  wsCursor.value = ws.events.length
  window.addEventListener('pointerdown', onGlobalPointerDown, true)
})

onUnmounted(() => {
  window.removeEventListener('pointerdown', onGlobalPointerDown, true)
})
</script>

<template>
  <div class="layoutRoot" :class="{ public: !showLayout }">
    <div v-if="!showLayout" class="publicWrap">
      <slot />
    </div>

    <div v-else class="appWrap">
      <aside class="sidebar" aria-label="Sidebar Navigation">
        <button
          class="navIcon"
          type="button"
          :data-active="route.path.startsWith('/chats')"
          aria-label="会话"
          @click="go('/chats')"
        >
          <svg class="iconSvg" viewBox="0 0 24 24" aria-hidden="true">
            <path
              fill="currentColor"
              d="M4 4.75C4 3.784 4.784 3 5.75 3h12.5C19.216 3 20 3.784 20 4.75v9.5c0 .966-.784 1.75-1.75 1.75H10l-4.2 3.15c-.66.496-1.8.034-1.8-.8V4.75Z"
            />
          </svg>
        </button>
        <button
          class="navIcon"
          type="button"
          :data-active="route.path.startsWith('/contacts')"
          aria-label="通讯录"
          @click="go('/contacts')"
        >
          <svg class="iconSvg" viewBox="0 0 24 24" aria-hidden="true">
            <path
              fill="currentColor"
              d="M7.5 3C6.12 3 5 4.12 5 5.5v13C5 19.88 6.12 21 7.5 21h10c.828 0 1.5-.672 1.5-1.5V7.414a2 2 0 0 0-.586-1.414L15 2.586A2 2 0 0 0 13.586 2H7.5Zm7 1.5V7h2.5L14.5 4.5ZM8 9.25c0-.414.336-.75.75-.75h6.5a.75.75 0 0 1 0 1.5h-6.5A.75.75 0 0 1 8 9.25Zm0 3c0-.414.336-.75.75-.75h6.5a.75.75 0 0 1 0 1.5h-6.5A.75.75 0 0 1 8 12.25Zm0 3c0-.414.336-.75.75-.75h4.5a.75.75 0 0 1 0 1.5h-4.5A.75.75 0 0 1 8 15.25Z"
            />
          </svg>
        </button>
        <button
          class="navIcon"
          type="button"
          :data-active="route.path.startsWith('/moments')"
          aria-label="朋友圈"
          @click="go('/moments')"
        >
          <svg class="iconSvg" viewBox="0 0 24 24" aria-hidden="true">
            <path
              fill="currentColor"
              d="M12 2.75c2.32 0 4.21 1.89 4.21 4.21 0 2.32-1.89 4.21-4.21 4.21S7.79 9.28 7.79 6.96C7.79 4.64 9.68 2.75 12 2.75Zm0 10.5c4.85 0 8.75 2.14 8.75 4.95v.55c0 1.38-1.12 2.5-2.5 2.5H5.75a2.5 2.5 0 0 1-2.5-2.5v-.55c0-2.81 3.9-4.95 8.75-4.95Z"
            />
          </svg>
        </button>
        <button
          class="navIcon"
          type="button"
          :data-active="route.path.startsWith('/settings')"
          aria-label="设置"
          @click="go('/settings')"
        >
          <svg class="iconSvg" viewBox="0 0 24 24" aria-hidden="true">
            <path
              fill="currentColor"
              d="M11.983 2.75a1.75 1.75 0 0 1 1.701 1.26l.23.798c.16.552.64.945 1.214 1.02.41.054.81.16 1.193.315a1.75 1.75 0 0 1 1.013 2.326l-.37.743c-.26.52-.18 1.147.197 1.58.277.317.52.668.725 1.046.268.493.84.76 1.39.62l.8-.204a1.75 1.75 0 0 1 2.11 1.71v1.966a1.75 1.75 0 0 1-1.26 1.701l-.798.23c-.552.16-.945.64-1.02 1.214a5.91 5.91 0 0 1-.315 1.193 1.75 1.75 0 0 1-2.326 1.013l-.743-.37c-.52-.26-1.147-.18-1.58.197a5.9 5.9 0 0 1-1.046.725c-.493.268-.76.84-.62 1.39l.204.8a1.75 1.75 0 0 1-1.71 2.11h-1.966a1.75 1.75 0 0 1-1.701-1.26l-.23-.798c-.16-.552-.64-.945-1.214-1.02a5.91 5.91 0 0 1-1.193-.315 1.75 1.75 0 0 1-1.013-2.326l.37-.743c.26-.52.18-1.147-.197-1.58a5.9 5.9 0 0 1-.725-1.046c-.268-.493-.84-.76-1.39-.62l-.8.204A1.75 1.75 0 0 1 2 14.327v-1.966a1.75 1.75 0 0 1 1.26-1.701l.798-.23c.552-.16.945-.64 1.02-1.214.054-.41.16-.81.315-1.193a1.75 1.75 0 0 1 2.326-1.013l.743.37c.52.26 1.147.18 1.58-.197.317-.277.668-.52 1.046-.725.493-.268.76-.84.62-1.39l-.204-.8a1.75 1.75 0 0 1 1.71-2.11h1.966ZM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6Z"
            />
          </svg>
        </button>

        <div ref="meWrapEl" class="sidebarBottom">
          <button class="meBtn" type="button" aria-label="我的菜单" @click="showMeMenu = !showMeMenu">
            <UiAvatar :text="auth.userId ?? 'me'" :seed="auth.userId ?? 'me'" :size="40" />
          </button>
          <div v-if="showMeMenu" class="meMenu" role="menu">
            <div class="meMeta">
              <div class="meTitle">uid={{ auth.userId }}</div>
              <div class="meSub">WS: {{ wsBadge }}</div>
            </div>
            <div class="meActions">
              <button class="menuBtn" type="button" @click="go(`/contacts/u/${auth.userId}`)">个人主页</button>
              <button class="menuBtn" type="button" @click="ensureWs">重连 WS</button>
              <button class="menuBtn danger" type="button" @click="logout">退出登录</button>
            </div>
          </div>
        </div>
      </aside>

      <main class="mainStage">
        <slot />
      </main>
      <CallOverlay />
      <UiToastContainer />
    </div>
  </div>
</template>

<style scoped>
.layoutRoot {
  height: 100vh;
  width: 100vw;
  overflow: hidden;
}
.publicWrap {
  height: 100%;
}
.appWrap {
  height: 100%;
  display: flex;
  background: var(--bg);
}
.sidebar {
  width: 64px;
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px 8px;
  background: var(--bg-sidebar);
  border-right: 1px solid rgba(255, 255, 255, 0.08);
}
.navIcon {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(255, 255, 255, 0.04);
  color: rgba(255, 255, 255, 0.92);
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: transform 120ms ease, background 120ms ease, border-color 120ms ease;
}
.navIcon:hover {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 0.06);
}
.navIcon[data-active='true'] {
  border-color: rgba(7, 193, 96, 0.45);
  background: rgba(7, 193, 96, 0.18);
}
.iconSvg {
  width: 22px;
  height: 22px;
}
.sidebarBottom {
  margin-top: auto;
  position: relative;
}
.meBtn {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(255, 255, 255, 0.04);
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: transform 120ms ease, background 120ms ease, border-color 120ms ease;
}
.meBtn:hover {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 0.08);
  border-color: rgba(255, 255, 255, 0.12);
}
.meMenu {
  position: absolute;
  left: 68px;
  bottom: 0;
  width: 240px;
  border-radius: 16px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(20px);
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.18), 0 2px 8px rgba(15, 23, 42, 0.08);
  overflow: hidden;
}
.meMenu::before {
  content: '';
  position: absolute;
  left: -5px;
  bottom: 20px;
  width: 10px;
  height: 10px;
  background: rgba(255, 255, 255, 0.96);
  border-left: 1px solid rgba(0, 0, 0, 0.05);
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
  transform: rotate(45deg);
}
.meMeta {
  padding: 16px 14px 14px;
  background: linear-gradient(135deg, rgba(7, 193, 96, 0.04) 0%, rgba(59, 130, 246, 0.03) 100%);
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
}
.meTitle {
  font-weight: 700;
  color: #0f172a;
  font-size: 13px;
  letter-spacing: -0.01em;
}
.meSub {
  margin-top: 4px;
  font-size: 11px;
  color: rgba(15, 23, 42, 0.55);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.meSub::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #07c160;
  box-shadow: 0 0 8px rgba(7, 193, 96, 0.5);
}
.meActions {
  padding: 10px;
  display: grid;
  gap: 4px;
}
.menuBtn {
  width: 100%;
  text-align: left;
  border: 1px solid transparent;
  background: transparent;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  color: rgba(15, 23, 42, 0.85);
  transition: all 140ms ease;
  position: relative;
  overflow: hidden;
}
.menuBtn::before {
  content: '';
  position: absolute;
  inset: 0;
  background: rgba(15, 23, 42, 0.04);
  opacity: 0;
  transition: opacity 140ms ease;
}
.menuBtn:hover {
  color: rgba(15, 23, 42, 0.95);
  border-color: rgba(0, 0, 0, 0.06);
}
.menuBtn:hover::before {
  opacity: 1;
}
.menuBtn > * {
  position: relative;
  z-index: 1;
}
.menuBtn.danger {
  color: #f43f5e;
}
.menuBtn.danger:hover {
  border-color: rgba(244, 63, 94, 0.2);
  background: rgba(244, 63, 94, 0.06);
}
.menuBtn.danger:hover::before {
  opacity: 0;
}
.mainStage {
  flex: 1;
  min-width: 0;
  height: 100%;
  overflow: hidden;
}
</style>
