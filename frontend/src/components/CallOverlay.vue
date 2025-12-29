<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useCallStore } from '../stores/call'
import { useUserStore } from '../stores/users'

const call = useCallStore()
const users = useUserStore()

const peerName = computed(() => (call.peerUserId ? users.displayName(call.peerUserId) : ''))
const titleText = computed(() => {
  if (call.phase === 'incoming') return `来自 ${peerName.value || call.peerUserId} 的视频通话`
  if (call.phase === 'outgoing') return `正在呼叫 ${peerName.value || call.peerUserId}…`
  if (call.phase === 'in_call') return `${peerName.value || call.peerUserId}`
  if (call.phase === 'ended') return '通话结束'
  return ''
})

const subText = computed(() => {
  if (call.phase === 'incoming') return '对方想和你进行视频通话'
  if (call.phase === 'outgoing') return '等待对方接听'
  if (call.phase === 'in_call') return '视频通话中'
  if (call.phase === 'ended') return call.lastReason || '已结束'
  return ''
})

const debugText = computed(() => {
  const localV = call.localStream ? call.localStream.getVideoTracks().length : 0
  const localA = call.localStream ? call.localStream.getAudioTracks().length : 0
  const remoteV = call.remoteStream ? call.remoteStream.getVideoTracks().length : 0
  const remoteA = call.remoteStream ? call.remoteStream.getAudioTracks().length : 0
  return `secure=${window.isSecureContext ? 'yes' : 'no'} · pc=${call.pcState} · ice=${call.iceState} · localV/A=${localV}/${localA} · remoteV/A=${remoteV}/${remoteA}`
})

type UiMode = 'full' | 'mini'
const uiMode = ref<UiMode>('full')
const miniRect = ref({ x: 20, y: 20, w: 360, h: 240 })
const dragging = ref(false)
const dragOffset = ref({ x: 0, y: 0 })
const panelEl = ref<HTMLElement | null>(null)
const videoWrapEl = ref<HTMLElement | null>(null)
const isFullscreen = ref(false)

const remoteEl = ref<HTMLVideoElement | null>(null)
const localEl = ref<HTMLVideoElement | null>(null)

async function safePlay(el: HTMLVideoElement | null) {
  if (!el) return
  try {
    await el.play()
  } catch {
    try {
      el.muted = true
      await el.play()
    } catch {
      // ignore
    }
  }
}

function bindStreams() {
  if (remoteEl.value) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(remoteEl.value as any).srcObject = call.remoteStream
    void nextTick(() => safePlay(remoteEl.value))
  }
  if (localEl.value) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(localEl.value as any).srcObject = call.localStream
    void nextTick(() => safePlay(localEl.value))
  }
}

watch(
  () => [call.remoteStream, call.localStream, call.phase],
  () => bindStreams(),
)

function onKeyDown(e: KeyboardEvent) {
  if (!call.visible) return
  if (e.key === 'Escape' && call.phase === 'ended') {
    call.clear()
  }
}

function minimize() {
  const w = Math.min(360, Math.max(260, Math.floor(window.innerWidth * 0.32)))
  const h = Math.min(240, Math.max(180, Math.floor(window.innerHeight * 0.28)))
  miniRect.value = { x: Math.max(8, window.innerWidth - w - 16), y: 88, w, h }
  clampRect()
  uiMode.value = 'mini'
}

function maximize() {
  uiMode.value = 'full'
}

async function toggleFullscreen() {
  const el = panelEl.value ?? videoWrapEl.value
  if (!el) return
  try {
    if (!document.fullscreenElement) {
      await el.requestFullscreen()
    } else {
      await document.exitFullscreen()
    }
  } catch {
    // ignore
  }
}

function clampRect() {
  const vw = window.innerWidth
  const vh = window.innerHeight
  const r = miniRect.value
  const w = Math.min(Math.max(r.w, 260), vw)
  const h = Math.min(Math.max(r.h, 180), vh)
  const x = Math.min(Math.max(r.x, 8), Math.max(8, vw - w - 8))
  const y = Math.min(Math.max(r.y, 8), Math.max(8, vh - h - 8))
  miniRect.value = { x, y, w, h }
}

function onHeaderPointerDown(e: PointerEvent) {
  if (uiMode.value !== 'mini') return
  if (e.button !== 0) return
  const target = e.target as HTMLElement | null
  if (target && (target.closest('button') || target.closest('a') || target.closest('input'))) return
  dragging.value = true
  dragOffset.value = { x: e.clientX - miniRect.value.x, y: e.clientY - miniRect.value.y }
  ;(e.currentTarget as HTMLElement | null)?.setPointerCapture?.(e.pointerId)
  e.preventDefault()
}

function onHeaderPointerMove(e: PointerEvent) {
  if (!dragging.value) return
  miniRect.value = {
    ...miniRect.value,
    x: e.clientX - dragOffset.value.x,
    y: e.clientY - dragOffset.value.y,
  }
  clampRect()
}

function onHeaderPointerUp() {
  dragging.value = false
}

function onResize() {
  clampRect()
}

function onFullscreenChange() {
  isFullscreen.value = !!document.fullscreenElement
}

onMounted(() => {
  bindStreams()
  window.addEventListener('keydown', onKeyDown)
  window.addEventListener('resize', onResize)
  document.addEventListener('fullscreenchange', onFullscreenChange)

  const ro = new ResizeObserver(() => {
    if (uiMode.value !== 'mini') return
    const el = panelEl.value
    if (!el) return
    const rect = el.getBoundingClientRect()
    if (rect.width > 0 && rect.height > 0) {
      miniRect.value = {
        x: Math.round(rect.left),
        y: Math.round(rect.top),
        w: Math.round(rect.width),
        h: Math.round(rect.height),
      }
      clampRect()
    }
  })
  if (panelEl.value) ro.observe(panelEl.value)

  ;(window as unknown as { __miniImCallRo?: ResizeObserver }).__miniImCallRo = ro
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeyDown)
  window.removeEventListener('resize', onResize)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  try {
    ;(window as unknown as { __miniImCallRo?: ResizeObserver }).__miniImCallRo?.disconnect()
  } catch {
    // ignore
  }
})
</script>

<template>
  <div
    v-if="call.visible"
    class="mask"
    :class="{ mini: uiMode === 'mini' }"
    @click.self="uiMode === 'mini' ? null : call.phase === 'ended' ? call.clear() : null"
  >
    <div
      ref="panelEl"
      class="panel"
      :class="{ mini: uiMode === 'mini' }"
      role="dialog"
      aria-modal="true"
      aria-label="视频通话"
      :style="
        uiMode === 'mini'
          ? {
              left: `${miniRect.x}px`,
              top: `${miniRect.y}px`,
              width: `${miniRect.w}px`,
              height: `${miniRect.h}px`,
            }
          : undefined
      "
    >
      <div
        class="top"
        :class="{ draggable: uiMode === 'mini' }"
        @pointerdown="onHeaderPointerDown"
        @pointermove="onHeaderPointerMove"
        @pointerup="onHeaderPointerUp"
        @pointercancel="onHeaderPointerUp"
      >
        <div class="tMain">
          <div class="title">{{ titleText }}</div>
          <div class="sub">{{ subText }}</div>
          <div class="debug">{{ debugText }}</div>
        </div>
        <div class="topActions">
          <button v-if="uiMode === 'full' && call.phase !== 'ended'" class="x" type="button" aria-label="缩小" @click="minimize">
            缩小
          </button>
          <button v-if="uiMode === 'mini'" class="x" type="button" aria-label="放大" @click="maximize">放大</button>
          <button v-if="uiMode === 'full'" class="x" type="button" aria-label="全屏" @click="toggleFullscreen">
            {{ isFullscreen ? '退出全屏' : '全屏' }}
          </button>
          <button v-if="call.phase === 'ended'" class="x" type="button" aria-label="关闭" @click="call.clear()">×</button>
        </div>
      </div>

      <div
        v-if="call.phase === 'in_call' || call.phase === 'outgoing' || call.phase === 'incoming'"
        ref="videoWrapEl"
        class="videoWrap"
      >
        <video v-if="call.remoteStream" ref="remoteEl" class="remoteVideo" autoplay playsinline></video>
        <div v-else class="remotePlaceholder">等待对方视频…</div>
        <video v-if="call.localStream" ref="localEl" class="localVideo" autoplay playsinline muted></video>
        <div v-else class="localPlaceholder">未获取摄像头权限</div>

        <div v-if="uiMode === 'mini'" class="miniControls">
          <template v-if="call.phase === 'incoming'">
            <button class="miniBtn danger" type="button" @click="call.rejectIncoming()">拒绝</button>
            <button class="miniBtn primary" type="button" @click="call.acceptIncoming()">接听</button>
          </template>
          <template v-else-if="call.phase === 'outgoing'">
            <button class="miniBtn danger" type="button" @click="call.cancelOutgoing()">取消</button>
          </template>
          <template v-else-if="call.phase === 'in_call'">
            <button class="miniBtn" type="button" @click="call.toggleAudio()">{{ call.audioEnabled ? '静音' : '取消静音' }}</button>
            <button class="miniBtn" type="button" @click="call.toggleVideo()">
              {{ call.videoEnabled ? '关摄像头' : '开摄像头' }}
            </button>
            <button class="miniBtn danger" type="button" @click="call.hangup()">挂断</button>
          </template>
        </div>
      </div>

      <div v-else class="placeholder">
        <div class="hint">{{ call.callId ? `callId=${call.callId}` : '' }}</div>
      </div>

      <div v-if="uiMode === 'full'" class="actions">
        <template v-if="call.phase === 'incoming'">
          <button class="btn danger" type="button" @click="call.rejectIncoming()">拒绝</button>
          <button class="btn primary" type="button" @click="call.acceptIncoming()">接听</button>
        </template>
        <template v-else-if="call.phase === 'outgoing'">
          <button class="btn danger" type="button" @click="call.cancelOutgoing()">取消</button>
        </template>
        <template v-else-if="call.phase === 'in_call'">
          <button class="btn" type="button" @click="call.toggleAudio()">{{ call.audioEnabled ? '静音' : '取消静音' }}</button>
          <button class="btn" type="button" @click="call.toggleVideo()">{{ call.videoEnabled ? '关摄像头' : '开摄像头' }}</button>
          <button class="btn danger" type="button" @click="call.hangup()">挂断</button>
        </template>
        <template v-else-if="call.phase === 'ended'">
          <button class="btn" type="button" @click="call.clear()">关闭</button>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(15, 23, 42, 0.45);
  display: grid;
  place-items: center;
}
.mask.mini {
  background: transparent;
  place-items: initial;
  pointer-events: none;
}
.panel {
  width: min(920px, calc(100vw - 32px));
  height: min(640px, calc(100vh - 40px));
  border-radius: 18px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(10px);
  box-shadow: 0 22px 60px rgba(15, 23, 42, 0.28);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.panel.mini {
  position: fixed;
  pointer-events: auto;
  border-radius: 14px;
  box-shadow: 0 18px 46px rgba(15, 23, 42, 0.32);
  resize: both;
  overflow: hidden;
  min-width: 260px;
  min-height: 180px;
}
.top {
  padding: 14px 14px 10px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.top.draggable {
  cursor: move;
}
.topActions {
  display: flex;
  gap: 8px;
}
.title {
  font-weight: 900;
  font-size: 16px;
  color: #0f172a;
}
.sub {
  margin-top: 4px;
  font-size: 12px;
  color: rgba(15, 23, 42, 0.7);
}
.debug {
  margin-top: 6px;
  font-size: 12px;
  color: rgba(15, 23, 42, 0.55);
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
.videoWrap {
  position: relative;
  flex: 1;
  background: #0b1220;
  overflow: hidden;
}
.remoteVideo {
  width: 100%;
  height: 100%;
  object-fit: cover;
  background: #0b1220;
}
.remotePlaceholder {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  color: rgba(255, 255, 255, 0.7);
  font-size: 13px;
}
.localVideo {
  position: absolute;
  right: 14px;
  bottom: 14px;
  width: clamp(110px, 26%, 180px);
  height: clamp(82px, 20%, 132px);
  object-fit: cover;
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  box-shadow: 0 12px 30px rgba(0, 0, 0, 0.35);
  background: #0b1220;
  z-index: 2;
}
.localPlaceholder {
  position: absolute;
  right: 14px;
  bottom: 14px;
  width: clamp(110px, 26%, 180px);
  height: clamp(82px, 20%, 132px);
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  display: grid;
  place-items: center;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.7);
  background: rgba(11, 18, 32, 0.9);
  z-index: 2;
}
.miniControls {
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: 10px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  z-index: 3;
}
.miniBtn {
  border: 1px solid rgba(0, 0, 0, 0.12);
  background: rgba(255, 255, 255, 0.9);
  padding: 8px 10px;
  border-radius: 999px;
  cursor: pointer;
  font-weight: 800;
  font-size: 12px;
  color: rgba(15, 23, 42, 0.92);
}
.miniBtn:hover {
  background: #ffffff;
}
.miniBtn.primary {
  border-color: rgba(7, 193, 96, 0.35);
  background: rgba(7, 193, 96, 0.2);
  color: rgba(7, 193, 96, 0.95);
}
.miniBtn.danger {
  border-color: rgba(250, 81, 81, 0.35);
  background: rgba(250, 81, 81, 0.16);
  color: rgba(250, 81, 81, 0.92);
}
.placeholder {
  flex: 1;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, rgba(7, 193, 96, 0.12), rgba(15, 23, 42, 0.06));
}
.hint {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.6);
}
.actions {
  padding: 12px 14px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  background: rgba(255, 255, 255, 0.88);
}
.btn {
  border: 1px solid rgba(0, 0, 0, 0.08);
  background: rgba(255, 255, 255, 0.92);
  padding: 10px 14px;
  border-radius: 12px;
  cursor: pointer;
  font-weight: 750;
  font-size: 13px;
  color: rgba(15, 23, 42, 0.92);
}
.btn:hover {
  background: #ffffff;
}
.btn.primary {
  border-color: rgba(7, 193, 96, 0.35);
  background: rgba(7, 193, 96, 0.16);
  color: rgba(7, 193, 96, 0.95);
}
.btn.danger {
  border-color: rgba(250, 81, 81, 0.35);
  background: rgba(250, 81, 81, 0.12);
  color: rgba(250, 81, 81, 0.92);
}
</style>
