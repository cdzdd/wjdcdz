<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../../api'
import { usePage,perform,busy } from '../../state'
import { cell, jobOutcome, label, stateTone } from '../../domain'
import type { Row } from '../../domain'
import PageHeader from '../../components/PageHeader.vue'
import DataTable from '../../components/DataTable.vue'
import LoadState from '../../components/LoadState.vue'
const tab=ref('jobs'),jobs=ref<Row[]>([]),orders=ref<Row[]>([]),positions=ref<Row[]>([]),payouts=ref<Row[]>([]),plans=ref<Row[]>([]),date=ref(''),type=ref('ALL'),result=ref<Row|null>(null),detail=ref<Row|null>(null),detailOpen=ref(false),detailIsJob=ref(false),context=ref<Row|null>(null)
const countItems=[{key:'processed',label:'成功',tone:'success'},{key:'waiting',label:'等待',tone:'warning'},{key:'failed',label:'失败',tone:'danger'}]
let dateInitialized=false
const {loading,error,load}=usePage(async()=>{
  [jobs.value,orders.value,positions.value,payouts.value,plans.value,context.value]=await Promise.all([api.get('/admin/jobs'),api.get('/admin/orders'),api.get('/admin/positions'),api.get('/admin/payouts'),api.get('/sip-plans'),api.get('/admin/jobs/context')])
  if(!dateInitialized){if(!date.value)date.value=context.value!.businessTime.slice(0,10);dateInitialized=true}
})
async function run(){
  const requestedType=type.value,requestedDate=date.value
  await perform(async()=>{result.value={...await api.post('/admin/jobs',{type:requestedType,date:requestedDate}),type:requestedType,date:requestedDate};return result.value},load)
}
function inspect(row:Row,isJob=false){detail.value=row;detailIsJob.value=isJob;detailOpen.value=true}
function taskLabel(value:unknown){return value==='MONEY'?'货币收益结转':label(value)}
function outcomeTone(row:Row){return jobOutcome(row)==='NO_WORK'?'muted':stateTone(jobOutcome(row))}
function reasons(row:Row):string[]{
  const value=row.details
  const messages=(Array.isArray(value)?value:typeof value==='string'?value.split('\n'):value?[value]:[])
    .map(item=>typeof item==='object'?JSON.stringify(item):String(item)).filter(item=>item.trim())
  if(messages.length)return messages
  if(jobOutcome(row)==='NO_WORK')return ['所选业务日期和任务类型下，无待处理业务；已完成项目不会重复计数。']
  const fallback=[]
  if(row.failed>0)fallback.push('存在失败项，本批次未记录具体失败原因，请检查相关业务记录。')
  if(row.waiting>0)fallback.push('存在等待处理项，本批次未提供逐项原因，请在交易订单、待支付与路由或定投计划中查看对应记录。')
  return fallback.length?fallback:['本批次已完成，无等待或失败项。']
}
</script>
<template>
  <PageHeader title="交易与任务" subtitle="TA模拟确认、资金支付、定投与收益任务共用同一处理流程。"><button class="button" @click="load">刷新</button></PageHeader>
  <LoadState :loading="loading" :error="error" @retry="load"/>
  <div class="tabs"><button v-for="t in [['jobs','任务中心'],['orders','交易订单'],['positions','持仓'],['payouts','待支付与路由'],['plans','定投计划']]" :key="t[0]" :class="{active:tab===t[0]}" @click="tab=t[0]">{{ t[1] }}</button></div>
  <template v-if="tab==='jobs'">
    <section class="panel">
      <h2>按业务日期执行任务</h2>
      <div v-if="context" class="clock-context">
        <p>当前业务时间：{{ cell(context.businessTime,'date') }}</p>
        <p class="muted">当前实际时间（加载时）：{{ cell(context.actualTime,'date') }}<span v-if="context.demo"> · 演示业务时钟</span></p>
      </div>
      <p class="notice">手动执行仍检查业务时间与适用数据，重复运行由服务端去重。未到时间、缺净值、收款受阻时保留等待状态；不会强行跳过。</p>
      <form class="inline-form" @submit.prevent="run">
        <label><span>任务类型</span><select v-model="type"><option v-for="t in ['ALL','CONFIRM','PAY','SIP','MONEY','MARKET']" :key="t" :value="t">{{ taskLabel(t) }}</option></select></label>
        <label><span>业务日期</span><input v-model="date" type="date" required/></label>
        <button class="button primary" :disabled="busy>0||!date">{{ busy?'任务运行中…':'执行任务' }}</button>
      </form>
      <p v-if="type==='MONEY'||type==='ALL'" class="muted small" style="margin-top:14px">货币收益按所选业务日期的前一自然日结转。例如选择10月15日，会处理10月14日收益。</p>
      <div v-if="result" class="job-result" aria-live="polite">
        <h3>本次执行：{{ label(jobOutcome(result)) }}</h3>
        <div class="job-counts"><span v-for="item in countItems.filter(item=>result![item.key]>0)" :key="item.key" class="status" :class="item.tone">{{ item.label }} {{ result[item.key] }} 项</span><span v-if="jobOutcome(result)==='NO_WORK'" class="muted">本次无需处理</span></div>
        <p class="muted">{{ taskLabel(result.type) }} · 业务日期 {{ result.date }} · 实际运行时间 {{ cell(result.actualRunAt,'date') }} · 业务时钟 {{ cell(result.businessTime,'date') }}</p>
        <ul class="job-reasons"><li v-for="(reason,index) in reasons(result)" :key="index">{{ reason }}</li></ul>
      </div>
    </section>
    <section class="panel">
      <h2>任务历史</h2>
      <p class="notice">以下计数是每次执行批次的处理项数，不是累计订单数；处理项可能是订单、付款、定投或收益等业务。自动任务每5分钟检查一次，无待处理业务的空批次属于正常情况。旧记录不会追溯重算。</p>
      <DataTable :rows="jobs" :columns="[{key:'id',label:'任务ID'}, {key:'type',label:'类型'}, {key:'date',label:'业务日期'}, {key:'outcome',label:'执行结果'}, {key:'counts',label:'处理情况'}, {key:'actualRunAt',label:'实际运行时间'}, {key:'createdAt',label:'业务时钟',kind:'date'}]">
        <template #cell-type="{row}">{{ taskLabel(row.type) }}</template>
        <template #cell-outcome="{row}"><span class="status" :class="outcomeTone(row)">{{ label(jobOutcome(row)) }}</span></template>
        <template #cell-counts="{row}"><div class="job-counts"><span v-for="item in countItems.filter(item=>row[item.key]>0)" :key="item.key" class="status" :class="item.tone">{{ item.label }} {{ row[item.key] }} 项</span><span v-if="jobOutcome(row)==='NO_WORK'" class="muted">本次无需处理</span></div></template>
        <template #cell-actualRunAt="{row}">{{ row.actualRunAt?cell(row.actualRunAt,'date'):'旧记录未采集' }}</template>
        <template #actions="{row}"><button class="text-button" @click="inspect(row,true)">结果详情</button></template>
      </DataTable>
    </section>
  </template>
  <section v-else-if="tab==='orders'" class="panel"><DataTable :rows="orders" :columns="[{key:'orderNo',label:'订单'}, {key:'userId',label:'客户'}, {key:'fundName',label:'基金'}, {key:'type',label:'业务'}, {key:'tradingAccountId',label:'来源账户'}, {key:'sourceCardId',label:'历史来源卡'}, {key:'status',label:'状态',kind:'state'}, {key:'amount',label:'金额（元）',kind:'money'}, {key:'shares',label:'份额',kind:'shares'}, {key:'applicationDate',label:'交易日'}, {key:'reason',label:'原因'}]"><template #actions="{row}"><button class="text-button" @click="inspect(row)">详情</button></template></DataTable></section>
  <section v-else-if="tab==='positions'" class="panel"><DataTable :rows="positions" :columns="[{key:'userId',label:'客户'}, {key:'tradingAccountId',label:'交易账户'}, {key:'fundName',label:'基金'}, {key:'availableShares',label:'可用份额',kind:'shares'}, {key:'shares',label:'份额',kind:'shares'}, {key:'frozenShares',label:'冻结份额',kind:'shares'}, {key:'cost',label:'成本（元）',kind:'money'}, {key:'marketValue',label:'市值（元）',kind:'money'}]"><template #actions="{row}"><button class="text-button" @click="inspect(row)">持仓明细</button></template></DataTable></section>
  <section v-else-if="tab==='payouts'" class="panel"><p class="notice">历史来源卡与实际支付目标分别保留。受阻款项在客户资产中保持应收，不因换卡或持仓核销而消失。</p><DataTable :rows="payouts" :columns="[{key:'id',label:'付款ID'}, {key:'userId',label:'客户'}, {key:'tradingAccountId',label:'交易账户'}, {key:'sourceType',label:'来源业务'}, {key:'sourceCardId',label:'历史来源卡'}, {key:'targetCardId',label:'当前付款目标'}, {key:'amount',label:'金额（元）',kind:'money'}, {key:'dueDate',label:'计划支付日'}, {key:'status',label:'状态',kind:'state'}, {key:'reason',label:'阻塞原因'}]"><template #actions="{row}"><button class="text-button" @click="inspect(row)">支付路径</button></template></DataTable></section>
  <section v-else class="panel"><DataTable :rows="plans" :columns="[{key:'id',label:'计划'}, {key:'userId',label:'客户'}, {key:'fundName',label:'基金'}, {key:'tradingAccountId',label:'固定扣款账户'}, {key:'bankCardId',label:'扣款卡'}, {key:'bindingVersion',label:'版本'}, {key:'amount',label:'金额（元）',kind:'money'}, {key:'frequency',label:'频率'}, {key:'day',label:'日期'}, {key:'status',label:'状态',kind:'state'}]"/></section>
  <el-dialog v-model="detailOpen" :title="detailIsJob?'任务批次详情':'业务记录明细'" width="760px">
    <template v-if="detailIsJob&&detail">
      <dl class="job-summary">
        <dt>任务ID</dt><dd>{{ detail.id }}</dd><dt>任务类型</dt><dd>{{ taskLabel(detail.type) }}</dd>
        <dt>业务日期</dt><dd>{{ detail.date }}</dd><dt>执行结果</dt><dd>{{ label(jobOutcome(detail)) }}</dd>
        <dt>处理情况</dt><dd class="job-counts"><span v-for="item in countItems.filter(item=>detail![item.key]>0)" :key="item.key" class="status" :class="item.tone">{{ item.label }} {{ detail[item.key] }} 项</span><span v-if="jobOutcome(detail)==='NO_WORK'" class="muted">本次无需处理</span></dd>
        <dt>实际运行时间</dt><dd>{{ detail.actualRunAt?cell(detail.actualRunAt,'date'):'旧记录未采集' }}</dd><dt>业务时钟</dt><dd>{{ cell(detail.createdAt,'date') }}</dd>
      </dl>
      <h3>处理说明与原因</h3><ul class="job-reasons"><li v-for="(reason,index) in reasons(detail)" :key="index">{{ reason }}</li></ul>
    </template>
    <pre v-else class="admin-detail">{{ JSON.stringify(detail,null,2) }}</pre>
  </el-dialog>
</template>
<style scoped>
.clock-context p{margin-bottom:8px}
.job-result{margin-top:22px}
.job-result h3{margin-bottom:12px}
.job-counts{display:flex;flex-wrap:wrap;gap:6px;align-items:center}
.job-summary{display:grid;grid-template-columns:110px 1fr;gap:10px 16px;margin:0 0 24px;font-size:13px}
.job-summary dt{color:var(--muted)}
.job-summary dd{margin:0;overflow-wrap:anywhere}
.job-reasons{padding-left:20px;margin:12px 0;line-height:1.8;font-size:13px;overflow-wrap:anywhere}
.status.muted{background:#f0f3f4}
</style>
