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
      { path: 'friends', component: () => import('./views/FriendRequestsView.vue') },
      { path: 'chat/:peerUserId', component: () => import('./views/ChatView.vue') },
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

