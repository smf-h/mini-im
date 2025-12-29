<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import UiAvatar from '../components/UiAvatar.vue'
import UiListItem from '../components/UiListItem.vue'
import GlobalActionMenu from '../components/GlobalActionMenu.vue'
import { apiGet, apiPost } from '../services/api'
import type { CreateFriendRequestByCodeResponse, FriendRelationEntity, FriendRequestEntity, Id } from '../types/api'
import type { WsEnvelope } from '../types/ws'
import { useAuthStore } from '../stores/auth'
import { useWsStore } from '../stores/ws'
import { useUserStore } from '../stores/users'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ws = useWsStore()
const users = useUserStore()

const friends = ref<Id[]>([])
const friendsLoading = ref(false)
const friendsError = ref<string | null>(null)

const hasPendingFriendRequests = ref(false)
const wsCursor = ref(0)

const showAddFriend = ref(false)
const actionOpen = ref(false)
const actionWrapEl = ref<HTMLElement | null>(null)
const addFriendCode = ref('')
const addFriendMsg = ref('你好，我想加你好友')
const addFriendStatus = ref<string | null>(null)

function friendIdOf(r: FriendRelationEntity) {
  if (!auth.userId) return String(r.user1Id)
  const u1 = String(r.user1Id)
  const u2 = String(r.user2Id)
  return u1 === auth.userId ? u2 : u1
}

async function loadFriends() {
  if (!auth.userId) return
  friendsLoading.value = true
  friendsError.value = null
  try {
    const list = await apiGet<FriendRelationEntity[]>(`/friend/relation/list`)
    const ids = list.map(friendIdOf)
    friends.value = Array.from(new Set(ids))
    void users.ensureBasics(friends.value)
  } catch (e) {
    friendsError.value = String(e)
  } finally {
    friendsLoading.value = false
  }
}

async function refreshPendingFriendRequests() {
  if (!auth.userId) return
  try {
    const qs = new URLSearchParams()
    qs.set('box', 'inbox')
    qs.set('limit', '50')
    const list = await apiGet<FriendRequestEntity[]>(`/friend/request/cursor?${qs.toString()}`)
    hasPendingFriendRequests.value = list.some((x) => x.status === 'PENDING')
  } catch {
    // ignore
  }
}

function openAddFriend() {
  addFriendStatus.value = null
  showAddFriend.value = true
  addFriendCode.value = ''
}

function closeAddFriend() {
  showAddFriend.value = false
}

async function sendAddFriend() {
  addFriendStatus.value = null
  const code = addFriendCode.value.trim().toUpperCase()
  if (!/^[A-Z0-9]{6,16}$/.test(code)) {
    addFriendStatus.value = 'FriendCode 无效'
    return
  }
  try {
    addFriendStatus.value = '发送中…'
    await apiPost<CreateFriendRequestByCodeResponse>(`/friend/request/by-code`, {
      toFriendCode: code,
      message: addFriendMsg.value,
    })
    addFriendStatus.value = '已发送'
    hasPendingFriendRequests.value = true
    void router.push('/contacts/new-friends')
    closeAddFriend()
  } catch (e) {
    addFriendStatus.value = String(e)
  }
}

function onGlobalPointerDown(e: PointerEvent) {
  if (!actionOpen.value) return
  const el = actionWrapEl.value
  if (!el) return
  if (e.target instanceof Node && el.contains(e.target)) return
  actionOpen.value = false
}

function applyWsEvent(ev: WsEnvelope) {
  if (ev.type === 'FRIEND_REQUEST') {
    if (!ev.to || ev.to !== auth.userId) return
    hasPendingFriendRequests.value = true
  }
}

watch(
  () => ws.events.length,
  () => {
    for (let i = wsCursor.value; i < ws.events.length; i++) {
      applyWsEvent(ws.events[i]!)
    }
    wsCursor.value = ws.events.length
  },
)

onMounted(() => {
  wsCursor.value = ws.events.length
  void ws.connect()
  void loadFriends()
  void refreshPendingFriendRequests()
  window.addEventListener('pointerdown', onGlobalPointerDown, true)
})

onUnmounted(() => {
  window.removeEventListener('pointerdown', onGlobalPointerDown, true)
})

watch(
  () => route.query.action,
  () => {
    if (route.query.action === 'add-friend') {
      openAddFriend()
      return
    }
    actionOpen.value = false
  },
)
</script>

<template>
  <div class="module">
    <aside class="listPanel">
      <div class="panelTop">
        <div class="panelTitle">通讯录</div>
        <div ref="actionWrapEl" class="actionsWrap">
          <button class="plusBtn" type="button" aria-label="添加" @click="actionOpen = !actionOpen">+</button>
          <GlobalActionMenu :open="actionOpen" @close="actionOpen = false" />
        </div>
      </div>

      <div class="section">
        <UiListItem to="/contacts/new-friends">
          <template #left>
            <div class="iconPill" aria-hidden="true">N</div>
          </template>
          <div class="navTitle">
            新的朋友
            <span v-if="hasPendingFriendRequests" class="dot" aria-label="有未处理请求"></span>
          </div>
        </UiListItem>
        <UiListItem to="/contacts/groups">
          <template #left>
            <div class="iconPill" aria-hidden="true">G</div>
          </template>
          <div class="navTitle">我的群组</div>
        </UiListItem>
      </div>

      <div class="friendsHeader">
        <div class="muted">好友</div>
        <button class="textBtn" type="button" @click="loadFriends">刷新</button>
      </div>

      <div class="friendsList">
        <div v-if="friendsLoading" class="tail muted">加载中…</div>
        <div v-else-if="friendsError" class="tail error">{{ friendsError }}</div>
        <UiListItem v-for="fid in friends" :key="fid" :to="`/contacts/u/${fid}`">
          <template #left>
            <UiAvatar :text="users.displayName(fid)" :seed="fid" :size="40" />
          </template>
          <div class="friendName">{{ users.displayName(fid) }}</div>
        </UiListItem>
        <div v-if="!friendsLoading && !friendsError && friends.length === 0" class="tail muted">暂无好友</div>
      </div>
    </aside>

    <section class="stage">
      <RouterView />
    </section>

    <div v-if="showAddFriend" class="modalMask" @click.self="closeAddFriend">
      <div class="modalCard" role="dialog" aria-modal="true" aria-label="添加好友">
        <div class="modalTitle">添加好友</div>
        <div class="muted" style="font-size: 12px; margin-top: 4px">使用 FriendCode + 验证信息发起申请。</div>

        <div class="field">
          <div class="label muted">FriendCode</div>
          <input v-model="addFriendCode" class="input" placeholder="例如 1C2A3B…" />
        </div>
        <div class="field">
          <div class="label muted">验证信息</div>
          <input v-model="addFriendMsg" class="input" />
        </div>

        <div v-if="addFriendStatus" class="muted" style="margin-top: 10px">{{ addFriendStatus }}</div>

        <div class="modalActions">
          <button class="btn" type="button" @click="closeAddFriend">取消</button>
          <button class="btn primary" type="button" @click="sendAddFriend">发送</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.module {
  height: 100%;
  display: flex;
  background: var(--bg);
}
.listPanel {
  width: 260px;
  flex: none;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--divider);
  background: var(--bg-list);
  overflow: hidden;
}
.panelTop {
  padding: 14px 14px 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border-bottom: 1px solid var(--divider);
}
.panelTitle {
  font-weight: 900;
  font-size: 16px;
}
.plusBtn {
  width: 32px;
  height: 32px;
  border-radius: 12px;
  border: 1px solid var(--divider);
  background: rgba(7, 193, 96, 0.12);
  color: rgba(7, 193, 96, 0.92);
  cursor: pointer;
  font-weight: 900;
}
.actionsWrap {
  position: relative;
}
.section {
  border-bottom: 1px solid var(--divider);
}
.iconPill {
  width: 32px;
  height: 32px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  font-weight: 900;
  color: rgba(15, 23, 42, 0.82);
  background: rgba(15, 23, 42, 0.06);
  border: 1px solid rgba(0, 0, 0, 0.06);
}
.navTitle {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 750;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: var(--badge-red);
  border: 1px solid #ffffff;
}
.friendsHeader {
  padding: 10px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border-bottom: 1px solid var(--divider);
}
.textBtn {
  border: 0;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: rgba(7, 193, 96, 0.9);
  font-weight: 750;
}
.friendsList {
  flex: 1;
  overflow: auto;
}
.friendName {
  font-weight: 750;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tail {
  padding: 10px 12px;
  text-align: center;
  font-size: 12px;
}
.tail.error {
  color: var(--danger);
}
.stage {
  flex: 1;
  min-width: 0;
  height: 100%;
  overflow: hidden;
}
.modalMask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.35);
  display: grid;
  place-items: center;
  z-index: 9998;
}
.modalCard {
  width: min(420px, calc(100vw - 32px));
  padding: 16px 16px 14px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(12px);
  border-radius: 16px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  box-shadow: 0 22px 60px rgba(15, 23, 42, 0.28);
}
.modalTitle {
  font-weight: 900;
  font-size: 16px;
}
.field {
  margin-top: 12px;
  display: grid;
  gap: 6px;
}
.label {
  font-size: 12px;
}
.modalActions {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
