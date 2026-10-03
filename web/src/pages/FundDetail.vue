<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
import { usePage } from '../state'
import { decimal, percent, label } from '../domain'
import type { Row } from '../domain'
import PageHeader from '../components/PageHeader.vue'
import LoadState from '../components/LoadState.vue'
import NavChart from '../components/NavChart.vue'
import TradeDialog from '../components/TradeDialog.vue'
const route = useRoute(), fund = ref<Row>({}), history = ref<Row[]>([]), trade = ref(false), type = ref<'PURCHASE'|'REDEMPTION'>('PURCHASE')
const { loading, error, load } = usePage(async () => { [fund.value, history.value] = await Promise.all([api.get('/fund-classes/' + route.params.id), api.get('/fund-classes/' + route.params.id + '/history')]); history.value.sort((a,b) => String(a.date).localeCompare(String(b.date))) })
function open(action: 'PURCHASE'|'REDEMPTION') { type.value = action; trade.value = true }
</script>
<template><PageHeader :title="fund.name || '基金详情'" :subtitle="(fund.code || '') + ' · ' + (fund.manager || '')"><RouterLink to="/market" class="button">返回市场</RouterLink><button class="button primary" :disabled="!fund.purchaseOpen && fund.state !== 'RAISING'" @click="open('PURCHASE')">{{ fund.state === 'RAISING' ? '认购' : '申购' }}</button><button class="button" :disabled="!fund.redemptionOpen" @click="open('REDEMPTION')">赎回</button></PageHeader><LoadState :loading="loading" :error="error" @retry="load"/><div class="metrics"><div class="metric"><p>单位净值</p><b>{{ decimal(fund.nav,6) }}</b><small>{{ fund.navDate || '尚未发布' }}</small></div><div class="metric"><p>产品风险</p><b>R{{ fund.riskLevel || '—' }}</b><small>{{ label(fund.type) }}</small></div><div class="metric"><p>申购费率</p><b>{{ percent(fund.purchaseRate) }}</b><small>前端外扣法 · 服务端最终核算</small></div><div class="metric"><p>赎回基础费率</p><b>{{ percent(fund.redemptionRate) }}</b><small>实际按来源批次及持有期核算</small></div></div><section class="panel"><div class="section-title"><h2>{{ fund.type === 'MONEY' ? '万份收益走势' : '单位净值走势' }}</h2><span class="muted">模拟数据</span></div><NavChart :history="history" :money="fund.type === 'MONEY'"/></section><section class="panel"><h2>产品与交易规则</h2><div class="detail-facts"><div><span>运作状态</span>{{ label(fund.state) }}</div><div><span>最低申购（元）</span>{{ decimal(fund.minPurchase) }}</div><div><span>最低赎回（份）</span>{{ decimal(fund.minRedemption,4) }}</div><div><span>登记机构</span>{{ fund.registrarName || 'TA ' + (fund.registrarId || '—') }}</div><div><span>申购开放</span>{{ fund.purchaseOpen ? '开放' : '暂停' }}</div><div><span>赎回开放</span>{{ fund.redemptionOpen ? '开放' : '暂停' }}</div><div><span>归属交易日截止</span>15:00（北京时间）</div><div><span>模拟确认与付款</span>默认 T+1 确认 / 赎回 T+3 付款</div></div><p class="notice" style="margin-top:24px">严格早于15:00的申请归属当日，否则顺延下一开放日。缺少适用净值时保持待确认；不使用最近一日净值替代。具体以申请确认结果为准。</p><p v-if="fund.type === 'MONEY'" class="muted">货币基金采用净值1的独立收益模型，每日万份收益用于结转；七日年化仅为展示指标。模拟申购确认后的下一自然日开始计息。货币基金不承诺保本或收益。</p></section><TradeDialog v-model="trade" :fund="fund" :type="type" :offering="type === 'PURCHASE' && fund.state === 'RAISING'"/></template>
