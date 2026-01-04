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
import { useAuthStore } from '../stores/auth'
import { useGroupStore } from '../stores/groups'
import { useUserStore } from '../stores/users'
import UiAvatar from '../components/UiAvatar.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const groups = useGroupStore()
const users = useUserStore()

auth.hydrateFromStorage()

const groupId = computed(() => String(route.params.groupId ?? ''))
const loading = ref(false)
const errorMsg = ref<string | null>(null)
const statusMsg = ref<string | null>(null)

const profile = ref<GroupProfileDto | null>(null)
const members = ref<GroupMemberDto[]>([])
const joinRequests = ref<GroupJoinRequestEntity[]>([])

const joinMessage = ref('你好，我想加入该群')
const memberMenuUserId = ref<string | null>(null)

const myRole = computed<MemberRole | null>(() => profile.value?.myRole ?? null)
const canManage = computed(() => myRole.value === 'OWNER' || myRole.value === 'ADMIN')
const isOwner = computed(() => myRole.value === 'OWNER')
const myUserId = computed(() => auth.userId ?? null)

function toTs(v?: string | null) {
  if (!v) return null
  const t = new Date(v).getTime()
  if (!Number.isFinite(t)) return null
  return t
}

function isSpeakMuted(m: GroupMemberDto) {
  const t = toTs(m.speakMuteUntil ?? null)
  return t != null && t > Date.now()
}

function speakMuteLabel(m: GroupMemberDto) {
  const t = toTs(m.speakMuteUntil ?? null)
  if (t == null || t <= Date.now()) return null
  const d = new Date(t)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `禁言至 ${hh}:${mm}`
}

function goBack() {
  if (window.history.length > 1) {
    router.back()
    return
  }
  void router.push('/contacts/groups')
}

async function copyText(text: string, okMsg: string) {
  statusMsg.value = null
  errorMsg.value = null
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      statusMsg.value = okMsg
      return
    }
  } catch {
    // ignore and fallback
  }
  try {
    const el = document.createElement('textarea')
    el.value = text
    el.setAttribute('readonly', '')
    el.style.position = 'fixed'
    el.style.left = '-9999px'
    el.style.top = '0'
    document.body.appendChild(el)
    el.select()
    document.execCommand('copy')
    document.body.removeChild(el)
    statusMsg.value = okMsg
  } catch (e) {
    errorMsg.value = String(e)
  }
}

function copyGroupId() {
  const id = profile.value?.groupId ?? groupId.value
  void copyText(String(id), '已复制群号')
}

function copyGroupCode() {
  const code = (profile.value?.groupCode ?? '').trim()
  if (!code) {
    statusMsg.value = '群码为空'
    return
  }
  void copyText(code, '已复制群码，可发给好友申请入群')
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
    memberMenuUserId.value = null
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
    memberMenuUserId.value = null
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
    memberMenuUserId.value = null
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
    memberMenuUserId.value = null
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
    memberMenuUserId.value = null
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
    memberMenuUserId.value = null
    await apiPost(`/group/leave`, { groupId: groupId.value })
    statusMsg.value = '已退出该群'
    void router.push('/contacts/groups')
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

function canMute(targetUserId: string, targetRole: MemberRole) {
  if (!canManage.value) return false
  if (myUserId.value && String(targetUserId) === String(myUserId.value)) return false
  if (myRole.value === 'OWNER') return targetRole !== 'OWNER'
  if (myRole.value === 'ADMIN') return targetRole === 'MEMBER'
  return false
}

async function muteMember(userId: string, durationSeconds: number) {
  statusMsg.value = null
  errorMsg.value = null
  try {
    memberMenuUserId.value = null
    await apiPost(`/group/member/mute`, { groupId: groupId.value, userId, durationSeconds })
    statusMsg.value = durationSeconds === 0 ? '已解除禁言' : '已设置禁言'
    await load()
  } catch (e) {
    errorMsg.value = String(e)
  }
}

function toggleMemberMenu(userId: string) {
  memberMenuUserId.value = memberMenuUserId.value === userId ? null : userId
}

onMounted(() => void load())
</script>

<template>
  <div class="wrap">
    <div class="panel">
      <header class="header">
        <div class="headerLeft">
          <div class="title">{{ profile?.name ?? '群资料' }}</div>
          <div class="metaRow">
            <div class="metaText">群号：{{ profile?.groupId ?? groupId }}</div>
            <button class="miniLink" type="button" @click="copyGroupId">复制</button>
          </div>
        </div>
        <div class="headerRight">
          <button class="btn" @click="load">刷新</button>
          <button class="btn" @click="goBack">返回</button>
          <button v-if="profile?.isMember" class="btn dangerOutline" @click="leave">退出群</button>
        </div>
      </header>

      <div v-if="loading" class="muted" style="padding: 14px 16px">加载中…</div>
      <div v-else-if="profile" class="content">
        <section class="hero">
          <UiAvatar :text="profile.name" :seed="profile.groupId" :size="64" />
          <div class="heroMain">
            <div class="heroTitle">{{ profile.name }}</div>
            <div class="heroSub muted">成员 {{ profile.memberCount ?? '—' }} · 我的角色 {{ profile.myRole ?? '—' }}</div>
          </div>
          <div class="heroRight">
            <div class="muted" style="font-size: 12px">群码</div>
            <div class="codeRow">
              <div class="code">{{ profile.groupCode || '—' }}</div>
              <button class="miniBtn" type="button" @click="copyGroupCode">复制</button>
            </div>
            <div class="row" style="justify-content: flex-end; gap: 10px; margin-top: 10px">
              <button v-if="profile.isMember && canManage" class="btn" @click="resetGroupCode">重置群码</button>
              <button v-if="!profile.isMember" class="btn primary" @click="requestJoin">申请加入</button>
            </div>
          </div>
        </section>

        <section v-if="!profile.isMember" class="joinBox">
          <div class="muted" style="font-size: 12px; margin-bottom: 6px">验证信息</div>
          <input v-model="joinMessage" class="input" />
        </section>

        <section v-if="profile.isMember" class="section">
          <div class="sectionTop">
            <div class="sectionTitle">成员</div>
            <div class="muted" style="font-size: 12px">5-6 列网格，点击头像看主页</div>
          </div>
          <div class="memberGrid">
            <div
              v-for="m in members"
              :key="m.userId"
              class="memberTile"
            >
              <button class="memberOpen" type="button" @click="router.push(`/contacts/u/${m.userId}`)">
                <UiAvatar :text="m.nickname ?? m.username ?? m.userId" :seed="m.userId" :size="46" />
                <div class="memberName">{{ m.nickname ?? m.username ?? m.userId }}</div>
                <div class="memberRole muted">{{ m.role }}</div>
                <div v-if="isSpeakMuted(m)" class="muteTag">禁言中</div>
              </button>
              <button
                v-if="canKick(m.role) || canToggleAdmin(m.role) || (isOwner && m.role !== 'OWNER') || canMute(m.userId, m.role)"
                class="moreBtn"
                type="button"
                aria-label="成员操作"
                @click.stop="toggleMemberMenu(m.userId)"
              >
                ⋯
              </button>
              <div v-if="memberMenuUserId === m.userId" class="memberMenu">
                <div v-if="speakMuteLabel(m)" class="menuHint muted">{{ speakMuteLabel(m) }}</div>
                <button
                  v-if="canMute(m.userId, m.role) && isSpeakMuted(m)"
                  class="menuItem"
                  type="button"
                  @click="muteMember(m.userId, 0)"
                >
                  解除禁言
                </button>
                <button v-if="canMute(m.userId, m.role)" class="menuItem" type="button" @click="muteMember(m.userId, 600)">
                  禁言 10 分钟
                </button>
                <button v-if="canMute(m.userId, m.role)" class="menuItem" type="button" @click="muteMember(m.userId, 3600)">
                  禁言 1 小时
                </button>
                <button v-if="canMute(m.userId, m.role)" class="menuItem" type="button" @click="muteMember(m.userId, 86400)">
                  禁言 1 天
                </button>
                <button v-if="canMute(m.userId, m.role)" class="menuItem" type="button" @click="muteMember(m.userId, -1)">
                  永久禁言
                </button>
                <button v-if="canToggleAdmin(m.role)" class="menuItem" type="button" @click="setAdmin(m.userId, m.role !== 'ADMIN')">
                  {{ m.role === 'ADMIN' ? '取消管理员' : '设为管理员' }}
                </button>
                <button v-if="isOwner && m.role !== 'OWNER'" class="menuItem" type="button" @click="transferOwner(m.userId)">
                  转让群主
                </button>
                <button v-if="canKick(m.role)" class="menuItem danger" type="button" @click="kick(m.userId)">
                  踢出
                </button>
              </div>
            </div>

            <button v-if="profile.groupCode" class="memberTile addTile" type="button" @click="copyGroupCode">
              <div class="addCircle">+</div>
              <div class="memberName">邀请</div>
              <div class="memberRole muted">复制群码</div>
            </button>
          </div>
        </section>

        <section v-if="profile.isMember && canManage" class="section">
          <div class="sectionTop">
            <div class="sectionTitle">入群申请（待处理）</div>
            <div class="muted" style="font-size: 12px">{{ joinRequests.length ? `${joinRequests.length} 条` : '暂无' }}</div>
          </div>
          <div class="reqList">
            <div v-if="!joinRequests.length" class="muted" style="padding: 10px 0">暂无</div>
            <div v-else class="reqRow" v-for="r in joinRequests" :key="r.id">
              <button class="avatarBtn" type="button" @click="router.push(`/contacts/u/${r.fromUserId}`)">
                <UiAvatar :text="users.displayName(r.fromUserId)" :seed="r.fromUserId" :size="40" />
              </button>
              <div class="reqMain">
                <div class="reqName">{{ users.displayName(r.fromUserId) }}</div>
                <div v-if="r.message" class="reqMsg">{{ r.message }}</div>
              </div>
              <div class="reqActions">
                <button class="btnSmall" type="button" @click="decideJoin(r.id, 'reject')">拒绝</button>
                <button class="btnSmall primary" type="button" @click="decideJoin(r.id, 'accept')">同意</button>
              </div>
            </div>
          </div>
        </section>

        <div v-if="statusMsg" class="muted" style="margin-top: 12px">{{ statusMsg }}</div>
        <div v-if="errorMsg" class="muted" style="color: var(--danger); margin-top: 12px">{{ errorMsg }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wrap {
  height: 100%;
  overflow: auto;
  padding: 16px;
  background: var(--bg);
}
.panel {
  background: var(--bg-main, var(--surface));
  border: 1px solid var(--divider);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}
.header {
  padding: 14px 16px;
  background: var(--surface);
  border-bottom: 1px solid var(--divider);
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.headerLeft {
  min-width: 0;
}
.headerRight {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.title {
  font-weight: 900;
  font-size: 20px;
  line-height: 1.15;
}
.metaRow {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.metaText {
  font-size: 12px;
  color: rgba(17, 17, 17, 0.55);
}
.miniLink {
  border: 0;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: rgba(7, 193, 96, 0.92);
  font-weight: 800;
}
.dangerOutline {
  border-color: rgba(250, 81, 81, 0.28);
  background: rgba(250, 81, 81, 0.06);
  color: rgba(250, 81, 81, 0.92);
}
.content {
  padding: 14px 16px 16px;
}
.hero {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 14px;
  align-items: center;
  padding: 14px 12px;
  border-radius: 16px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(0, 0, 0, 0.015);
}
.heroMain {
  min-width: 0;
}
.heroTitle {
  font-weight: 900;
  font-size: 18px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.heroSub {
  margin-top: 4px;
  font-size: 12px;
}
.heroRight {
  text-align: right;
}
.codeRow {
  margin-top: 4px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}
.code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
  font-weight: 900;
  letter-spacing: 0.8px;
  color: rgba(17, 17, 17, 0.85);
}
.miniBtn {
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(255, 255, 255, 0.92);
  height: 26px;
  padding: 0 10px;
  border-radius: 999px;
  cursor: pointer;
  font-size: 12px;
  color: rgba(17, 17, 17, 0.72);
}
.miniBtn:hover {
  background: #ffffff;
}
.joinBox {
  margin-top: 12px;
  padding: 12px;
  border-radius: 16px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(255, 255, 255, 0.7);
}
.section {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}
.sectionTop {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}
.sectionTitle {
  font-weight: 900;
}
.memberGrid {
  margin-top: 10px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(86px, 1fr));
  gap: 10px;
}
.memberTile {
  position: relative;
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(255, 255, 255, 0.92);
  border-radius: 14px;
  padding: 10px 8px 9px;
  display: grid;
}
.memberTile:hover {
  background: #ffffff;
}
.memberOpen {
  width: 100%;
  border: 0;
  background: transparent;
  cursor: pointer;
  display: grid;
  justify-items: center;
  gap: 6px;
  padding: 0;
}
.memberName {
  width: 100%;
  text-align: center;
  font-weight: 800;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.memberRole {
  font-size: 11px;
}
.moreBtn {
  position: absolute;
  right: 6px;
  top: 6px;
  width: 24px;
  height: 24px;
  border-radius: 10px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(255, 255, 255, 0.88);
  cursor: pointer;
  display: none;
  color: rgba(17, 17, 17, 0.7);
  font-size: 16px;
  line-height: 22px;
}
.memberTile:hover .moreBtn {
  display: block;
}
.memberMenu {
  position: absolute;
  right: 6px;
  top: 34px;
  width: 140px;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(12px);
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.18);
  overflow: hidden;
  z-index: 5;
}
.menuHint {
  padding: 8px 10px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  font-size: 12px;
}
.menuItem {
  width: 100%;
  text-align: left;
  padding: 9px 10px;
  border: 0;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: rgba(15, 23, 42, 0.9);
}
.menuItem:hover {
  background: rgba(0, 0, 0, 0.03);
}
.menuItem.danger {
  color: rgba(250, 81, 81, 0.92);
}
.addTile {
  border-style: dashed;
  background: rgba(255, 255, 255, 0.65);
}
.addCircle {
  width: 46px;
  height: 46px;
  border-radius: 999px;
  border: 2px dashed rgba(0, 0, 0, 0.18);
  display: grid;
  place-items: center;
  color: rgba(17, 17, 17, 0.62);
  font-size: 22px;
  line-height: 1;
}
.muteTag {
  margin-top: 6px;
  font-size: 11px;
  font-weight: 900;
  color: rgba(250, 81, 81, 0.92);
  background: rgba(250, 81, 81, 0.08);
  border: 1px solid rgba(250, 81, 81, 0.18);
  padding: 0 8px;
  height: 18px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
}
.reqList {
  margin-top: 10px;
  display: grid;
  gap: 10px;
}
.reqRow {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 10px;
  align-items: flex-start;
  padding: 10px 10px;
  border-radius: 14px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(255, 255, 255, 0.9);
}
.reqMain {
  min-width: 0;
  display: grid;
  gap: 4px;
}
.reqName {
  font-weight: 850;
}
.reqMeta {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.55);
}
.reqMsg {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.72);
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.reqActions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.btnSmall {
  border: 1px solid rgba(17, 17, 17, 0.16);
  background: rgba(255, 255, 255, 0.92);
  padding: 0 10px;
  height: 28px;
  border-radius: 999px;
  cursor: pointer;
  font-size: 13px;
  color: rgba(17, 17, 17, 0.78);
}
.btnSmall.primary {
  border-color: rgba(7, 193, 96, 0.32);
  background: var(--primary);
  color: #ffffff;
  font-weight: 800;
}
.btnSmall.primary:hover {
  background: var(--primary-hover, #06ad56);
}
.avatarBtn {
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}
</style>
