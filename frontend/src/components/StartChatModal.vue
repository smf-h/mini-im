<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { apiGet } from '../services/api'
import type { FriendRelationEntity, Id } from '../types/api'
import { useAuthStore } from '../stores/auth'
import { useUserStore } from '../stores/users'
import UiAvatar from './UiAvatar.vue'

const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const router = useRouter()
const auth = useAuthStore()
const users = useUserStore()

const loading = ref(false)
const errorMsg = ref<string | null>(null)
const friends = ref<Id[]>([])
const q = ref('')

function friendIdOf(r: FriendRelationEntity) {
  if (!auth.userId) return String(r.user1Id)
  const u1 = String(r.user1Id)
  const u2 = String(r.user2Id)
  return u1 === auth.userId ? u2 : u1
}

async function load() {
  if (!props.open) return
  if (!auth.userId) return
  loading.value = true
  errorMsg.value = null
  try {
    const list = await apiGet<FriendRelationEntity[]>(`/friend/relation/list`)
    friends.value = Array.from(new Set(list.map(friendIdOf)))
    void users.ensureBasics(friends.value)
  } catch (e) {
    errorMsg.value = String(e)
  } finally {
    loading.value = false
  }
}

const filtered = computed(() => {
  const raw = q.value.trim().toLowerCase()
  if (!raw) return friends.value
  return friends.value.filter((id) => {
    const name = users.displayName(id).toLowerCase()
    return id.toLowerCase().includes(raw) || name.includes(raw)
  })
})

function close() {
  emit('close')
}

function openChat(id: string) {
  close()
  void router.push(`/chats/dm/${id}`)
}

watch(
  () => props.open,
  () => {
    if (props.open) {
      q.value = ''
      void load()
    }
  },
)

onMounted(() => {
  if (props.open) void load()
})
</script>

<template>
  <div v-if="open" class="mask" @click.self="close">
    <div class="card" role="dialog" aria-modal="true" aria-label="发起单聊">
      <div class="top">
        <div>
          <div class="title">发起单聊</div>
          <div class="sub">从好友列表选择一个人开始聊天。</div>
        </div>
        <button class="x" type="button" aria-label="关闭" @click="close">×</button>
      </div>

      <div class="search">
        <input v-model="q" class="input" placeholder="搜索昵称" />
      </div>

      <div class="list">
        <div v-if="loading" class="tail">加载中…</div>
        <div v-else-if="errorMsg" class="tail error">{{ errorMsg }}</div>
        <button v-for="id in filtered" :key="id" class="rowBtn" type="button" @click="openChat(id)">
          <UiAvatar :text="users.displayName(id)" :seed="id" :size="40" />
          <div class="name">{{ users.displayName(id) }}</div>
        </button>
        <div v-if="!loading && !errorMsg && filtered.length === 0" class="tail">暂无匹配</div>
      </div>

      <div class="actions">
        <button class="btn" type="button" @click="router.push('/contacts/new-friends?action=add-friend'); close()">
          去添加好友
        </button>
        <button class="btn" type="button" @click="router.push('/contacts'); close()">去通讯录</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.35);
  display: grid;
  place-items: center;
  z-index: 9998;
}
.card {
  width: min(520px, calc(100vw - 32px));
  max-height: min(640px, calc(100vh - 40px));
  padding: 16px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(12px);
  border-radius: 16px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  box-shadow: 0 22px 60px rgba(15, 23, 42, 0.28);
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.title {
  font-weight: 900;
  font-size: 16px;
}
.sub {
  margin-top: 4px;
  color: rgba(15, 23, 42, 0.72);
  font-size: 12px;
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
.search {
  display: flex;
}
.list {
  flex: 1;
  overflow: auto;
  border-radius: 14px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(255, 255, 255, 0.86);
}
.rowBtn {
  width: 100%;
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 10px;
  align-items: center;
  padding: 10px 12px;
  border: 0;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  background: transparent;
  cursor: pointer;
  text-align: left;
}
.rowBtn:hover {
  background: rgba(0, 0, 0, 0.03);
}
.name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 800;
  font-size: 14px;
}
.tail {
  padding: 12px;
  text-align: center;
  font-size: 12px;
  color: rgba(15, 23, 42, 0.62);
}
.tail.error {
  color: var(--danger);
}
.actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
