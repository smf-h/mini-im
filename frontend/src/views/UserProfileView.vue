<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiGet, apiPost } from '../services/api'
import type { CreateFriendRequestByCodeResponse, MeProfileDto, ResetFriendCodeResponse, UserProfileDto } from '../types/api'
import { useAuthStore } from '../stores/auth'
import UiAvatar from '../components/UiAvatar.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const userId = computed(() => String(route.params.userId ?? ''))
const isMe = computed(() => auth.userId && userId.value === auth.userId)

const loading = ref(false)
const errorMsg = ref<string | null>(null)

const profile = ref<UserProfileDto | null>(null)
const me = ref<MeProfileDto | null>(null)

const requestMsg = ref('你好，我想加你好友')
const sendStatus = ref<string | null>(null)

async function load() {
  loading.value = true
  errorMsg.value = null
  sendStatus.value = null
  profile.value = null
  me.value = null
  try {
    if (!auth.userId) return
    if (isMe.value) {
      me.value = await apiGet<MeProfileDto>(`/me/profile`)
      profile.value = me.value as unknown as UserProfileDto
    } else {
      profile.value = await apiGet<UserProfileDto>(`/user/profile?userId=${encodeURIComponent(userId.value)}`)
    }
  } catch (e) {
    errorMsg.value = String(e)
  } finally {
    loading.value = false
  }
}

async function sendFriendRequest() {
  sendStatus.value = null
  errorMsg.value = null
  const code = (profile.value?.friendCode ?? '').trim()
  if (!code) {
    sendStatus.value = '对方未提供 FriendCode'
    return
  }
  try {
    await apiPost<CreateFriendRequestByCodeResponse>(`/friend/request/by-code`, {
      toFriendCode: code,
      message: requestMsg.value,
    })
    sendStatus.value = '已发送'
  } catch (e) {
    sendStatus.value = String(e)
  }
}

async function resetFriendCode() {
  sendStatus.value = null
  errorMsg.value = null
  try {
    const resp = await apiPost<ResetFriendCodeResponse>(`/me/friend-code/reset`, {})
    if (me.value) {
      me.value.friendCode = resp.friendCode
      me.value.friendCodeUpdatedAt = resp.friendCodeUpdatedAt ?? me.value.friendCodeUpdatedAt
      me.value.friendCodeNextResetAt = resp.friendCodeNextResetAt ?? me.value.friendCodeNextResetAt
    }
    if (profile.value) {
      profile.value.friendCode = resp.friendCode
    }
    sendStatus.value = '已重置'
  } catch (e) {
    sendStatus.value = String(e)
  }
}

function goBack() {
  if (window.history.length > 1) {
    router.back()
    return
  }
  void router.push('/conversations')
}

watch(
  () => userId.value,
  () => void load(),
)

onMounted(() => void load())
</script>

<template>
  <div class="card" style="padding: 14px">
    <div class="row" style="justify-content: space-between; margin-bottom: 10px">
      <h2 style="margin: 0">{{ isMe ? '我的主页' : '个人主页' }}</h2>
      <button class="btn" @click="goBack">返回</button>
    </div>

    <div v-if="loading" class="muted">加载中…</div>
    <div v-else-if="profile" class="profile">
      <UiAvatar :text="profile.nickname ?? profile.username ?? profile.id" :seed="profile.id" :size="68" />
      <div class="info">
        <div class="name">{{ profile.nickname || profile.username || profile.id }}</div>
        <div class="meta muted">@{{ profile.username }}</div>
        <div class="meta muted">uid={{ profile.id }}</div>
      </div>
    </div>

    <div v-if="profile" class="card" style="padding: 12px; margin-top: 12px">
      <div class="row" style="justify-content: space-between">
        <div>
          <div style="font-weight: 750">FriendCode</div>
          <div class="muted" style="font-size: 12px">用于加好友（不可枚举，可重置限频）</div>
        </div>
        <div class="code">{{ profile.friendCode || '—' }}</div>
      </div>

      <div v-if="isMe && me" class="muted" style="margin-top: 8px; font-size: 12px">
        <div>上次更新：{{ me.friendCodeUpdatedAt || '—' }}</div>
        <div>下次可重置：{{ me.friendCodeNextResetAt || '—' }}</div>
      </div>

      <div class="row" style="margin-top: 10px; justify-content: flex-end">
        <button v-if="isMe" class="btn" @click="resetFriendCode">重置 FriendCode</button>
        <button v-else class="btn primary" @click="sendFriendRequest">申请好友</button>
      </div>

      <div v-if="!isMe" style="margin-top: 10px">
        <div class="muted" style="font-size: 12px; margin-bottom: 6px">验证信息</div>
        <input v-model="requestMsg" class="input" />
      </div>

      <div v-if="sendStatus" class="muted" style="margin-top: 10px">{{ sendStatus }}</div>
    </div>

    <div v-if="errorMsg" class="muted" style="color: var(--danger); margin-top: 10px">{{ errorMsg }}</div>
  </div>
</template>

<style scoped>
.profile {
  display: flex;
  align-items: center;
  gap: 12px;
}
.info {
  min-width: 0;
  display: grid;
  gap: 4px;
}
.name {
  font-weight: 850;
  font-size: 18px;
}
.meta {
  font-size: 12px;
}
.code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
  font-weight: 900;
  letter-spacing: 0.8px;
  color: rgba(17, 17, 17, 0.85);
}
</style>

