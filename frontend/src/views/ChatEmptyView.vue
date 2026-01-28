<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="empty">
    <div class="card">
      <div class="title">开始聊天</div>
      <div class="sub">在左侧选择一个会话，或去通讯录发起单聊。</div>
      <div class="row" style="margin-top: 12px; justify-content: center">
        <button class="btn primary" @click="router.push('/contacts')">去通讯录</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.empty {
  height: 100%;
  display: grid;
  place-items: center;
  padding: 18px;
  background: var(--bg);
}
.card {
  width: min(520px, 92%);
  padding: 22px 18px;
  background: var(--surface);
  border: 1px solid var(--divider);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  text-align: center;
}
.title {
  font-weight: 900;
  font-size: 18px;
}
.sub {
  margin-top: 8px;
  color: var(--text-2);
  font-size: 13px;
}
</style>

