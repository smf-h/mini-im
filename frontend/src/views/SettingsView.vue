<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { apiGet } from '../services/api'
import type { MeProfileDto } from '../types/api'
import { useAuthStore } from '../stores/auth'
import { useWsStore } from '../stores/ws'
import UiAvatar from '../components/UiAvatar.vue'

const router = useRouter()
const auth = useAuthStore()
const ws = useWsStore()

const me = ref<MeProfileDto | null>(null)
const loading = ref(false)
const errorMsg = ref<string | null>(null)

const wsBadge = computed(() => {
  if (!ws.connected) return '离线'
  if (!ws.authed) return '已连(未鉴权)'
  return '在线'
})

async function load() {
  if (!auth.userId) return
  loading.value = true
  errorMsg.value = null
  try {
    me.value = await apiGet<MeProfileDto>(`/me/profile`)
  } catch (e) {
    errorMsg.value = String(e)
  } finally {
    loading.value = false
  }
}

function logout() {
  ws.close()
  auth.clear()
  void router.push('/login')
}

onMounted(() => void load())
</script>

<template>
  <div class="wrap">
    <div class="card">
      <div class="top">
        <UiAvatar :text="me?.nickname ?? me?.username ?? auth.userId ?? 'me'" :seed="auth.userId ?? 'me'" :size="64" />
        <div class="meta">
          <div class="name">{{ me?.nickname || me?.username || auth.userId }}</div>
          <div class="statusRow">
            <span class="statusDot" :data-on="ws.connected && ws.authed ? '1' : '0'" aria-hidden="true"></span>
            <div class="muted">{{ wsBadge }}</div>
          </div>
        </div>
      </div>

      <div class="row" style="justify-content: flex-end; margin-top: 12px">
        <button class="btn" @click="ws.connect()">重连 WS</button>
        <button class="btn" @click="router.push(`/contacts/u/${auth.userId}`)">个人主页</button>
        <button class="btn danger" @click="logout">退出登录</button>
      </div>
    </div>

    <div v-if="loading" class="muted" style="margin-top: 10px">加载中…</div>
    <div v-if="errorMsg" class="muted" style="margin-top: 10px; color: var(--danger)">{{ errorMsg }}</div>
  </div>
</template>

<style scoped>
.wrap {
  height: 100%;
  overflow: auto;
  padding: 16px;
  background: var(--bg);
}
.card {
  padding: 16px;
  background: var(--surface);
  border: 1px solid var(--divider);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}
.top {
  display: flex;
  align-items: center;
  gap: 12px;
}
.meta {
  min-width: 0;
  display: grid;
  gap: 4px;
}
.name {
  font-weight: 900;
  font-size: 18px;
}
.statusRow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.statusDot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: rgba(17, 17, 17, 0.18);
  box-shadow: 0 0 0 3px rgba(17, 17, 17, 0.04);
}
.statusDot[data-on='1'] {
  background: var(--primary);
  box-shadow: 0 0 0 3px rgba(7, 193, 96, 0.14);
}
</style>
