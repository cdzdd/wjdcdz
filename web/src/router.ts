import { createRouter, createWebHistory } from 'vue-router'
import Lobby from './pages/Lobby.vue'
import Dashboard from './pages/Dashboard.vue'
import Market from './pages/Market.vue'
import FundDetail from './pages/FundDetail.vue'
import Accounts from './pages/Accounts.vue'
import Orders from './pages/Orders.vue'
import Plans from './pages/Plans.vue'
import Benefits from './pages/Benefits.vue'
import Messages from './pages/Messages.vue'
import Profile from './pages/Profile.vue'
import Help from './pages/Help.vue'
import Assistant from './pages/Assistant.vue'
import Admin from './pages/Admin.vue'
import { initializeSession, user } from './state'

const protectedPage = { requiresAuth: true }
export function isStaff(role: unknown) { return role === 'ADMIN' || role === 'OPERATOR' }

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'lobby', component: Lobby, meta: { publicPage: true } },
    { path: '/login', name: 'login', component: Lobby, meta: { publicPage: true } },
    { path: '/dashboard', component: Dashboard, meta: protectedPage },
    { path: '/market', component: Market, meta: protectedPage },
    { path: '/funds/:id', component: FundDetail, meta: protectedPage },
    { path: '/accounts', component: Accounts, meta: protectedPage },
    { path: '/orders', component: Orders, meta: protectedPage },
    { path: '/plans', component: Plans, meta: protectedPage },
    { path: '/benefits', component: Benefits, meta: protectedPage },
    { path: '/messages', component: Messages, meta: protectedPage },
    { path: '/profile', component: Profile, meta: protectedPage },
    { path: '/help', component: Help, meta: protectedPage },
    { path: '/assistant', component: Assistant, meta: protectedPage },
    { path: '/admin/:section?', component: Admin, meta: { ...protectedPage, staffOnly: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ],
  scrollBehavior: () => ({ top: 0 })
})
export function loginDestination(redirect: unknown) {
  if (user.value?.mustChangePassword) return '/profile'
  const staff = isStaff(user.value?.role)
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//') && !redirect.includes('\\')) {
    const destination = router.resolve(redirect)
    if (destination.meta.requiresAuth && (!destination.meta.staffOnly || staff)) return destination.fullPath
  }
  return staff ? '/admin/overview' : '/dashboard'
}

router.beforeEach(async to => {
  if (to.meta.requiresAuth) await initializeSession()
  if (user.value?.mustChangePassword && to.path !== '/profile') return '/profile'
  if (to.meta.requiresAuth && !user.value) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.meta.staffOnly && !isStaff(user.value?.role)) return '/dashboard'
  if (to.name === 'login' && user.value) return loginDestination(to.query.redirect)
})
