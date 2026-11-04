<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, login } from '../api'
import { user, refreshUser, message } from '../state'

defineProps<{ startError: string }>()
defineEmits<{ retry: [] }>()

const router = useRouter()
const register = ref(false), authBusy = ref(false), authError = ref('')
const username = ref(''), password = ref(''), displayName = ref('')
const motionPaused = ref(false)
const demoCredentials = import.meta.env.DEV || import.meta.env.VITE_DEMO === 'true'

async function authenticate() {
  if (authBusy.value) return
  authBusy.value = true; authError.value = ''
  try {
    if (register.value) await api.post('/auth/register', { username: username.value, password: password.value, displayName: displayName.value })
    user.value = await login({ username: username.value, password: password.value })
    await refreshUser(); password.value = ''
    await router.push(['ADMIN', 'OPERATOR'].includes(user.value?.role) ? '/admin/overview' : '/')
  } catch (error) { authError.value = message(error) }
  finally { authBusy.value = false }
}

function switchMode() {
  if (authBusy.value) return
  register.value = !register.value
  authError.value = ''
}
</script>

<template>
  <div class="hg-gateway" :class="{ 'hg-paused': motionPaused }">
    <div class="hg-ambient hg-ambient-one" aria-hidden="true" />
    <div class="hg-ambient hg-ambient-two" aria-hidden="true" />
    <header class="hg-header">
      <div class="hg-brand"><span class="hg-brand-mark">恒</span><div>恒升<span>HENGSHENG · FUND SIMULATION</span></div></div>
      <div class="hg-header-actions"><button class="hg-motion-toggle" type="button" :aria-pressed="motionPaused" title="系统的减少动画设置优先" @click="motionPaused = !motionPaused"><span aria-hidden="true">{{ motionPaused ? '▷' : 'Ⅱ' }}</span>{{ motionPaused ? '开启动效' : '暂停动效' }}</button><span class="hg-environment"><i aria-hidden="true" />教学模拟环境</span></div>
    </header>

    <div class="hg-content">
      <aside class="hg-story" aria-label="平台介绍">
        <p class="hg-kicker"><span aria-hidden="true" />让学习，循迹而行</p>
        <h1>每一次选择，<br />都让成长<span>更清晰。</span></h1>
        <p class="hg-intro">从第一张模拟银行卡，到第一笔基金申购。<br class="hg-desktop-break" />在完整的业务流程里，建立自己的投资认知。</p>

        <div class="hg-scene" aria-hidden="true">
          <div class="hg-orbit hg-orbit-one"><span /></div>
          <div class="hg-orbit hg-orbit-two"><span /></div>
          <div class="hg-scene-grid" />
          <div class="hg-chart-card">
            <div class="hg-chart-heading"><span>每一程，都有迹可循</span><span class="hg-chart-label">流程示意</span></div>
            <div class="hg-chart-title">从理解，到从容实践<span>YOUR LEARNING JOURNEY</span></div>
            <svg class="hg-chart" viewBox="0 0 420 125" fill="none">
              <defs><linearGradient id="hg-chart-fill" x1="210" y1="12" x2="210" y2="125" gradientUnits="userSpaceOnUse"><stop stop-color="#d78596" stop-opacity=".3" /><stop offset="1" stop-color="#d78596" stop-opacity="0" /></linearGradient></defs>
              <path class="hg-grid-line" d="M0 30H420M0 70H420M0 110H420" />
              <path class="hg-chart-area" d="M0 99C28 99 34 68 59 78S97 111 121 78S153 39 181 60S219 77 245 42S276 65 306 36S342 55 363 25S395 26 420 12V125H0Z" fill="url(#hg-chart-fill)" />
              <path class="hg-chart-path" d="M0 99C28 99 34 68 59 78S97 111 121 78S153 39 181 60S219 77 245 42S276 65 306 36S342 55 363 25S395 26 420 12" />
              <circle class="hg-chart-point" cx="245" cy="42" r="5" />
              <circle class="hg-chart-point hg-chart-point-end" cx="419" cy="12" r="5" />
            </svg>
            <div class="hg-chart-axis"><span>认识账户</span><span>理解交易</span><span>回顾记录</span></div>
          </div>
          <div class="hg-floating-card hg-account-card"><span class="hg-mini-icon">▤</span><div>多卡独立记账<small>资金来源，清晰可查</small></div><span class="hg-mini-check">✓</span></div>
          <div class="hg-floating-card hg-trade-card"><span class="hg-mini-icon">⇄</span><div>体验交易全流程<small>申请 · 确认 · 到账</small></div></div>
          <span class="hg-scene-caption">抽象学习轨迹 · 非实际行情或收益</span>
        </div>

        <div class="hg-features">
          <div><span>01 / ACCOUNT</span><strong>账户独立</strong><p>逐卡管理模拟资金</p></div>
          <div><span>02 / TRADE</span><strong>流程完整</strong><p>从申请到份额确认</p></div>
          <div><span>03 / RECORD</span><strong>记录可循</strong><p>每笔操作都有来路</p></div>
        </div>
      </aside>

      <main class="hg-main">
        <section class="hg-form-card" aria-labelledby="hg-auth-title">
          <div class="hg-card-top"><span class="hg-card-mark" aria-hidden="true">✳</span><span>学习，从这里继续</span></div>
          <p class="hg-form-kicker">{{ register ? 'START YOUR JOURNEY' : 'WELCOME BACK' }}</p>
          <h2 id="hg-auth-title">{{ register ? '开启你的学习旅程' : '欢迎回来' }}<span aria-hidden="true">.</span></h2>
          <p class="hg-form-description">{{ register ? '创建模拟账户，开始探索基金业务。' : '登录恒升，继续探索每一笔交易的来路。' }}</p>

          <div v-if="startError" class="hg-error" role="alert">{{ startError }} <button type="button" :disabled="authBusy" @click="$emit('retry')">重试连接</button></div>
          <form :aria-busy="authBusy" @submit.prevent="authenticate">
            <fieldset :disabled="authBusy">
              <label class="hg-field" for="hg-username"><span>用户名</span><span class="hg-input-wrap"><svg aria-hidden="true" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="8" r="3.2" /><path d="M5 21v-2a7 7 0 0 1 14 0v2" /></svg><input id="hg-username" v-model="username" name="username" autocomplete="username" required minlength="3" maxlength="50" placeholder="输入用户名" /></span></label>
              <label v-if="register" class="hg-field hg-extra-field" for="hg-display-name"><span>模拟持卡人 / 昵称</span><span class="hg-input-wrap"><svg aria-hidden="true" viewBox="0 0 24 24" fill="none"><rect x="3" y="5" width="18" height="14" rx="3" /><path d="M7 10h4M7 14h10" /></svg><input id="hg-display-name" v-model="displayName" name="displayName" autocomplete="nickname" required maxlength="60" placeholder="所有模拟卡使用此名称" /></span></label>
              <label class="hg-field" for="hg-password"><span>密码<small v-if="register">至少 10 个字符</small></span><span class="hg-input-wrap"><svg aria-hidden="true" viewBox="0 0 24 24" fill="none"><rect x="5" y="10" width="14" height="11" rx="3" /><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3" /></svg><input id="hg-password" v-model="password" name="password" :autocomplete="register ? 'new-password' : 'current-password'" type="password" required :minlength="register ? 10 : undefined" placeholder="输入密码" /></span></label>
              <p v-if="authError" class="hg-error" role="alert">{{ authError }}</p>
              <button class="hg-submit" type="submit" :disabled="authBusy"><span v-if="authBusy" class="hg-submit-spinner" aria-hidden="true" /><span>{{ authBusy ? '处理中…' : register ? '注册并登录' : '登录' }}</span><svg v-if="!authBusy" aria-hidden="true" viewBox="0 0 24 24" fill="none"><path d="M4 12h15m-6-6 6 6-6 6" /></svg></button>
            </fieldset>
          </form>

          <div class="hg-switch"><span>{{ register ? '已经有账户了？' : '第一次来到恒升？' }}</span><button type="button" :disabled="authBusy" @click="switchMode">{{ register ? '返回登录' : '注册账户' }}<span aria-hidden="true"> ↗</span></button></div>
          <p class="hg-form-note"><svg aria-hidden="true" viewBox="0 0 24 24" fill="none"><path d="m12 3 8 3v6c0 4-4 7-8 9-4-2-8-5-8-9V6l8-3Z" /><path d="m8.5 12 2.5 2.5 4.5-5" /></svg>仅使用虚拟资金，无需真实银行卡信息</p>
          <p v-if="demoCredentials" class="hg-demo">本地演示账户：demo / Demo12345!<br />本地管理员：admin / Admin12345!</p>
        </section>
        <p class="hg-outside-note">练习选择，理解规则，循序成长。</p>
      </main>
    </div>

    <footer class="hg-footer"><span>恒升基金模拟平台</span><span>教学模拟 · 虚拟资金 · 不涉及真实投资</span></footer>
  </div>
</template>

<style scoped>
.hg-gateway{--hg-ink:#482c35;--hg-muted:#80636e;--hg-line:#efdee2;--hg-accent:var(--brand,#b64c60);--hg-strong:var(--brand-strong,#923b50);position:relative;isolation:isolate;min-height:100svh;overflow:hidden;padding:36px clamp(24px,5vw,86px) 22px;color:var(--hg-ink);background:#fffafa;background-image:radial-gradient(ellipse at 6% 45%,#fae9ec 0,transparent 55%),radial-gradient(ellipse at 97% 5%,#fceef0 0,transparent 45%)}
.hg-header,.hg-content,.hg-footer{position:relative;z-index:1;max-width:1500px;margin-inline:auto}.hg-header{display:flex;align-items:center;justify-content:space-between;gap:16px}.hg-brand{display:flex;align-items:center;gap:12px;font-weight:650;font-size:24px;letter-spacing:3px}.hg-brand-mark{display:grid;place-items:center;width:43px;height:43px;border:1px solid #e1a9b5;border-radius:13px;background:#fff7f8;color:var(--hg-accent);font-size:25px;box-shadow:0 5px 16px #b64c6008}.hg-brand div>span{display:block;margin-top:5px;color:#947781;font-size:8px;letter-spacing:1.7px;font-weight:500}.hg-header-actions{display:flex;align-items:center;gap:16px}.hg-motion-toggle{display:flex;align-items:center;gap:6px;padding:5px 0;border:0;background:none;color:#8b5e6a;font-size:10px}.hg-motion-toggle>span{font-size:12px}.hg-environment{display:flex;align-items:center;gap:8px;padding:8px 12px;border:1px solid #ecd4db;border-radius:30px;background:#ffffff78;color:#8b5e6a;font-size:11px}.hg-environment i{width:5px;height:5px;border-radius:50%;background:#b66377;box-shadow:0 0 0 4px #b6637712}.hg-content{display:grid;grid-template-columns:minmax(0,1.15fr) minmax(340px,.85fr);align-items:center;gap:clamp(40px,7vw,120px);min-height:calc(100svh - 145px);padding-block:38px 34px}.hg-story{min-width:0;animation:hg-enter .8s ease-out both}.hg-kicker{display:flex;align-items:center;gap:10px;color:#9e6273;font-size:11px;letter-spacing:3px;margin:0 0 18px}.hg-kicker>span{display:block;width:24px;height:1px;background:#c98b9b}.hg-story h1{font-size:clamp(34px,3.6vw,57px);line-height:1.38;letter-spacing:-1.8px;font-weight:600;margin:0 0 20px}.hg-story h1>span{color:var(--hg-accent)}.hg-intro{font-size:13px;line-height:1.95;color:var(--hg-muted);margin:0;max-width:460px}.hg-features{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px;max-width:510px;margin-top:8px}.hg-features>div{border-top:1px solid #e5cbd3;padding-top:15px}.hg-features span{display:block;color:#ac7b89;font-size:8px;letter-spacing:1.2px;margin-bottom:10px}.hg-features strong{font-size:13px;font-weight:600}.hg-features p{font-size:10px;line-height:1.7;color:#947b83;margin:6px 0 0}
.hg-scene{position:relative;height:285px;max-width:550px;margin:13px -5px 8px}.hg-scene-grid{position:absolute;inset:24px -30px 0;background-image:linear-gradient(#d7aeba16 1px,transparent 1px),linear-gradient(90deg,#d7aeba16 1px,transparent 1px);background-size:34px 34px;mask-image:radial-gradient(ellipse,#000 10%,transparent 68%);transform:perspective(450px) rotateX(28deg) rotateZ(-8deg)}.hg-orbit{position:absolute;border:1px solid #dfb7c24d;border-radius:50%;pointer-events:none}.hg-orbit-one{width:350px;height:350px;left:70px;top:-25px;transform:rotate(-25deg);animation:hg-orbit 42s linear infinite}.hg-orbit-two{width:450px;height:230px;left:2px;top:53px;transform:rotate(-20deg)}.hg-orbit>span{position:absolute;left:26px;top:26px;width:7px;height:7px;background:#cf94a4;border-radius:50%;box-shadow:0 0 0 6px #cf94a419}.hg-chart-card{position:absolute;left:8%;right:8%;top:37px;padding:20px 24px 16px;border:1px solid #ffffff;border-radius:16px;background:linear-gradient(125deg,#ffffffed,#fff8faeb);box-shadow:0 25px 65px #9d50611a,0 2px 8px #9d506107;transform:rotate(-3deg);animation:hg-card-in 1.1s .15s both}.hg-chart-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;color:#906b78;font-size:9px}.hg-chart-label{border:1px solid #ecd8df;border-radius:20px;padding:3px 7px;font-size:8px}.hg-chart-title{font-size:15px;font-weight:550;letter-spacing:.3px;margin-top:13px}.hg-chart-title>span{display:block;font-size:7px;letter-spacing:1.5px;color:#b3919c;margin-top:7px}.hg-chart{display:block;width:100%;height:auto;margin-top:10px;overflow:visible}.hg-grid-line{stroke:#eddde2;stroke-width:1;stroke-dasharray:3 5}.hg-chart-path{stroke:#b85872;stroke-width:2.5;stroke-linecap:round;stroke-dasharray:650;stroke-dashoffset:650;animation:hg-draw 2.1s .4s ease forwards}.hg-chart-point{fill:#fff;stroke:#bd6a80;stroke-width:2}.hg-chart-point-end{animation:hg-glow 3s 2.2s ease-in-out infinite}.hg-chart-area{animation:hg-fade 1.8s .4s both}.hg-chart-axis{display:flex;justify-content:space-between;color:#b499a3;font-size:7px;margin-top:5px}.hg-floating-card{position:absolute;display:flex;align-items:center;gap:10px;padding:12px 14px;border:1px solid #fff;border-radius:12px;background:#fffdfdf5;box-shadow:0 12px 38px #9d506117;font-size:10px;font-weight:550;animation:hg-float 6s ease-in-out infinite}.hg-floating-card small{display:block;color:#a68a95;font-size:8px;font-weight:400;margin-top:5px}.hg-account-card{left:-1%;bottom:35px;animation-delay:-2s;transform:rotate(-5deg)}.hg-trade-card{right:0;top:25px;animation-delay:-4s}.hg-mini-icon{display:grid;place-items:center;flex:none;width:29px;height:29px;border-radius:8px;background:#faeaf0;color:#b75b75;font-size:18px;font-weight:400}.hg-mini-check{margin-left:7px;color:#ad7385;font-size:12px}.hg-scene-caption{position:absolute;right:36px;bottom:5px;color:#ad8f9a;font-size:8px;letter-spacing:.5px}.hg-main{min-width:0;animation:hg-enter .8s .14s ease-out both}.hg-form-card{position:relative;width:100%;max-width:450px;margin-inline:auto;padding:34px clamp(25px,3vw,43px) 24px;background:#ffffffdb;border:1px solid #fff;border-radius:24px;box-shadow:0 25px 90px #8b3d5010,0 2px 12px #8b3d5005}.hg-card-top{display:flex;align-items:center;justify-content:space-between;font-size:9px;letter-spacing:1px;color:#a88b95;margin-bottom:26px}.hg-card-mark{display:grid;place-items:center;width:34px;height:34px;border:1px solid #efdce3;border-radius:50%;font-size:22px;color:#bb7c8e;font-weight:400}.hg-form-kicker{color:var(--hg-accent);font-size:9px;letter-spacing:2.2px;margin:0 0 10px}.hg-form-card h2{font-size:30px;letter-spacing:-1px;font-weight:600;margin:0 0 10px;line-height:1.4}.hg-form-card h2>span{color:#c7788d}.hg-form-description{font-size:12px;line-height:1.8;color:var(--hg-muted);margin:0 0 28px}.hg-form-card form{margin:0}.hg-form-card fieldset{min-width:0;padding:0;margin:0;border:0}.hg-field{display:block;margin-bottom:19px;color:#68505a;font-size:11px}.hg-field>span:first-child{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-bottom:9px}.hg-field small{color:#a68b95;font-size:10px}.hg-input-wrap{display:flex;align-items:center;min-width:0;gap:11px;padding:0 14px;border:1px solid #eadce1;border-radius:9px;background:#fffcfd;transition:border-color .2s,box-shadow .2s,background .2s}.hg-input-wrap:focus-within{border-color:#c57b91;box-shadow:0 0 0 3px #d69aad1c;background:#fff}.hg-input-wrap>svg{flex:none;width:16px;height:16px;stroke:#b294a0;stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round}.hg-input-wrap input{min-width:0;flex:1;height:46px;padding:0;border:0;border-radius:0;outline:none!important;box-shadow:none!important;background:transparent;color:var(--hg-ink);font-size:12px}.hg-input-wrap input::placeholder{color:#866d78}.hg-extra-field{animation:hg-enter .25s ease both}.hg-submit{display:flex;align-items:center;justify-content:center;gap:10px;width:100%;min-height:47px;border:1px solid transparent;border-radius:9px;padding:12px 17px;margin-top:6px;background:linear-gradient(110deg,#bd5d75,var(--hg-accent));color:#fff;font-size:12px;font-weight:550;box-shadow:0 8px 22px #b64c6024;transition:transform .2s,box-shadow .2s,background .2s}.hg-submit:hover:not(:disabled){transform:translateY(-2px);background:var(--hg-strong);box-shadow:0 12px 25px #b64c6030}.hg-submit:active:not(:disabled){transform:translateY(0)}.hg-submit>svg{width:17px;height:17px;stroke:currentColor;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round;transition:transform .2s}.hg-submit:hover>svg{transform:translateX(3px)}.hg-submit-spinner{width:15px;height:15px;border:1.5px solid #ffffff55;border-top-color:#fff;border-radius:50%;animation:hg-spin .8s linear infinite}.hg-switch{display:flex;justify-content:center;flex-wrap:wrap;gap:6px;font-size:11px;color:#a18a94;margin:22px 0}.hg-switch button,.hg-error button{padding:0;border:0;background:none;color:var(--hg-accent);font-size:inherit;font-weight:550}.hg-form-note{display:flex;justify-content:center;align-items:center;gap:7px;border-top:1px solid #f2e7eb;padding-top:18px;margin:0;color:#a18c95;font-size:9px;line-height:1.8}.hg-form-note svg{flex:none;width:13px;height:13px;stroke:#b597a3;stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round}.hg-demo{font-size:10px;color:#9b808c;line-height:1.8;background:#fcf4f6;border-radius:7px;padding:10px;margin:16px 0 0;overflow-wrap:anywhere}.hg-error{padding:11px 12px;border:1px solid #eecad2;border-radius:8px;background:#fff3f5;color:#a83f58;font-size:11px;line-height:1.8;margin:0 0 16px;overflow-wrap:anywhere}.hg-error button{text-decoration:underline}.hg-outside-note{margin:20px 0 0;text-align:center;color:#b397a2;font-size:10px;letter-spacing:2px}.hg-footer{display:flex;justify-content:space-between;gap:16px;color:#b198a3;font-size:9px;letter-spacing:.5px}.hg-ambient{position:absolute;z-index:0;border:1px solid #dba8b425;border-radius:50%;pointer-events:none}.hg-ambient-one{width:780px;height:780px;left:-440px;top:80px;box-shadow:0 0 0 55px #dba8b407,0 0 0 110px #dba8b405}.hg-ambient-two{width:520px;height:520px;right:-340px;top:-190px;box-shadow:0 0 0 45px #dba8b408}.hg-gateway button:focus-visible{outline:3px solid #d69aad;outline-offset:4px}.hg-gateway button:disabled{cursor:wait}.hg-form-card fieldset:disabled{opacity:.75}.hg-paused *,.hg-paused *::before,.hg-paused *::after{animation-play-state:paused!important}
@keyframes hg-enter{from{opacity:0;transform:translateY(18px)}to{opacity:1;transform:translateY(0)}}@keyframes hg-card-in{from{opacity:0;transform:translateY(15px) rotate(0)}to{opacity:1;transform:translateY(0) rotate(-3deg)}}@keyframes hg-fade{from{opacity:0}to{opacity:1}}@keyframes hg-draw{to{stroke-dashoffset:0}}@keyframes hg-float{0%,100%{translate:0 0}50%{translate:0 -7px}}@keyframes hg-orbit{to{transform:rotate(335deg)}}@keyframes hg-glow{0%,100%{stroke-width:2}50%{stroke-width:5;stroke-opacity:.45}}@keyframes hg-spin{to{transform:rotate(360deg)}}
@media(min-width:1700px){.hg-content{gap:140px}.hg-story h1{font-size:62px}.hg-scene{margin-block:30px;height:310px}.hg-chart-card{top:45px}.hg-form-card{padding-block:40px 30px}}
@media(max-width:1100px){.hg-gateway{padding-inline:32px}.hg-content{gap:40px;grid-template-columns:minmax(0,1fr) minmax(330px,.9fr)}.hg-story h1{font-size:39px}.hg-intro{font-size:12px}.hg-chart-card{left:5%;right:3%;padding:18px}.hg-scene{height:260px}.hg-orbit-one{left:25px}.hg-orbit-two{left:-30px}.hg-account-card{left:-7px;bottom:36px}.hg-trade-card{right:-15px;top:17px}.hg-chart-title{font-size:13px}.hg-features{gap:11px}.hg-features p{font-size:9px}.hg-form-card{padding-inline:27px}}
@media(max-width:800px){.hg-gateway{padding:24px 24px 20px}.hg-content{grid-template-columns:1fr;gap:28px;min-height:auto;max-width:560px;padding-block:38px 30px}.hg-brand{font-size:21px}.hg-brand-mark{height:39px;width:39px;font-size:22px}.hg-brand div>span{font-size:7px;letter-spacing:1px}.hg-story h1{font-size:36px;line-height:1.35;letter-spacing:-1px}.hg-intro{max-width:100%;font-size:12px}.hg-kicker{font-size:10px;margin-bottom:14px}.hg-scene{height:230px;max-width:470px;margin:8px auto 2px}.hg-chart-card{left:9%;right:9%;top:25px;padding:17px 20px 12px}.hg-chart-title{font-size:13px}.hg-chart{height:84px}.hg-account-card{left:0;bottom:30px}.hg-trade-card{right:0;top:10px}.hg-orbit-one{width:280px;height:280px;left:90px;top:-20px}.hg-orbit-two{left:5px;top:35px;width:420px;height:200px}.hg-scene-caption{bottom:0;right:20px}.hg-features{max-width:none;margin-top:0}.hg-features>div{padding-top:12px}.hg-features p{font-size:10px}.hg-main{margin-top:5px}.hg-form-card{max-width:100%;padding:25px 30px}.hg-card-top{margin-bottom:20px}.hg-footer{justify-content:center;flex-wrap:wrap;gap:7px 16px;font-size:8px}.hg-ambient-one{left:-620px}.hg-outside-note{font-size:9px;margin-top:17px}}
@media(max-width:420px){.hg-gateway{padding-inline:19px}.hg-header-actions{gap:10px}.hg-motion-toggle{font-size:8px;gap:4px}.hg-environment{font-size:9px;padding:7px 9px}.hg-brand{gap:8px}.hg-brand div>span{max-width:125px;line-height:1.5;letter-spacing:.7px}.hg-content{padding-top:31px;gap:23px}.hg-story h1{font-size:32px}.hg-desktop-break{display:none}.hg-scene{height:206px;margin-inline:-2px}.hg-chart-card{left:8%;right:6%;top:25px;padding:14px 15px 12px}.hg-chart-title{font-size:12px}.hg-chart{height:70px}.hg-chart-heading{font-size:8px}.hg-chart-label{font-size:7px}.hg-floating-card{gap:7px;padding:9px 10px;font-size:8px;border-radius:10px}.hg-floating-card small{font-size:7px}.hg-mini-icon{width:24px;height:24px;font-size:16px}.hg-account-card{bottom:22px;left:-2px}.hg-trade-card{right:-3px;top:13px}.hg-mini-check{margin-left:0}.hg-orbit-one{width:235px;height:235px;left:65px}.hg-orbit-two{left:-6px;top:38px;width:330px;height:160px}.hg-scene-caption{font-size:7px;right:5px;bottom:1px}.hg-features{gap:10px}.hg-features span{font-size:7px;letter-spacing:.4px}.hg-features strong{font-size:12px}.hg-features p{font-size:9px}.hg-form-card{padding:24px 23px;border-radius:19px}.hg-form-card h2{font-size:27px}.hg-form-description{font-size:11px}.hg-card-top{font-size:8px}.hg-form-note{font-size:8px}}
@media(prefers-reduced-motion:reduce){.hg-gateway *,.hg-gateway *::before,.hg-gateway *::after{animation:none!important;transition:none!important}.hg-chart-path{stroke-dashoffset:0}.hg-submit:hover:not(:disabled){transform:none}.hg-submit:hover>svg{transform:none}}
</style>
