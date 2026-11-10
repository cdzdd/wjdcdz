<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { logout as endSession } from './api'
import { user, initializeSession, sessionReady, perform } from './state'
import { isStaff, loginDestination } from './router'
import LoadingIndicator from './components/LoadingIndicator.vue'
const router = useRouter(), route = useRoute()
const isAdmin = computed(() => isStaff(user.value?.role))
const menus = [['/dashboard', '◈', '资产总览'], ['/market', '▥', '基金市场'], ['/accounts', '▤', '银行卡与资金'], ['/orders', '⇄', '交易记录'], ['/plans', '◷', '定投计划'], ['/benefits', '◇', '认购与收益'], ['/messages', '✉', '消息公告'], ['/profile', '◎', '账户设置'], ['/assistant', '◌', '业务助手'], ['/help', '?', '使用帮助']]
const adminMenus = [['/admin/overview', '◈', '运营总览'], ['/admin/customers', '◎', '客户与账户'], ['/admin/products', '▥', '产品与数据'], ['/admin/trading', '⇄', '交易与任务'], ['/admin/benefits', '◇', '收益与生命周期'], ['/admin/system', '▤', '运营与审计'], ['/assistant', '◌', '业务助手']]
async function start() {
  await initializeSession()
  if (user.value?.mustChangePassword && route.path !== '/profile') await router.replace('/profile')
  else if (user.value && route.name === 'login') await router.replace(loginDestination(route.query.redirect))
}
async function logout() { await perform(endSession, () => { user.value = null; return router.replace('/') }) }
function expired() {
  user.value = null
  if (route.meta.requiresAuth) void router.replace({ name: 'login', query: { redirect: route.fullPath } })
}
onMounted(() => { window.addEventListener('session-expired', expired); start() })
onBeforeUnmount(() => window.removeEventListener('session-expired', expired))
</script>
<template>
  <RouterView v-if="route.meta.publicPage"/>
  <LoadingIndicator v-else-if="!sessionReady" full-screen label="正在连接基金模拟平台…"/>
  <div v-else-if="user" class="app-layout">
    <aside class="sidebar"><RouterLink to="/" class="brand"><span class="brand-symbol">恒</span><div>恒升<span>基金模拟平台</span></div></RouterLink><p class="nav-caption">{{ isAdmin ? '销售管理后台' : '投资者中心' }}</p><nav><RouterLink v-for="item in (isAdmin ? adminMenus : menus)" :key="item[0]" :to="item[0]" :class="{ active: router.currentRoute.value.path === item[0] }"><span class="nav-icon" aria-hidden="true">{{ item[1] }}</span>{{ item[2] }}</RouterLink></nav><div class="sidebar-bottom"><span class="simulation-pill">● 教学模拟环境</span><p>虚拟资金与模拟产品<br/>每笔操作保留来源与记录</p></div></aside>
    <div class="workspace"><header class="topbar"><div class="mobile-brand">恒升 <span>SIMULATION</span></div><span class="topbar-context">{{ isAdmin ? '销售运营工作台' : '基金学习与交易' }}</span><div class="user-area"><RouterLink v-if="isAdmin" to="/dashboard">投资者视图</RouterLink><RouterLink v-else to="/messages">消息</RouterLink><span class="avatar">{{ (user.displayName || user.username).slice(0, 1) }}</span><span>{{ user.displayName || user.username }}</span><button class="text-button" @click="logout">退出</button></div></header><main class="page-content"><div class="page-surface" :key="router.currentRoute.value.fullPath"><RouterView/></div></main><footer class="site-footer">恒升基金模拟平台 · 所有交易使用虚拟资金 · 业务参数为教学模拟规则</footer></div>
  </div>
</template>
<style scoped>.sidebar nav{min-height:0;overflow-y:auto}</style>
