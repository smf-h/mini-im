import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from './stores/auth'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/conversations' },
  { path: '/login', component: () => import('./views/LoginView.vue') },
  {
    path: '/',
    component: () => import('./views/AppShellView.vue'),
    children: [
      { path: 'conversations', component: () => import('./views/ConversationsView.vue') },
      { path: 'groups', component: () => import('./views/GroupsView.vue') },
      { path: 'friends', component: () => import('./views/FriendRequestsView.vue') },
      { path: 'chat/:peerUserId', component: () => import('./views/ChatView.vue') },
      { path: 'group/:groupId', component: () => import('./views/GroupChatView.vue') },
      { path: 'group/:groupId/profile', component: () => import('./views/GroupProfileView.vue') },
      { path: 'u/:userId', component: () => import('./views/UserProfileView.vue') },
    ],
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  auth.hydrateFromStorage()

  if (to.path === '/login') return true
  if (!auth.isLoggedIn) return '/login'
  return true
})
