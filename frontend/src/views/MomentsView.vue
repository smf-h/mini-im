<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { apiGet, apiPost } from '../services/api'
import type {
  CreateMomentCommentResponse,
  CreateMomentPostResponse,
  MomentCommentDto,
  MomentPostDto,
  ToggleMomentLikeResponse,
} from '../types/api'
import { useAuthStore } from '../stores/auth'
import { useUserStore } from '../stores/users'
import { formatTime } from '../utils/format'
import UiAvatar from '../components/UiAvatar.vue'
import UiListItem from '../components/UiListItem.vue'
import UiSegmented from '../components/UiSegmented.vue'

type Tab = 'feed' | 'me'

const router = useRouter()
const auth = useAuthStore()
const users = useUserStore()

const tab = ref<Tab>('feed')
const tabOptions = [
  { value: 'feed', label: '动态' },
  { value: 'me', label: '我的' },
]

const posting = ref(false)
const draft = ref('')

const loading = ref(false)
const done = ref(false)
const errorMsg = ref<string | null>(null)
const posts = ref<MomentPostDto[]>([])

type CommentState = {
  open: boolean
  loading: boolean
  done: boolean
  errorMsg: string | null
  items: MomentCommentDto[]
  draft: string
}

const commentsByPostId = ref<Record<string, CommentState>>({})

const lastPostId = computed(() => (posts.value.length ? posts.value[posts.value.length - 1]!.id : null))

function displayUser(id: string) {
  if (id === auth.userId) return '我'
  return users.displayName(id)
}

function getOrInitCommentState(postId: string): CommentState {
  const cur = commentsByPostId.value[postId]
  if (cur) return cur
  const init: CommentState = { open: false, loading: false, done: false, errorMsg: null, items: [], draft: '' }
  commentsByPostId.value[postId] = init
  return init
}

function commentState(postId: string) {
  return getOrInitCommentState(postId)
}

async function loadMorePosts() {
  if (loading.value || done.value) return
  loading.value = true
  errorMsg.value = null
  try {
    const qs = new URLSearchParams()
    qs.set('limit', '20')
    if (lastPostId.value != null) {
      qs.set('lastId', String(lastPostId.value))
    }

    const path =
      tab.value === 'me'
        ? `/moment/user/cursor?userId=${encodeURIComponent(String(auth.userId ?? ''))}&${qs.toString()}`
        : `/moment/feed/cursor?${qs.toString()}`

    const data = await apiGet<MomentPostDto[]>(path)
    if (!data.length) {
      done.value = true
      return
    }
    posts.value.push(...data)
    void users.ensureBasics(Array.from(new Set(data.map((x) => String(x.authorId)))))
  } catch (e) {
    errorMsg.value = String(e)
  } finally {
    loading.value = false
  }
}

function resetAndLoad() {
  posts.value = []
  done.value = false
  errorMsg.value = null
  void loadMorePosts()
}

function onScroll(e: Event) {
  const el = e.target as HTMLElement
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 80) {
    void loadMorePosts()
  }
}

async function createPost() {
  const content = draft.value.trim()
  if (!content) return
  if (posting.value) return
  posting.value = true
  errorMsg.value = null
  try {
    const resp = await apiPost<CreateMomentPostResponse>('/moment/post/create', { content })
    draft.value = ''
    // 直接刷新，避免额外组装 DTO
    resetAndLoad()
    return resp
  } catch (e) {
    errorMsg.value = String(e)
    return null
  } finally {
    posting.value = false
  }
}

async function deletePost(postId: string) {
  errorMsg.value = null
  try {
    await apiPost<void>('/moment/post/delete', { postId })
    posts.value = posts.value.filter((p) => String(p.id) !== String(postId))
  } catch (e) {
    errorMsg.value = String(e)
  }
}

async function toggleLike(p: MomentPostDto) {
  errorMsg.value = null
  try {
    const resp = await apiPost<ToggleMomentLikeResponse>('/moment/like/toggle', { postId: p.id })
    p.likedByMe = resp.liked
    p.likeCount = resp.likeCount
  } catch (e) {
    errorMsg.value = String(e)
  }
}

async function openComments(p: MomentPostDto) {
  const key = String(p.id)
  const state = getOrInitCommentState(key)
  state.open = !state.open
  if (!state.open) return
  if (state.items.length) return
  void loadMoreComments(p)
}

async function loadMoreComments(p: MomentPostDto) {
  const postId = String(p.id)
  const state = getOrInitCommentState(postId)
  if (state.loading || state.done) return
  state.loading = true
  state.errorMsg = null
  try {
    const qs = new URLSearchParams()
    qs.set('postId', postId)
    qs.set('limit', '20')
    const lastId = state.items.length ? state.items[state.items.length - 1]!.id : null
    if (lastId != null) {
      qs.set('lastId', String(lastId))
    }
    const data = await apiGet<MomentCommentDto[]>(`/moment/comment/cursor?${qs.toString()}`)
    if (!data.length) {
      state.done = true
      return
    }
    state.items.push(...data)
    void users.ensureBasics(Array.from(new Set(data.map((x) => String(x.userId)))))
  } catch (e) {
    state.errorMsg = String(e)
  } finally {
    state.loading = false
  }
}

async function createComment(p: MomentPostDto) {
  const postId = String(p.id)
  const state = getOrInitCommentState(postId)
  const content = state.draft.trim()
  if (!content) return
  state.errorMsg = null
  try {
    const resp = await apiPost<CreateMomentCommentResponse>('/moment/comment/create', { postId: p.id, content })
    state.draft = ''
    // 刷新评论区（保持简单正确）
    state.items = []
    state.done = false
    await loadMoreComments(p)
    // commentCount 由服务端计数更新，这里仅做轻量同步：+1（避免用户误解）
    p.commentCount = Math.max(0, (p.commentCount ?? 0) + 1)
    return resp
  } catch (e) {
    state.errorMsg = String(e)
    return null
  }
}

async function deleteComment(p: MomentPostDto, commentId: string) {
  const postId = String(p.id)
  const state = getOrInitCommentState(postId)
  state.errorMsg = null
  try {
    await apiPost<void>('/moment/comment/delete', { commentId })
    state.items = state.items.filter((c) => String(c.id) !== String(commentId))
    p.commentCount = Math.max(0, (p.commentCount ?? 0) - 1)
  } catch (e) {
    state.errorMsg = String(e)
  }
}

function canDeletePost(p: MomentPostDto) {
  return auth.userId != null && String(p.authorId) === String(auth.userId)
}

function canDeleteComment(p: MomentPostDto, c: MomentCommentDto) {
  if (!auth.userId) return false
  const me = String(auth.userId)
  return String(c.userId) === me || String(p.authorId) === me
}

function gotoUser(id: string) {
  void router.push(`/contacts/u/${id}`)
}

onMounted(() => {
  resetAndLoad()
})
</script>

<template>
  <div class="page">
    <div class="header row" style="justify-content: space-between">
      <h2 style="margin: 0">朋友圈</h2>
      <div class="row" style="gap: 8px">
        <UiSegmented v-model="tab" :options="tabOptions" />
        <button class="btn" @click="resetAndLoad">刷新</button>
      </div>
    </div>

    <div class="composer">
      <textarea v-model="draft" class="textarea" placeholder="分享新鲜事…" rows="3" />
      <div class="row" style="justify-content: space-between">
        <div class="muted" style="font-size: 12px">{{ draft.trim().length }}/500</div>
        <button class="btn primary" :disabled="posting || !draft.trim()" @click="createPost">发布</button>
      </div>
    </div>

    <div class="list" @scroll="onScroll">
      <UiListItem v-for="p in posts" :key="String(p.id)">
        <template #left>
          <button class="avatarBtn" type="button" @click="gotoUser(String(p.authorId))">
            <UiAvatar :text="displayUser(String(p.authorId))" :seed="String(p.authorId)" :size="40" />
          </button>
        </template>
        <div class="main">
          <div class="titleRow">
            <div class="name">{{ displayUser(String(p.authorId)) }}</div>
            <div class="time">{{ formatTime(p.createdAt) }}</div>
          </div>
          <div class="content">{{ p.content }}</div>

          <div class="actions row">
            <button class="btnSmall" :class="{ liked: p.likedByMe }" @click.stop="toggleLike(p)">
              {{ p.likedByMe ? '已赞' : '点赞' }} · {{ p.likeCount ?? 0 }}
            </button>
            <button class="btnSmall" @click.stop="openComments(p)">评论 · {{ p.commentCount ?? 0 }}</button>
            <button v-if="canDeletePost(p)" class="btnSmall danger" @click.stop="deletePost(String(p.id))">删除</button>
          </div>

          <div v-if="commentState(String(p.id)).open" class="comments">
            <div class="commentList">
              <div v-for="c in commentState(String(p.id)).items" :key="String(c.id)" class="commentItem">
                <button class="commentUser" type="button" @click="gotoUser(String(c.userId))">
                  {{ displayUser(String(c.userId)) }}
                </button>
                <span class="commentContent">：{{ c.content }}</span>
                <span class="commentFooter">
                  <span>{{ formatTime(c.createdAt) }}</span>
                  <button
                    v-if="canDeleteComment(p, c)"
                    class="commentDel"
                    type="button"
                    @click.stop="deleteComment(p, String(c.id))"
                  >
                    删除
                  </button>
                </span>
              </div>

              <div class="muted" style="padding: 8px 0; text-align: center; font-size: 13px">
                <span v-if="commentState(String(p.id)).loading">加载中…</span>
                <span v-else-if="commentState(String(p.id)).done"></span>
                <button
                  v-else
                  class="btnSmall"
                  style="margin: 0 auto; border: none; background: transparent; color: var(--text-3);"
                  @click.stop="loadMoreComments(p)"
                >
                  查看更多评论
                </button>
              </div>

              <div v-if="commentState(String(p.id)).errorMsg" class="muted" style="color: var(--danger)">
                {{ commentState(String(p.id)).errorMsg }}
              </div>
            </div>

            <div class="commentComposer">
              <input
                v-model="commentState(String(p.id)).draft"
                class="input"
                placeholder="评论…"
                @keydown.enter.prevent="createComment(p)"
              />
              <button
                class="btnSmall primary"
                :disabled="!commentState(String(p.id)).draft.trim()"
                @click="createComment(p)"
              >
                发送
              </button>
            </div>
          </div>
        </div>
      </UiListItem>

      <div class="muted" style="padding: 10px; text-align: center">
        <span v-if="loading">加载中…</span>
        <span v-else-if="done">没有更多了</span>
        <span v-else>下滑加载更多</span>
      </div>
    </div>

    <div v-if="errorMsg" class="muted" style="color: var(--danger); margin-top: 10px">{{ errorMsg }}</div>
  </div>
</template>


<style scoped>
.page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg-main, var(--bg));
}
.header {
  flex: none;
  padding: 12px 24px;
  background: var(--surface);
  border-bottom: 1px solid var(--divider);
  z-index: 10;
}
.composer {
  flex: none;
  margin: 16px 24px 12px;
  padding: 16px;
  border-radius: 12px;
  background: var(--surface);
  border: 1px solid var(--divider);
  display: grid;
  gap: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.02);
}
.textarea {
  width: 100%;
  resize: vertical;
  min-height: 80px;
  border-radius: 8px;
  border: 1px solid transparent;
  background: #f7f7f7;
  padding: 12px;
  color: var(--text-1);
  outline: none;
  font-size: 15px;
  transition: all 0.2s;
}
.textarea:focus {
  background: #fff;
  border-color: rgba(7, 193, 96, 0.45);
  box-shadow: 0 0 0 2px rgba(7, 193, 96, 0.1);
}
.list {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 0;
  background: var(--surface);
}
/* Optimize scrollbar */
.list::-webkit-scrollbar {
  width: 6px;
}
.list::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.1);
  border-radius: 3px;
}

.main {
  min-width: 0;
  padding-bottom: 12px;
}
.titleRow {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 6px;
}
.name {
  font-weight: 600;
  font-size: 16px;
  color: #3b507d; /* WeChat-like blue for names */
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.4;
}
.time {
  flex: none;
  font-size: 12px;
  color: var(--text-3);
}
.content {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
  font-size: 15px;
  color: var(--text-1);
  margin-bottom: 8px;
}
.actions {
  margin-top: 8px;
  display: flex;
  gap: 12px;
  align-items: center;
}

.btnSmall {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 13px;
  border: 1px solid var(--divider);
  background: transparent;
  color: var(--text-2);
  cursor: pointer;
  transition: all 0.15s;
  line-height: 1.4;
}
.btnSmall:hover {
  background: rgba(0, 0, 0, 0.04);
  color: var(--text-1);
}
.btnSmall.liked {
  border-color: transparent;
  color: var(--primary);
  background: rgba(7, 193, 96, 0.08);
}
.btnSmall.danger {
  border-color: transparent;
  color: var(--danger);
  background: rgba(244, 63, 94, 0.06);
}
.btnSmall.danger:hover {
  background: rgba(244, 63, 94, 0.12);
}
.btnSmall.primary {
  background: var(--primary);
  color: #fff;
  border-color: transparent;
}
.btnSmall.primary:hover {
  filter: brightness(0.95);
}
.btnSmall.primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.comments {
  margin-top: 14px;
  padding: 12px;
  border-radius: 8px;
  background: #f7f7f7;
  position: relative;
}
.comments::before {
  content: "";
  position: absolute;
  top: -6px;
  left: 14px;
  width: 0;
  height: 0;
  border-left: 6px solid transparent;
  border-right: 6px solid transparent;
  border-bottom: 6px solid #f7f7f7;
}

.commentList {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.commentItem {
  font-size: 14px;
  line-height: 1.5;
  color: var(--text-1);
}
.commentUser {
  border: 0;
  background: transparent;
  padding: 0;
  cursor: pointer;
  color: #3b507d;
  font-weight: 500;
  font-size: 14px;
}
.commentUser:hover {
  text-decoration: underline;
}
.commentContent {
  color: var(--text-2);
  margin: 0 4px;
}
.commentFooter {
  display: inline-flex;
  gap: 8px;
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-3);
  align-items: center;
}

.commentDel {
  border: 0;
  background: transparent;
  padding: 0;
  cursor: pointer;
  font-size: 12px;
  color: var(--text-3);
  transition: color 0.2s;
}
.commentDel:hover {
  color: var(--danger);
  text-decoration: underline;
}

.commentComposer {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}
.input {
  flex: 1;
  border-radius: 6px;
  border: 1px solid var(--divider);
  background: #fff;
  padding: 8px 12px;
  color: var(--text-1);
  outline: none;
  font-size: 14px;
  transition: all 0.2s;
}
.input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px rgba(7, 193, 96, 0.12);
}
</style>
