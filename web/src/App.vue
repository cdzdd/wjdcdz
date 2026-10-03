<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { api, login, logout as endSession, refreshCsrf, ApiError } from './api'
import { user, refreshUser, perform, message } from './state'
const router = useRouter(), ready = ref(false), startError = ref(''), register = ref(false), authBusy = ref(false), authError = ref('')
const username = ref(''), password = ref(''), displayName = ref('')
const isAdmin = computed(() => ['ADMIN','OPERATOR'].includes(user.value?.role))
const demoCredentials = import.meta.env.DEV || import.meta.env.VITE_DEMO === 'true'
const menus = [['/', '◈', '资产总览'], ['/market', '▥', '基金市场'], ['/accounts', '▤', '银行卡与资金'], ['/orders', '⇄', '交易记录'], ['/plans', '◷', '定投计划'], ['/benefits', '◇', '认购与收益'], ['/messages', '✉', '消息公告'], ['/profile', '◎', '账户设置'], ['/help', '?', '使用帮助']]
const adminMenus = [['/admin/overview', '◈', '运营总览'], ['/admin/customers', '◎', '客户与账户'], ['/admin/products', '▥', '产品与数据'], ['/admin/trading', '⇄', '交易与任务'], ['/admin/benefits', '◇', '收益与生命周期'], ['/admin/system', '▤', '运营与审计']]
async function start() {
  ready.value = false; startError.value = ''
  try { await refreshCsrf(); await refreshUser(); if(user.value?.mustChangePassword) await router.push('/profile') } catch (e) { if (!(e instanceof ApiError && e.status === 401)) startError.value = message(e) } finally { ready.value = true }
}
async function authenticate() {
  authBusy.value = true; authError.value = ''
  try {
    if (register.value) await api.post('/auth/register', { username: username.value, password: password.value, displayName: displayName.value })
    user.value = await login({ username: username.value, password: password.value })
    await refreshUser(); password.value = ''; await router.push(isAdmin.value ? '/admin/overview' : '/')
  } catch (e) { authError.value = message(e) } finally { authBusy.value = false }
}
async function logout() { await perform(endSession, () => { user.value = null; password.value = ''; return router.push('/') }) }
function expired() { user.value = null }
onMounted(() => { window.addEventListener('session-expired', expired); start() })
onBeforeUnmount(() => window.removeEventListener('session-expired', expired))
</script>
<template>
  <div v-if="!ready" class="boot">正在连接基金模拟平台…</div>
  <div v-else-if="!user" class="auth-layout">
    <aside class="auth-story"><div class="brand"><span class="brand-symbol">恒</span><div>恒升<span>基金模拟平台</span></div></div><div><p class="eyebrow">A CLEARER WAY TO LEARN</p><h1>让每一笔交易<br/>都有清晰的来路。</h1><p>从基金选择到份额确认，体验完整的基金销售与登记流程。逐卡记账，循序学习。</p><div class="story-facts"><div><b>01</b><span>账户独立</span></div><div><b>02</b><span>来源可查</span></div><div><b>03</b><span>流程完整</span></div></div></div><p class="auth-foot">教学模拟 · 使用虚拟资金 · 不涉及真实投资</p></aside>
    <main class="auth-main"><div class="auth-card"><p class="eyebrow">WELCOME TO HENGSHENG</p><h2>{{ register ? '创建学习账户' : '欢迎回来' }}</h2><p class="muted">{{ register ? '使用昵称开户，无需真实身份或银行卡信息。' : '登录后继续你的模拟投资旅程。' }}</p><div v-if="startError" class="notice error">{{ startError }} <button class="text-button" @click="start">重试连接</button></div><form @submit.prevent="authenticate"><label><span>用户名</span><input v-model="username" name="username" autocomplete="username" required minlength="3" maxlength="50" placeholder="输入用户名"/></label><label v-if="register"><span>模拟持卡人 / 昵称</span><input v-model="displayName" name="displayName" required maxlength="60" placeholder="所有模拟卡使用此名称"/></label><label><span>密码</span><input v-model="password" name="password" autocomplete="current-password" type="password" required :minlength="register ? 10 : undefined" placeholder="输入密码"/></label><p v-if="authError" class="notice error" role="alert">{{ authError }}</p><button class="button primary full" :disabled="authBusy">{{ authBusy ? '处理中…' : register ? '注册并登录' : '登录' }}</button></form><p class="auth-switch">{{ register ? '已有账户？' : '第一次使用？' }} <button class="text-button" @click="register = !register; authError = ''">{{ register ? '返回登录' : '注册账户' }}</button></p><p v-if="demoCredentials" class="muted small">演示账户：demo / Demo12345!<br/>管理员：admin / Admin12345!</p></div></main>
  </div>
  <div v-else class="app-layout">
    <aside class="sidebar"><RouterLink to="/" class="brand"><span class="brand-symbol">恒</span><div>恒升<span>基金模拟平台</span></div></RouterLink><p class="nav-caption">{{ isAdmin ? '销售管理后台' : '投资者中心' }}</p><nav><RouterLink v-for="item in (isAdmin ? adminMenus : menus)" :key="item[0]" :to="item[0]" :class="{ active: router.currentRoute.value.path === item[0] }"><span class="nav-icon" aria-hidden="true">{{ item[1] }}</span>{{ item[2] }}</RouterLink></nav><div class="sidebar-bottom"><span class="simulation-pill">● 教学模拟环境</span><p>虚拟资金与模拟产品<br/>每笔操作保留来源与记录</p></div></aside>
    <div class="workspace"><header class="topbar"><div class="mobile-brand">恒升 <span>SIMULATION</span></div><span class="topbar-context">{{ isAdmin ? '销售运营工作台' : '基金学习与交易' }}</span><div class="user-area"><RouterLink v-if="isAdmin" to="/">投资者视图</RouterLink><RouterLink v-else to="/messages">消息</RouterLink><span class="avatar">{{ (user.displayName || user.username).slice(0, 1) }}</span><span>{{ user.displayName || user.username }}</span><button class="text-button" @click="logout">退出</button></div></header><main class="page-content"><RouterView :key="router.currentRoute.value.fullPath"/></main><footer class="site-footer">恒升基金模拟平台 · 所有交易使用虚拟资金 · 业务参数为教学模拟规则</footer></div>
  </div>
</template>
