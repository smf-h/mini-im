<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiGet, apiPost } from '../services/api'
import type {
  GroupJoinRequestEntity,
  GroupJoinRequestResponse,
  GroupMemberDto,
  GroupProfileDto,
  MemberRole,
  ResetGroupCodeResponse,
} from '../types/api'
import { useGroupStore } from '../stores/groups'
import { useUserStore } from '../stores/users'
import UiAvatar from '../components/UiAvatar.vue'

const route = useRoute()
const router = useRouter()
const groups = useGroupStore()
const users = useUserStore()

const groupId = computed(() => String(route.params.groupId ?? ''))
const loading = ref(false)
const errorMsg = ref<string | null>(null)
const statusMsg = ref<string | null>(null)

const profile = ref<GroupProfileDto | null>(null)
const members = ref<GroupMemberDto[]>([])
const joinRequests = ref<GroupJoinRequestEntity[]>([])

const joinMessage = ref('你好，我想加入该群')

const myRole = computed<MemberRole | null>(() => profile.value?.myRole ?? null)
const canManage = computed(() => myRole.value === 'OWNER' || myRole.value === 'ADMIN')
const isOwner = computed(() => myRole.value === 'OWNER')

function goBack() {
  if (window.history.length > 1) {
    router.back()
    return
  }
  void router.push('/groups')
}

async function load() {
  loading.value = true
  errorMsg.value = null
  statusMsg.value = null
  members.value = []
  joinRequests.value = []
  try {
    profile.value = await apiGet<GroupProfileDto>(`/group/profile/by-id?groupId=${encodeURIComponent(groupId.value)}`)
    if (profile.value?.groupId) {
      groups.upsertBasics([{ id: profile.value.groupId, name: profile.value.name }])
    }

    if (profile.value?.isMember) {
      members.value = await apiGet<GroupMemberDto[]>(`/group/member/list?groupId=${encodeURIComponent(groupId.value)}`)
      void users.ensureBasics(members.value.map((m) => m.userId))
    }

    if (profile.value?.isMember && canManage.value) {
      joinRequests.value = await apiGet<GroupJoinRequestEntity[]>(
        `/group/join/requests?groupId=${encodeURIComponent(groupId.value)}&status=pending&limit=50`,
      )
      void users.ensureBasics(joinRequests.value.map((r) => r.fromUserId))
    }
  } catch (e) {
    errorMsg.value = String(e)
  } finally {
    loading.value = false
  }
}

async function requestJoin() {
  statusMsg.value = null
  errorMsg.value = null
  const code = (profile.value?.groupCode ?? '').trim()
  if (!code) {
    statusMsg.value = '群码为空'
    return
  }
  try {
    await apiPost<GroupJoinRequestResponse>(`/group/join/request`, { groupCode: code, message: joinMessage.value })
    statusMsg.value = '已提交申请，等待群主/管理员审批'
  } catch (e) {
    statusMsg.value = String(e)
  }
}

async function decideJoin(requestId: string, action: 'accept' | 'reject') {
  statusMsg.value = null
  errorMsg.value = null
  try {
    await apiPost(`/group/join/decide`, { requestId, action })
    statusMsg.value = action === 'accept' ? '已同意' : '已拒绝'
    await load()
  } catch (e) {
    statusMsg.value = String(e)
  }
}

async function kick(userId: string) {
  statusMsg.value = null
  errorMsg.value = null
  try {
    await apiPost(`/group/member/kick`, { groupId: groupId.value, userId })
    statusMsg.value = '已踢出'
    await load()
  } catch (e) {
    statusMsg.value = String(e)
  }
}

async function setAdmin(userId: string, admin: boolean) {
  statusMsg.value = null
  errorMsg.value = null
  try {
    await apiPost(`/group/member/set-admin`, { groupId: groupId.value, userId, admin })
    statusMsg.value = admin ? '已设为管理员' : '已取消管理员'
    await load()
  } catch (e) {
    statusMsg.value = String(e)
  }
}

async function transferOwner(newOwnerUserId: string) {
  statusMsg.value = null
  errorMsg.value = null
  try {
    await apiPost(`/group/owner/transfer`, { groupId: groupId.value, newOwnerUserId })
    statusMsg.value = '已转让群主'
    await load()
  } catch (e) {
    statusMsg.value = String(e)
  }
}

async function resetGroupCode() {
  statusMsg.value = null
  errorMsg.value = null
  try {
    const resp = await apiPost<ResetGroupCodeResponse>(`/group/code/reset`, { groupId: groupId.value })
    if (profile.value) profile.value.groupCode = resp.groupCode
    statusMsg.value = '群码已重置'
  } catch (e) {
    statusMsg.value = String(e)
  }
}

async function leave() {
  statusMsg.value = null
  errorMsg.value = null
  try {
    await apiPost(`/group/leave`, { groupId: groupId.value })
    statusMsg.value = '已退出该群'
    void router.push('/groups')
  } catch (e) {
    statusMsg.value = String(e)
  }
}

function canKick(targetRole: MemberRole) {
  if (!canManage.value) return false
  if (myRole.value === 'OWNER') return targetRole !== 'OWNER'
  if (myRole.value === 'ADMIN') return targetRole === 'MEMBER'
  return false
}

function canToggleAdmin(targetRole: MemberRole) {
  return isOwner.value && targetRole !== 'OWNER'
}

onMounted(() => void load())
</script>

<template>
  <div class="card" style="padding: 14px">
    <div class="row" style="justify-content: space-between; margin-bottom: 10px">
      <h2 style="margin: 0">群资料</h2>
      <div class="row">
        <button class="btn" @click="load">刷新</button>
        <button class="btn" @click="goBack">返回</button>
      </div>
    </div>

    <div v-if="loading" class="muted">加载中…</div>
    <div v-else-if="profile" class="card" style="padding: 12px">
      <div class="row" style="justify-content: space-between; align-items: flex-start">
        <div class="row" style="gap: 12px">
          <UiAvatar :text="profile.name" :seed="profile.groupId" :size="56" />
          <div style="display: grid; gap: 4px">
            <div style="font-weight: 850; font-size: 18px">{{ profile.name }}</div>
            <div class="muted" style="font-size: 12px">groupId={{ profile.groupId }}</div>
            <div class="muted" style="font-size: 12px">成员数={{ profile.memberCount ?? '—' }}</div>
            <div class="muted" style="font-size: 12px">我的角色={{ profile.myRole ?? '—' }}</div>
          </div>
        </div>
        <div style="text-align: right">
          <div class="muted" style="font-size: 12px">GroupCode</div>
          <div class="code">{{ profile.groupCode || '—' }}</div>
        </div>
      </div>

      <div class="row" style="margin-top: 12px; justify-content: flex-end">
        <button v-if="profile.isMember" class="btn" @click="leave">退出群</button>
        <button v-if="profile.isMember && canManage" class="btn" @click="resetGroupCode">重置群码</button>
        <button v-if="!profile.isMember" class="btn primary" @click="requestJoin">申请加入</button>
      </div>

      <div v-if="!profile.isMember" style="margin-top: 10px">
        <div class="muted" style="font-size: 12px; margin-bottom: 6px">验证信息</div>
        <input v-model="joinMessage" class="input" />
      </div>
    </div>

    <div v-if="profile?.isMember" class="card" style="padding: 12px; margin-top: 12px">
      <div class="row" style="justify-content: space-between">
        <div style="font-weight: 850">成员</div>
        <div class="muted" style="font-size: 12px">点击头像查看个人主页</div>
      </div>
      <div style="display: grid; gap: 10px; margin-top: 10px">
        <div v-for="m in members" :key="m.userId" class="row" style="justify-content: space-between">
          <div class="row" style="gap: 10px">
            <button class="avatarBtn" type="button" @click="router.push(`/u/${m.userId}`)">
              <UiAvatar :text="m.nickname ?? m.username ?? m.userId" :seed="m.userId" :size="40" />
            </button>
            <div style="display: grid; gap: 2px">
              <div style="font-weight: 750">{{ m.nickname ?? m.username ?? m.userId }}</div>
              <div class="muted" style="font-size: 12px">uid={{ m.userId }} · role={{ m.role }}</div>
            </div>
          </div>

          <div class="row" style="gap: 10px">
            <button
              v-if="canToggleAdmin(m.role)"
              class="btn"
              @click="setAdmin(m.userId, m.role !== 'ADMIN')"
            >
              {{ m.role === 'ADMIN' ? '取消管理员' : '设为管理员' }}
            </button>
            <button v-if="isOwner && m.role !== 'OWNER'" class="btn" @click="transferOwner(m.userId)">转让群主</button>
            <button v-if="canKick(m.role)" class="btn danger" @click="kick(m.userId)">踢出</button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="profile?.isMember && canManage" class="card" style="padding: 12px; margin-top: 12px">
      <div class="row" style="justify-content: space-between">
        <div style="font-weight: 850">入群申请（待处理）</div>
        <button class="btn" @click="load">刷新</button>
      </div>
      <div v-if="!joinRequests.length" class="muted" style="margin-top: 10px">暂无</div>
      <div v-else style="display: grid; gap: 10px; margin-top: 10px">
        <div v-for="r in joinRequests" :key="r.id" class="card" style="padding: 10px">
          <div class="row" style="justify-content: space-between">
            <div class="row" style="gap: 10px">
              <button class="avatarBtn" type="button" @click="router.push(`/u/${r.fromUserId}`)">
                <UiAvatar :text="users.displayName(r.fromUserId)" :seed="r.fromUserId" :size="36" />
              </button>
              <div>
                <div style="font-weight: 750">{{ users.displayName(r.fromUserId) }}</div>
                <div class="muted" style="font-size: 12px">uid={{ r.fromUserId }} · #{{ r.id }}</div>
              </div>
            </div>
            <div class="row" style="gap: 10px">
              <button class="btn" @click="decideJoin(r.id, 'reject')">拒绝</button>
              <button class="btn primary" @click="decideJoin(r.id, 'accept')">同意</button>
            </div>
          </div>
          <div v-if="r.message" class="muted" style="margin-top: 8px; white-space: pre-wrap">{{ r.message }}</div>
        </div>
      </div>
    </div>

    <div v-if="statusMsg" class="muted" style="margin-top: 10px">{{ statusMsg }}</div>
    <div v-if="errorMsg" class="muted" style="color: var(--danger); margin-top: 10px">{{ errorMsg }}</div>
  </div>
</template>

<style scoped>
.code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
  font-weight: 900;
  letter-spacing: 0.8px;
  color: rgba(17, 17, 17, 0.85);
}
.avatarBtn {
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}
</style>
