import { createRouter, createWebHistory } from 'vue-router'
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
import Admin from './pages/Admin.vue'
import { user } from './state'
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: Dashboard }, { path: '/market', component: Market }, { path: '/funds/:id', component: FundDetail },
    { path: '/accounts', component: Accounts }, { path: '/orders', component: Orders }, { path: '/plans', component: Plans },
    { path: '/benefits', component: Benefits }, { path: '/messages', component: Messages }, { path: '/profile', component: Profile },
    { path: '/help', component: Help }, { path: '/admin/:section?', component: Admin }
  ],
  scrollBehavior: () => ({ top: 0 })
})
router.beforeEach(to => user.value?.mustChangePassword && to.path !== '/profile' ? '/profile' : true)
