<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../../api'
import { usePage } from '../../state'
import { decimal } from '../../domain'
import type { Row } from '../../domain'
import PageHeader from '../../components/PageHeader.vue'
import DataTable from '../../components/DataTable.vue'
import LoadState from '../../components/LoadState.vue'
const dashboard=ref<Row>({}),jobs=ref<Row[]>([]),reconciliation=ref<Row>({ok:true,issues:[]})
const {loading,error,load}=usePage(async()=>{[dashboard.value,jobs.value,reconciliation.value]=await Promise.all([api.get('/admin/dashboard'),api.get('/admin/jobs'),api.get('/admin/reconciliation')])})
</script>
<template><PageHeader title="运营总览" subtitle="基金销售、登记确认与资金支付的运营概览。"><RouterLink to="/admin/trading" class="button primary">运行交易任务 ↗</RouterLink><button class="button" @click="load">刷新</button></PageHeader><LoadState :loading="loading" :error="error" @retry="load"/><section class="asset-hero"><p class="muted">平台模拟资产总额（元）</p><div class="asset-value">{{ decimal(dashboard.totalAssets) }}</div><div class="hero-bottom"><div>待确认交易<b>{{ dashboard.pendingOrders ?? '—' }}</b></div><div>待支付款项<b>{{ dashboard.pendingPayments ?? '—' }}</b></div></div></section><div class="metrics"><div class="metric"><p>客户数</p><b>{{ dashboard.users ?? '—' }}</b><small>包含模拟投资者</small></div><div class="metric"><p>交易账户</p><b>{{ dashboard.accounts ?? '—' }}</b><small>逐卡管理资金</small></div><div class="metric"><p>基金类别</p><b>{{ dashboard.funds ?? '—' }}</b><small>独立可交易产品</small></div><div class="metric"><p>累计订单</p><b>{{ dashboard.orders ?? '—' }}</b><small>完整保留历史</small></div></div><div class="notice" :class="{error:!reconciliation.ok}">{{ reconciliation.ok?'资金与持仓对账通过':'存在对账异常，请前往运营与审计检查' }} <RouterLink to="/admin/system">查看对账 →</RouterLink></div><section class="panel"><div class="section-title"><h2>最近任务</h2><RouterLink to="/admin/trading">任务中心 →</RouterLink></div><DataTable :rows="jobs.slice(0,12)" :columns="[{key:'type',label:'任务'}, {key:'date',label:'业务日期'}, {key:'processed',label:'成功'}, {key:'waiting',label:'等待'}, {key:'failed',label:'失败'}, {key:'createdAt',label:'运行时间',kind:'date'}]"/></section><div class="grid-two"><section class="panel"><h2>产品数据</h2><p class="muted">维护产品开放状态、费用与风险等级；发布归属日期净值和交易日历。</p><RouterLink class="button" to="/admin/products">维护产品 →</RouterLink></section><section class="panel"><h2>异常处置</h2><p class="muted">查询收款受阻款项和换卡申请，授权路径变更保留审计与历史来源。</p><RouterLink class="button" to="/admin/customers">客户与账户 →</RouterLink></section></div></template>
