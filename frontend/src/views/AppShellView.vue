<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useWsStore } from '../stores/ws'
import { useUserStore } from '../stores/users'
import { useNotifyStore, type Toast } from '../stores/notify'
import type { WsEnvelope } from '../types/ws'

const auth = useAuthStore()
const ws = useWsStore()
const users = useUserStore()
const notify = useNotifyStore()
const route = useRoute()
const router = useRouter()

const wsBadge = computed(() => {
  if (!ws.connected) return 'WS: 离线'
  if (!ws.authed) return 'WS: 已连(未鉴权)'
  return 'WS: 在线'
})

const wsCursor = ref(0)

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

function isChatRouteWithPeer(peerId: string) {
  return route.path === `/chat/${peerId}`
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
  // 兼容：有些旧事件可能用 content/message 字段
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

  if (ev.type === 'SINGLE_CHAT') {
    if (!ev.from || !ev.to) return
    if (ev.to !== auth.userId) return
    if (isChatRouteWithPeer(ev.from)) return

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
      path: `/chat/${ev.from}`,
    })
    return
  }

  if (ev.type === 'FRIEND_REQUEST') {
    if (route.path === '/friends') return
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
      path: '/friends',
    })
  }
}

function openToast(t: Toast) {
  if (t.path) {
    void router.push(t.path)
  }
  notify.remove(t.id)
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

if (auth.isLoggedIn && route.path !== '/login') {
  void ensureWs()
}

watch(
  () => ws.events.length,
  () => {
    void drainWsEvents()
  },
)

onMounted(() => {
  wsCursor.value = ws.events.length
})
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="container row" style="justify-content: space-between">
        <div class="row" style="gap: 14px">
          <RouterLink class="brand" to="/conversations">mini-im</RouterLink>
          <RouterLink class="nav" to="/conversations">会话</RouterLink>
          <RouterLink class="nav" to="/friends">好友申请</RouterLink>
        </div>
        <div class="row">
          <div class="muted">{{ wsBadge }}</div>
          <div class="muted">uid={{ auth.userId }}</div>
          <button class="btn" @click="ensureWs">重连</button>
          <button class="btn danger" @click="logout">退出</button>
        </div>
      </div>
    </header>
    <main class="container">
      <div class="toastWrap">
        <button v-for="t in notify.toasts" :key="t.id" class="toast" type="button" @click="openToast(t)">
          <div class="toastIcon" :data-kind="t.kind" aria-hidden="true"></div>
          <div class="toastMain">
            <div class="toastTitle">{{ t.title || '通知' }}</div>
            <div class="toastText">{{ t.text || '' }}</div>
          </div>
        </button>
      </div>
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
}
.topbar {
  position: sticky;
  top: 0;
  background: var(--primary);
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  color: #ffffff;
  box-shadow: 0 6px 16px rgba(15, 23, 42, 0.12);
}
.topbar .muted {
  color: rgba(255, 255, 255, 0.86);
}
.topbar .btn {
  background: rgba(255, 255, 255, 0.18);
  border-color: rgba(255, 255, 255, 0.32);
  color: #ffffff;
}
.topbar .btn.danger {
  background: rgba(255, 255, 255, 0.18);
  border-color: rgba(255, 255, 255, 0.32);
  color: #ffffff;
}
.brand {
  font-weight: 700;
  letter-spacing: 0.2px;
  color: #ffffff;
}
.nav {
  padding: 6px 10px;
  border-radius: 10px;
  border: 1px solid transparent;
  color: rgba(255, 255, 255, 0.92);
}
.nav.router-link-active {
  border-color: rgba(255, 255, 255, 0.35);
  background: rgba(255, 255, 255, 0.18);
}
.toastWrap {
  position: fixed;
  right: 16px;
  top: 72px;
  z-index: 9999;
  display: grid;
  gap: 10px;
  width: min(420px, calc(100vw - 32px));
  pointer-events: none;
}
.toast {
  pointer-events: auto;
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 12px;
  border-radius: 14px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(10px);
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.18);
  font-size: 13px;
  line-height: 1.35;
  color: var(--text);
  text-align: left;
  cursor: pointer;
  transition: transform 120ms ease, box-shadow 120ms ease;
}
.toast:hover {
  transform: translateY(-1px);
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.22);
}
.toast:active {
  transform: translateY(0);
}
.toast:focus-visible {
  outline: 2px solid rgba(255, 255, 255, 0.9);
  outline-offset: 2px;
}
.toastIcon {
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  background: rgba(34, 197, 94, 0.18);
}
.toastIcon[data-kind='friend_request'] {
  background: rgba(59, 130, 246, 0.18);
}
.toastMain {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 2px;
}
.toastTitle {
  font-weight: 700;
  color: #0f172a;
}
.toastText {
  color: rgba(15, 23, 42, 0.72);
  overflow: hidden;
  max-height: calc(1.35em * 2);
}
</style>
