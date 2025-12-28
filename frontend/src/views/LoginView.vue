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
  <div class="stage">
    <div class="panel">
      <div class="brand">
        <div class="logo" aria-hidden="true"></div>
        <div>
          <div class="title">mini-im</div>
          <div class="subtitle">微信绿白风格 · 轻量 IM</div>
        </div>
      </div>

      <div class="card authCard">
        <h2 style="margin: 0 0 6px">登录</h2>
        <div class="muted" style="margin-bottom: 16px">后端支持“首次登录自动注册”。</div>

        <div class="form">
          <label class="field">
            <div class="label">用户名</div>
            <input v-model="username" class="fieldInput" autocomplete="username" />
          </label>
          <label class="field">
            <div class="label">密码</div>
            <input v-model="password" class="fieldInput" type="password" autocomplete="current-password" />
          </label>

          <button class="btn primary primaryBtn" :disabled="loading" @click="submit">
            {{ loading ? '登录中…' : '登录' }}
          </button>

          <div v-if="errorMsg" class="muted" style="color: var(--danger)">{{ errorMsg }}</div>
        </div>

        <div class="hint muted">WS：浏览器会用 `?token=` 握手鉴权，连接后再发送 `AUTH` 帧。</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stage {
  min-height: calc(100vh - 72px);
  display: grid;
  place-items: center;
  padding: 32px 16px 60px;
  background:
    radial-gradient(1200px 500px at 20% 10%, rgba(7, 193, 96, 0.18), transparent 60%),
    radial-gradient(900px 360px at 80% 20%, rgba(59, 130, 246, 0.12), transparent 60%),
    var(--bg);
}
.panel {
  width: 100%;
  max-width: 520px;
  display: grid;
  gap: 14px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
}
.logo {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  background: linear-gradient(135deg, var(--primary), rgba(255, 255, 255, 0.35));
  box-shadow: var(--shadow-float);
  border: 1px solid rgba(0, 0, 0, 0.06);
}
.title {
  font-weight: 850;
  letter-spacing: 0.2px;
  font-size: 18px;
}
.subtitle {
  color: var(--text-2);
  font-size: 13px;
}
.authCard {
  padding: 22px 20px;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.55);
}
.form {
  display: grid;
  gap: 12px;
}
.field {
  display: grid;
  gap: 8px;
}
.label {
  color: var(--text-3);
  font-size: 12px;
}
.fieldInput {
  width: 100%;
  padding: 12px 12px;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(0, 0, 0, 0.03);
  transition: box-shadow 160ms ease, border-color 160ms ease, background 160ms ease;
}
.fieldInput:focus {
  outline: none;
  border-color: rgba(7, 193, 96, 0.42);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 0 0 5px rgba(7, 193, 96, 0.14);
}
.primaryBtn {
  width: 100%;
  padding: 12px 14px;
  font-weight: 800;
}
.hint {
  margin-top: 14px;
  font-size: 13px;
}
</style>
