import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from './stores/auth'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/chats' },
  { path: '/login', component: () => import('./views/LoginView.vue') },
  {
    path: '/chats',
    component: () => import('./views/ChatsView.vue'),
    children: [
      { path: '', component: () => import('./views/ChatEmptyView.vue') },
      { path: 'dm/:peerUserId', component: () => import('./views/ChatView.vue') },
      { path: 'group/:groupId', component: () => import('./views/GroupChatView.vue') },
      { path: 'group/:groupId/profile', component: () => import('./views/GroupProfileView.vue') },
    ],
  },
  {
    path: '/contacts',
    component: () => import('./views/ContactsView.vue'),
    children: [
      { path: '', redirect: '/contacts/new-friends' },
      { path: 'new-friends', component: () => import('./views/FriendRequestsView.vue') },
      { path: 'groups', component: () => import('./views/GroupsView.vue') },
      { path: 'u/:userId', component: () => import('./views/UserProfileView.vue') },
    ],
  },
  { path: '/settings', component: () => import('./views/SettingsView.vue') },

  // legacy routes (keep old deep-links working)
  { path: '/conversations', redirect: '/chats' },
  { path: '/groups', redirect: '/contacts/groups' },
  { path: '/friends', redirect: '/contacts/new-friends' },
  { path: '/chat/:peerUserId', redirect: (to) => `/chats/dm/${to.params.peerUserId}` },
  { path: '/group/:groupId', redirect: (to) => `/chats/group/${to.params.groupId}` },
  { path: '/group/:groupId/profile', redirect: (to) => `/chats/group/${to.params.groupId}/profile` },
  { path: '/u/:userId', redirect: (to) => `/contacts/u/${to.params.userId}` },
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
