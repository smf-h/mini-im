<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import StartChatModal from './StartChatModal.vue'

const props = defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const route = useRoute()
const router = useRouter()

const startChatOpen = ref(false)

const items = computed(() => [
  {
    key: 'start-dm',
    title: '发起单聊',
    desc: '从好友列表选人',
    run: () => {
      startChatOpen.value = true
    },
  },
  {
    key: 'create-group',
    title: '创建群聊',
    desc: '创建群后用群码邀请',
    run: () => {
      void router.push('/contacts/groups?action=create')
      emit('close')
    },
  },
  {
    key: 'join-group',
    title: '加入群组',
    desc: '输入群码申请入群',
    run: () => {
      void router.push('/contacts/groups?action=join')
      emit('close')
    },
  },
  {
    key: 'add-friend',
    title: '添加朋友',
    desc: '使用 FriendCode 申请好友',
    run: () => {
      void router.push('/contacts/new-friends?action=add-friend')
      emit('close')
    },
  },
])

function close() {
  emit('close')
}

watch(
  () => props.open,
  () => {
    if (!props.open) startChatOpen.value = false
  },
)

watch(
  () => route.path,
  () => {
    if (props.open) emit('close')
  },
)
</script>

<template>
  <div v-if="open" class="menu" role="menu" aria-label="全局操作菜单">
    <button v-for="it in items" :key="it.key" class="item" type="button" @click="it.run">
      <div class="t">{{ it.title }}</div>
      <div class="d">{{ it.desc }}</div>
    </button>
  </div>
  <StartChatModal :open="startChatOpen" @close="startChatOpen = false; close()" />
</template>

<style scoped>
.menu {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  width: 240px;
  border-radius: 14px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(12px);
  box-shadow: 0 18px 48px rgba(15, 23, 42, 0.2);
  overflow: hidden;
  z-index: 20;
}
.item {
  width: 100%;
  text-align: left;
  padding: 10px 12px;
  border: 0;
  background: transparent;
  cursor: pointer;
  display: grid;
  gap: 2px;
}
.item:hover {
  background: rgba(0, 0, 0, 0.03);
}
.t {
  font-weight: 850;
  color: rgba(15, 23, 42, 0.92);
  font-size: 13px;
}
.d {
  color: rgba(15, 23, 42, 0.6);
  font-size: 12px;
}
</style>

