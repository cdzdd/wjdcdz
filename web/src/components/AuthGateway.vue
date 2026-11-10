<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, login } from '../api'
import { loginDestination } from '../router'
import { initializeSession, message, refreshUser, sessionError, user } from '../state'

const router = useRouter(), route = useRoute()
const dialog = ref<HTMLDialogElement>()
const register = ref(false), authBusy = ref(false), authError = ref('')
const username = ref(''), password = ref(''), displayName = ref('')
const demoCredentials = import.meta.env.DEV || import.meta.env.VITE_DEMO === 'true'
let opener: HTMLElement | undefined
let active = true

function enter() {
  if (active && route.name === 'login' && user.value) return router.replace(loginDestination(route.query.redirect))
}
async function retry() { await initializeSession(true); await enter() }
function dismiss() { void router.replace('/') }

async function authenticate() {
  if (authBusy.value) return
  authBusy.value = true
  authError.value = ''
  try {
    await initializeSession()
    if (!active) return
    if (!user.value) {
      if (register.value) await api.post('/auth/register', { username: username.value, password: password.value, displayName: displayName.value })
      if (!active) return
      user.value = await login({ username: username.value, password: password.value })
      await refreshUser()
    }
    password.value = ''
    await enter()
  } catch (error) { authError.value = message(error) }
  finally { authBusy.value = false }
}

function switchMode() {
  if (authBusy.value) return
  register.value = !register.value
  authError.value = ''
}

onMounted(() => {
  if (document.activeElement instanceof HTMLElement && document.activeElement !== document.body) opener = document.activeElement
  dialog.value?.showModal()
})
onBeforeUnmount(() => {
  active = false
  dialog.value?.close()
  if (route.name === 'lobby') void nextTick(() => {
    const target = opener?.isConnected ? opener : document.getElementById('lobby-login')
    target?.focus()
  })
})
</script>

<template>
  <dialog ref="dialog" class="lobby-auth-dialog" aria-labelledby="lobby-auth-title" @cancel.prevent="dismiss">
    <button type="button" class="lobby-auth-close" aria-label="关闭登录" @click="dismiss">×</button>
    <div class="lobby-auth-main">
      <aside class="lobby-auth-story" aria-label="平台介绍">
        <p class="lobby-auth-kicker">让学习，有迹可循</p>
        <h2>每一次选择<br/>都让成长<em>清晰可见</em></h2>
        <p>从第一张模拟银行卡，到第一笔基金申购。<br/>在完整的业务流程里，建立自己的投资认知。</p>
        <svg class="lobby-auth-chart" viewBox="0 0 500 120" preserveAspectRatio="none" role="img" aria-label="学习成长轨迹示意图">
          <path d="M0 14h500M0 62h500M0 110h500" stroke="#e8edf3" fill="none"/>
          <path d="M5 97l44-9 44-9 44-15 44-9 44 5 44-12 44 4 44-19 44-11 44 5 50-17V110H5Z" fill="#e7eef7"/>
          <path d="M5 97l44-9 44-9 44-15 44-9 44 5 44-12 44 4 44-19 44-11 44 5 50-17" stroke="#315e92" stroke-width="2.5" stroke-linejoin="round" fill="none"/>
        </svg>
        <div class="lobby-auth-facts">
          <div><small>01 / 账户</small><strong>账户有序</strong><p>逐卡管理模拟资金</p></div>
          <div><small>02 / 交易</small><strong>交易有据</strong><p>从申请到份额确认</p></div>
          <div><small>03 / 记录</small><strong>记录可循</strong><p>每笔操作都有来路</p></div>
        </div>
      </aside>
      <section class="lobby-auth-card" aria-labelledby="lobby-auth-title">
        <p class="lobby-auth-kicker">投资学习，从这里继续</p>
        <h2 id="lobby-auth-title">{{ register ? '创建学习账户' : '欢迎回来' }}</h2>
        <p>{{ register ? '创建模拟账户，开始探索基金业务。' : '登录恒升，继续探索每一笔交易的来龙去脉。' }}</p>
        <div v-if="sessionError" class="notice error" role="alert">{{ sessionError }} <button type="button" class="lobby-auth-link" :disabled="authBusy" @click="retry">重试连接</button></div>
        <form :aria-busy="authBusy" @submit.prevent="authenticate">
          <fieldset :disabled="authBusy">
            <label for="lobby-auth-username"><span>用户名</span><input id="lobby-auth-username" v-model="username" name="username" autocomplete="username" required minlength="3" maxlength="50" placeholder="输入用户名" autofocus/></label>
            <label v-if="register" for="lobby-auth-display-name"><span>模拟持卡人 / 昵称</span><input id="lobby-auth-display-name" v-model="displayName" name="displayName" autocomplete="nickname" required maxlength="60" placeholder="所有模拟卡使用此名称"/></label>
            <label for="lobby-auth-password"><span>密码</span><input id="lobby-auth-password" v-model="password" name="password" :autocomplete="register ? 'new-password' : 'current-password'" type="password" required :minlength="register ? 10 : undefined" placeholder="输入密码"/></label>
            <p v-if="authError" class="notice error" role="alert">{{ authError }}</p>
            <button class="lobby-auth-submit" type="submit">{{ authBusy ? '处理中…' : register ? '注册并登录' : '登录' }}<span aria-hidden="true">→</span></button>
          </fieldset>
        </form>
        <p class="lobby-auth-switch">{{ register ? '已经有账户了？' : '第一次来到恒升？' }} <button type="button" class="lobby-auth-link" :disabled="authBusy" @click="switchMode">{{ register ? '返回登录' : '注册账户' }}</button></p>
        <p class="lobby-auth-note">教学模拟 · 使用虚拟资金 · 不涉及真实投资</p>
        <p v-if="demoCredentials" class="lobby-auth-note">本地演示账户：demo / Demo12345!<br/>本地管理员：admin / Admin12345!</p>
      </section>
    </div>
  </dialog>
</template>

<style scoped>
.lobby-auth-dialog {
  width: min(calc(100% - 48px), 1420px);
  max-width: none;
  max-height: calc(100dvh - 32px);
  margin: auto;
  padding: 0;
  overflow: auto;
  overscroll-behavior: contain;
  border: 1px solid #dce5ef;
  border-radius: 14px;
  background: #f3f6fa;
  color: #1f3047;
  box-shadow: 0 26px 90px #102a4a40;
}
.lobby-auth-dialog::backdrop { background: rgb(19 40 68 / 28%); backdrop-filter: blur(7px); }
:global(body:has(.lobby-auth-dialog[open])) { overflow: hidden; }
.lobby-auth-close {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 44px;
  height: 44px;
  z-index: 1;
  border: 0;
  border-radius: 50%;
  background: #e8eef6;
  color: #324f6e;
  font-size: 24px;
  line-height: 1;
}
.lobby-auth-close:hover { background: #dbe7f4; }
.lobby-auth-main {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(330px, .74fr);
  gap: 46px;
  align-items: center;
  padding: 50px 42px 36px;
}
.lobby-auth-kicker { color: #315e92; font-size: 13px; font-weight: 600; letter-spacing: 2px; }
.lobby-auth-story h2 { margin: 18px 0 24px; font-size: clamp(30px, 4vw, 46px); line-height: 1.4; font-weight: 550; }
.lobby-auth-story em { color: #315e92; font-style: normal; }
.lobby-auth-story p, .lobby-auth-card > p { color: #69798c; font-size: 15px; line-height: 1.8; }
.lobby-auth-chart { display: block; width: 100%; height: 120px; margin: 26px 0 20px; }
.lobby-auth-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; }
.lobby-auth-facts > div { border-top: 1px solid #dce5ef; padding-top: 12px; }
.lobby-auth-facts small { color: #69798c; font-size: 12px; }
.lobby-auth-facts strong { display: block; margin: 6px 0; font-size: 17px; }
.lobby-auth-facts p { margin-bottom: 0; font-size: 13px; }
.lobby-auth-card { min-width: 0; padding: 38px; border: 1px solid #e0e6ee; border-radius: 9px; background: #fff; }
.lobby-auth-card .lobby-auth-kicker { color: #315e92; font-size: 12px; margin-bottom: 8px; }
.lobby-auth-card h2 { font-size: 32px; font-weight: 550; margin: 0 0 10px; }
.lobby-auth-card fieldset { min-width: 0; margin: 0; padding: 0; border: 0; }
.lobby-auth-card label { color: #39516b; font-size: 14px; margin: 15px 0; }
.lobby-auth-card input { min-height: 46px; color: #1f3047; border-color: #dce5ef; font-size: 15px; }
.lobby-auth-dialog :is(input, button):focus-visible { outline: 3px solid #638fc4; outline-offset: 2px; }
.lobby-auth-submit {
  display: flex;
  justify-content: space-between;
  width: 100%;
  min-height: 48px;
  align-items: center;
  padding: 0 18px;
  border: 0;
  border-radius: 7px;
  background: #163b69;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}
.lobby-auth-submit:hover { background: #24558f; }
.lobby-auth-link { padding: 0; border: 0; background: transparent; color: #24558f; font-weight: 600; }
.lobby-auth-card .lobby-auth-switch { margin: 16px 0 0; text-align: center; font-size: 14px; }
.lobby-auth-card .lobby-auth-note { margin: 12px 0 0; text-align: center; font-size: 12px; }
@media (min-width: 961px) { .lobby-auth-dialog { zoom: 80%; } }
@media (max-width: 960px) { .lobby-auth-main { gap: 26px; padding: 50px 26px 28px; } .lobby-auth-card { padding: 25px; } }
@media (max-width: 760px) {
  .lobby-auth-dialog { width: calc(100% - 24px); max-height: calc(100dvh - 24px); }
  .lobby-auth-main { display: flex; flex-direction: column-reverse; align-items: stretch; padding: 58px 16px 22px; }
  .lobby-auth-card { padding: 24px 20px; }
  .lobby-auth-story { padding-inline: 8px; }
  .lobby-auth-story h2 { font-size: 30px; }
}
</style>
