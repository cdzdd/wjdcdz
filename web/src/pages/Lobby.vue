<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AuthGateway from '../components/AuthGateway.vue'
import { logout } from '../api'
import { perform, user } from '../state'
import { isStaff } from '../router'
import '../lobby.css'

type NewsItem = { title: string; source: string; publishedAt: string; url: string }
const route = useRoute()
const workbench = computed(() => isStaff(user.value?.role) ? '/admin/overview' : '/dashboard')
const entry = computed(() => user.value ? workbench.value : '/login')
const news = ref<NewsItem[]>([]), loading = ref(true), stale = ref(false), updatedAt = ref<string | null>(null)
const newsRequest = new AbortController()
const statsColumn = 'https://www.stats.gov.cn/sj/zxfb/'
const beijingTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})
const sections = [['lobby-top', '大厅'], ['lobby-guide', '投教指南'], ['lobby-news', '财经导读'], ['lobby-sources', '官方公告']]
const guide = [
  ['认识模拟账户', '区分可用资金、冻结资金与持仓。'],
  ['了解交易流程', '学习申购、确认和赎回的状态变化。'],
  ['回顾投资记录', '按交易账户核对资金来源和份额。'],
]
const readings = [
  '申购填金额，赎回填份额', '周末申请归属哪一个交易日？', '预估份额与确认份额为何不同？',
  '更换默认卡会影响已有持仓吗？', '现金分红与红利再投资的区别', '风险测评有效期在哪里看？',
  '赎回款会回到哪张模拟银行卡？', '如何看懂净值曲线与收益记录',
]
const lessons = [
  ['宏观课堂', '读懂 CPI 与 PPI，理解价格变化'], ['指数课堂', '宽基指数与行业指数有什么区别'],
  ['样本复盘', '用三条模拟净值曲线观察不同产品的波动'], ['基金观察', '货币基金的万份收益与七日年化'],
  ['费用课堂', '看懂申购费、赎回费和管理费'], ['公告读法', '如何识别开放、暂停与限额的关键字段'],
  ['资金观察', '从可用、冻结与在途资金看账户状态'], ['组合练习', '用虚拟持仓观察资产分布'],
]
const briefs = [
  '核对申请归属日与计划确认日。', '在模拟订单中找到资金冻结记录。',
  '查阅基金定期报告中的资产组合与费用。', '查看赎回款的原结算路径。',
  '用累计净值理解分红前后的变化。', '区分同比、环比与累计增速。',
  '了解同一基金的 A 类与 C 类份额。', '核对产品开放状态与所选模拟银行卡。',
]
const sources = [
  ['国家统计局', '宏观数据发布', statsColumn],
  ['中国证监会', '监管要闻与公告', 'https://www.csrc.gov.cn/'],
  ['上海证券交易所', '上市公司公告', 'https://www.sse.com.cn/disclosure/listedinfo/announcement/'],
]

function scrollTo(id: string) { document.getElementById(id)?.scrollIntoView({ block: 'start' }) }
function numberLabel(index: number) { return String(index + 1).padStart(2, '0') }
function formatNewsTime(value: string, includeYear = false) {
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return includeYear ? value : value.slice(5)
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const parts = Object.fromEntries(beijingTime.formatToParts(date).map(({ type, value }) => [type, value]))
  const day = `${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
  return includeYear ? `${parts.year}-${day}` : day
}
function officialNews(item: unknown): item is NewsItem {
  if (!item || typeof item !== 'object') return false
  const row = item as Partial<NewsItem>
  if (typeof row.title !== 'string' || !row.title.trim() || typeof row.source !== 'string' ||
      typeof row.publishedAt !== 'string' || typeof row.url !== 'string') return false
  try {
    const url = new URL(row.url)
    return url.protocol === 'https:' && ['www.stats.gov.cn', 'stats.gov.cn'].includes(url.hostname) &&
      !url.port && !url.username && !url.password
  } catch { return false }
}

async function loadNews() {
  try {
    const response = await fetch('/api/public/news', { credentials: 'omit', signal: newsRequest.signal, headers: { Accept: 'application/json' } })
    if (!response.ok) return
    const result = await response.json()
    if (Array.isArray(result?.items)) news.value = result.items.filter(officialNews)
    stale.value = result?.stale === true
    updatedAt.value = typeof result?.updatedAt === 'string' ? result.updatedAt : null
  } catch {
    // The official column remains available when the feed cannot be loaded.
  } finally { loading.value = false }
}

async function leave() { await perform(logout, () => { user.value = null }) }
onMounted(loadNews)
onBeforeUnmount(() => newsRequest.abort())
</script>

<template>
  <div id="lobby-top" class="lobby">
    <header class="lobby-header">
      <div class="lobby-container lobby-header-row">
        <RouterLink to="/" class="lobby-logo"><span class="lobby-logo-mark" aria-hidden="true">恒</span><div><strong>恒升基金模拟平台</strong><small>投资学习与模拟实践</small></div></RouterLink>
        <nav class="lobby-nav" aria-label="大厅导航">
          <button v-for="[id, label] in sections" :key="id" type="button" :aria-current="id === 'lobby-top' ? 'page' : undefined" @click="scrollTo(id)">{{ label }}</button>
        </nav>
        <div class="lobby-account">
          <small>● 教学模拟环境</small>
          <RouterLink v-if="!user" id="lobby-login" to="/login" class="lobby-login">登录 <span aria-hidden="true">→</span></RouterLink>
          <template v-else><RouterLink :to="workbench" class="lobby-login">我的工作台 <span aria-hidden="true">→</span></RouterLink><button type="button" class="lobby-signout" @click="leave">退出</button></template>
        </div>
      </div>
    </header>

    <main class="lobby-container lobby-main">
      <section class="lobby-banner" aria-label="模拟广告">
        <svg class="lobby-banner-art" viewBox="0 0 760 180" preserveAspectRatio="none" aria-hidden="true" focusable="false">
          <path d="M96 0h110l160 90-160 90H96l160-90Z" fill="#fff8cf" opacity=".53"/>
          <path d="M252 0h78l162 90-162 90h-78l162-90Z" fill="#fff3a7" opacity=".72"/>
          <path d="M411 0h105l164 90-164 90H411l164-90Z" fill="#f07b15" opacity=".19"/>
          <path d="M529 0h84l147 81v18l-147 81h-84l164-90Z" fill="#d84916" opacity=".19"/>
          <path d="M245 24v28m-14-14h28M493 132v24m-12-12h24M630 17v20m-10-10h20" fill="none" stroke="#fff8d7" stroke-width="3"/>
        </svg>
        <div class="lobby-banner-content">
          <div class="lobby-banner-label"><b>模拟广告</b><span>恒升 · 基金训练营</span></div>
          <h1>模拟投资挑战 <em>三步上手</em></h1>
          <p>开立模拟账户，练习基金交易，再用记录复盘每一步。</p>
        </div>
        <ul class="lobby-banner-points" aria-label="训练内容">
          <li v-for="point in ['模拟开户', '练习申购', '跟踪确认', '复盘持仓']" :key="point"><span aria-hidden="true">✓</span>{{ point }}</li>
        </ul>
        <div class="lobby-banner-action"><RouterLink :to="entry" class="lobby-banner-button">立即体验 <span aria-hidden="true">→</span></RouterLink><small>教学展示 · 使用虚拟资金</small></div>
      </section>

      <div class="lobby-columns">
        <aside class="lobby-side" aria-label="学习与模拟入口">
          <section id="lobby-guide" class="lobby-card" aria-labelledby="lobby-guide-title">
            <div class="lobby-card-head"><h2 id="lobby-guide-title">新手练习</h2><span class="lobby-chip">入门三步</span></div>
            <ol class="lobby-list">
              <li v-for="([title, description], index) in guide" :key="title" class="lobby-entry"><span class="lobby-entry-number" aria-hidden="true">{{ numberLabel(index) }}</span><div><strong>{{ title }}</strong><p>{{ description }}</p></div></li>
            </ol>
            <RouterLink to="/dashboard" class="lobby-guide-button">进入资产工作台 <span aria-hidden="true">→</span></RouterLink>
          </section>
          <section class="lobby-card" aria-labelledby="lobby-reading-title">
            <div class="lobby-card-head"><h2 id="lobby-reading-title">投教速读</h2><span class="lobby-chip">示例</span></div>
            <ul class="lobby-list"><li v-for="reading in readings" :key="reading" class="lobby-reading">{{ reading }}</li></ul>
          </section>
          <section class="lobby-card lobby-note-card"><h2>练习提示</h2><p class="lobby-note">查看模拟指标时，先区分账户余额、持仓市值和在途资金。</p></section>
        </aside>

        <div class="lobby-side lobby-middle">
          <section id="lobby-news" class="lobby-card" aria-labelledby="lobby-news-title">
            <div class="lobby-card-head"><h2 id="lobby-news-title">国家统计局数据发布</h2><span class="lobby-chip">官方原文</span></div>
            <p class="lobby-attribution">引自国家统计局网站（www.stats.gov.cn）</p>
            <ul v-if="news.length" class="lobby-list">
              <li v-for="item in news" :key="item.url"><a class="lobby-official-item" :href="item.url" target="_blank" rel="noopener noreferrer"><strong>{{ item.title }}</strong><small>{{ item.source }} <time :datetime="item.publishedAt">{{ formatNewsTime(item.publishedAt) }}</time></small></a></li>
            </ul>
            <p v-else class="lobby-news-fallback" role="status">{{ loading ? '正在读取官方数据发布。' : '暂无可展示条目，可访问官方栏目查看原文。' }} <a :href="statsColumn" target="_blank" rel="noopener noreferrer">前往数据发布栏目 ↗</a></p>
            <p v-if="news.length && (updatedAt || stale)" class="lobby-news-caption">{{ stale ? '上次获取的条目' : '获取时间' }}<template v-if="updatedAt"> · <time :datetime="updatedAt">{{ formatNewsTime(updatedAt, true) }}</time>（北京时间）</template></p>
          </section>
          <section class="lobby-card" aria-labelledby="lobby-lessons-title">
            <div class="lobby-card-head"><h2 id="lobby-lessons-title">财经导读</h2><span class="lobby-chip">示例内容</span></div>
            <article class="lobby-lead"><small>投教课堂 · 示例</small><h3>一笔模拟申购如何确认</h3><p>从提交申请、资金冻结到份额确认，了解每次状态变化。</p></article>
            <ul class="lobby-list"><li v-for="[category, title] in lessons" :key="title" class="lobby-news-row"><span>{{ category }}</span><strong>{{ title }}</strong></li></ul>
          </section>
        </div>

        <aside class="lobby-side lobby-right" aria-label="公告与资讯来源">
          <section class="lobby-card" aria-labelledby="lobby-flash-title">
            <div class="lobby-card-head"><h2 id="lobby-flash-title">练习快讯</h2><span class="lobby-chip">示例</span></div>
            <ul class="lobby-list"><li v-for="(brief, index) in briefs" :key="brief" class="lobby-brief"><span>{{ numberLabel(index) }}</span><p>{{ brief }}</p></li></ul>
          </section>
          <section id="lobby-sources" class="lobby-card" aria-labelledby="lobby-sources-title">
            <div class="lobby-card-head"><h2 id="lobby-sources-title">官方资讯入口</h2><span class="lobby-chip">打开原站</span></div>
            <ul class="lobby-list">
              <li v-for="[name, description, url] in sources" :key="url"><a class="lobby-source" :href="url" target="_blank" rel="noopener noreferrer"><div><strong>{{ name }}</strong><small>{{ description }}</small></div><span class="lobby-source-arrow" aria-hidden="true">↗</span></a></li>
            </ul>
          </section>
          <section class="lobby-card" aria-labelledby="lobby-announcements-title">
            <div class="lobby-card-head"><h2 id="lobby-announcements-title">平台公告</h2><span class="lobby-chip">示例</span></div>
            <ul class="lobby-list"><li class="lobby-announcement">课程练习入口与使用说明</li><li class="lobby-announcement">模拟资金与交易规则说明</li></ul>
          </section>
        </aside>
      </div>
      <footer class="lobby-footer"><span>恒升基金模拟平台 · 教学模拟</span><span>课程练习使用虚拟资金</span></footer>
    </main>
  </div>
  <AuthGateway v-if="route.name === 'login'"/>
</template>
