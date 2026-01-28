<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useNotifyStore } from '../stores/notify'
import UiAvatar from './UiAvatar.vue'

const notify = useNotifyStore()
const router = useRouter()

const tone = computed(() => (kind: string) => {
  if (kind === 'error') return 'error'
  if (kind === 'friend_request') return 'friend'
  if (kind === 'message') return 'message'
  return 'info'
})

function openToast(id: string, path?: string) {
  if (path) void router.push(path)
  notify.remove(id)
}
</script>

<template>
  <TransitionGroup name="toast" tag="div" class="wrap" aria-live="polite">
    <div v-for="t in notify.toasts" :key="t.id" class="toast" :data-tone="tone(t.kind)">
      <button class="main" type="button" @click="openToast(t.id, t.path)">
        <div class="left">
          <UiAvatar
            v-if="t.avatarSeed"
            :text="t.avatarText || t.title || 'N'"
            :seed="t.avatarSeed"
            :size="40"
          />
          <div v-else class="icon" aria-hidden="true"></div>
        </div>
        <div class="body">
          <div class="title">{{ t.title || '通知' }}</div>
          <div class="text">{{ t.text || '' }}</div>
        </div>
      </button>
      <button class="close" type="button" aria-label="关闭" @click="notify.remove(t.id)">×</button>
    </div>
  </TransitionGroup>
</template>

<style scoped>
.wrap {
  position: fixed;
  right: 20px;
  top: 20px;
  z-index: 9999;
  display: grid;
  gap: 10px;
  width: min(420px, calc(100vw - 40px));
  pointer-events: none;
}
.toast {
  pointer-events: auto;
  position: relative;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
  padding: 12px 12px;
  border-radius: 14px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(12px);
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.15);
  overflow: hidden;
}
.toast::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 4px;
  background: rgba(7, 193, 96, 0.9);
}
.toast[data-tone='friend']::before {
  background: rgba(24, 144, 255, 0.9);
}
.toast[data-tone='error']::before {
  background: rgba(250, 81, 81, 0.95);
}
.toast[data-tone='info']::before {
  background: rgba(15, 23, 42, 0.25);
}
.main {
  border: 0;
  background: transparent;
  padding: 0;
  cursor: pointer;
  display: flex;
  gap: 10px;
  align-items: flex-start;
  text-align: left;
}
.left {
  flex: none;
}
.icon {
  width: 40px;
  height: 40px;
  border-radius: 14px;
  background: rgba(15, 23, 42, 0.06);
  border: 1px solid rgba(0, 0, 0, 0.06);
}
.body {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 2px;
}
.title {
  font-weight: 850;
  color: rgba(15, 23, 42, 0.92);
  font-size: 14px;
}
.text {
  color: rgba(15, 23, 42, 0.68);
  font-size: 13px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.close {
  width: 28px;
  height: 28px;
  border-radius: 10px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(255, 255, 255, 0.85);
  cursor: pointer;
  color: rgba(15, 23, 42, 0.6);
  display: grid;
  place-items: center;
}
.close:hover {
  background: #ffffff;
}
.toast-enter-active,
.toast-leave-active {
  transition: transform 180ms ease, opacity 180ms ease;
}
.toast-enter-from,
.toast-leave-to {
  transform: translateX(16px);
  opacity: 0;
}
.toast-move {
  transition: transform 180ms ease;
}
</style>

