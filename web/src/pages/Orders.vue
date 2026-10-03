<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessageBox } from 'element-plus'
import { api } from '../api'
import { usePage, perform, busy } from '../state'
import { label, cell, requestKey } from '../domain'
import type { Row } from '../domain'
import PageHeader from '../components/PageHeader.vue'
import DataTable from '../components/DataTable.vue'
import LoadState from '../components/LoadState.vue'
import RedemptionGroupDialog from '../components/RedemptionGroupDialog.vue'
const orders = ref<Row[]>([]), cards = ref<Row[]>([]), account = ref(''), status = ref(''), detail = ref<Row | null>(null), detailOpen = ref(false)
const groupOpen=ref(false),groupId=ref<number>()
const { loading,error,load } = usePage(async () => { [orders.value,cards.value] = await Promise.all([api.get('/orders' + (account.value ? '?tradingAccountId=' + account.value : '')), api.get('/bank-cards')]) })
const visible = computed(() => orders.value.filter(o => !status.value || o.status === status.value))
async function cancel(row: Row) { try { await ElMessageBox.confirm('仅归属交易日截止前、尚未确认的申请可以撤销。确认申请撤单？','撤销交易',{confirmButtonText:'申请撤单',cancelButtonText:'保留交易'}) } catch { return }; await perform(() => api.post('/orders/' + row.id + '/cancel',{requestKey:requestKey()}),load) }
async function show(row: Row) { await perform(async () => { detail.value = await api.get('/orders/' + row.id); detailOpen.value=true }) }
const detailLabels: Record<string,string> = { orderNo:'订单编号',type:'业务类型',status:'处理状态',fundName:'产品',tradingAccountId:'来源交易账户',sourceCardId:'历史来源卡',bankName:'银行',bindingVersion:'来源绑定版本',amount:'申请金额',shares:'成交/申请份额',nav:'成交净值',fee:'费用',netAmount:'净额',applicationDate:'归属交易日',confirmationDate:'计划确认日',paymentDate:'计划支付日',reason:'等待/失败原因',groupId:'交易组',createdAt:'申请时间' }
</script>
<template><PageHeader title="交易记录" subtitle="从申请到确认再到账，每一步都可查询。"><a href="/api/exports/orders" download class="button">导出 CSV</a><button class="button" @click="load">刷新</button></PageHeader><LoadState :loading="loading" :error="error" @retry="load"/><section class="panel"><div class="toolbar filters"><select v-model="account" @change="load"><option value="">所有来源账户</option><option v-for="card in cards" :key="card.id" :value="String(card.tradingAccountId)">{{ card.bankName }} · {{ card.accountNo }}</option></select><select v-model="status"><option value="">所有状态</option><option v-for="s in ['SUBMITTED','CONFIRMED','COMPLETED','CANCELLED','FAILED']" :key="s" :value="s">{{ label(s) }}</option></select></div><DataTable :rows="visible" :columns="[{key:'orderNo',label:'订单编号'}, {key:'groupId',label:'赎回组'}, {key:'fundName',label:'基金'}, {key:'type',label:'业务'}, {key:'bankName',label:'来源银行'}, {key:'tradingAccountId',label:'交易账户'}, {key:'status',label:'状态',kind:'state'}, {key:'amount',label:'金额（元）',kind:'money'}, {key:'shares',label:'份额',kind:'shares'}, {key:'applicationDate',label:'交易日'}, {key:'reason',label:'等待/失败原因'}]"><template #cell-groupId="{row}"><button v-if="row.groupId" class="text-button" @click="groupId=row.groupId;groupOpen=true">#{{ row.groupId }}</button><span v-else>—</span></template><template #actions="{row}"><button class="text-button" :disabled="busy>0" @click="show(row)">详情</button><button v-if="row.status==='SUBMITTED'" class="text-button danger" :disabled="busy>0" @click="cancel(row)">撤单</button></template></DataTable></section><p class="muted small">申购确认并登记份额后完成；赎回“待到账”表示已确认但尚未支付。交易组的子单逐笔处理，单个子单到账不代表整组完成。</p><el-dialog v-model="detailOpen" title="交易明细" width="690px"><dl class="definition-list"><div v-for="(caption,key) in detailLabels" :key="key"><dt>{{ caption }}</dt><dd>{{ cell(detail?.[key]) }}</dd></div></dl></el-dialog><RedemptionGroupDialog v-model="groupOpen" :group-id="groupId" @changed="load"/></template>
