<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

const username = ref('user10001')
const password = ref('123456')
const loading = ref(false)
const errorMsg = ref<string | null>(null)

async function submit() {
  errorMsg.value = null
  loading.value = true
  try {
    await auth.login(username.value.trim(), password.value)
    await router.push('/conversations')
  } catch (e) {
    errorMsg.value = String(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="container" style="max-width: 520px; padding-top: 64px">
    <div class="card" style="padding: 20px">
      <h2 style="margin: 0 0 6px">登录</h2>
      <div class="muted" style="margin-bottom: 16px">后端支持“首次登录自动注册”。</div>

      <div style="display: grid; gap: 12px">
        <label>
          <div class="muted" style="margin-bottom: 6px">用户名</div>
          <input v-model="username" class="input" autocomplete="username" />
        </label>
        <label>
          <div class="muted" style="margin-bottom: 6px">密码</div>
          <input v-model="password" class="input" type="password" autocomplete="current-password" />
        </label>
        <button class="btn primary" :disabled="loading" @click="submit">
          {{ loading ? '登录中…' : '登录' }}
        </button>
        <div v-if="errorMsg" class="muted" style="color: var(--danger)">{{ errorMsg }}</div>
      </div>

      <div class="muted" style="margin-top: 16px; font-size: 13px">
        WS：浏览器会用 `?token=` 握手鉴权，连接后再发送 `AUTH` 帧。
      </div>
    </div>
  </div>
</template>
