<script setup lang="ts">
import { computed,ref,watch } from 'vue'
import { api } from '../api'
import { usePage,perform,busy } from '../state'
import type { Row } from '../domain'
import DataTable from './DataTable.vue'
import LoadState from './LoadState.vue'
const props=defineProps<{funds:Row[]}>()
const records=ref<Row[]>([]),product=ref<number|''>(''),tiers=ref<Row[]>([])
const {loading,error,load}=usePage(async()=>{records.value=await api.get('/admin/fee-tiers');pick()})
const options=computed(()=>props.funds.filter(f=>f.type!=='MONEY'))
function pick(){tiers.value=records.value.filter(t=>t.fundClassId===Number(product.value)).map(t=>({minAmount:t.minAmount,rate:t.rate,fixedFee:t.fixedFee??''}))}
watch(product,pick)
watch(options,()=>{if(!product.value)product.value=options.value[0]?.id||''},{immediate:true})
async function save(){await perform(()=>api.put('/admin/fee-tiers/'+product.value,{tiers:tiers.value.map(t=>({minAmount:String(t.minAmount),rate:String(t.rate),fixedFee:t.fixedFee===''?null:String(t.fixedFee)}))}),load)}
</script>
<template><LoadState :loading="loading" :error="error" @retry="load"/><section class="panel"><h2>申购费率档位</h2><p class="notice">按申购金额匹配最高已达到的门槛。固定费用优先于比例费率；修改仅影响未来申请，历史规则快照保持。</p><form @submit.prevent="save"><label style="max-width:450px"><span>基金类别</span><select v-model="product" required><option v-for="fund in options" :key="fund.id" :value="fund.id">{{ fund.name }}</option></select></label><div class="table-scroll" style="margin-top:20px"><table><thead><tr><th>金额下限（元）</th><th>比例费率（小数）</th><th>固定费用（元，可选）</th><th>操作</th></tr></thead><tbody><tr v-for="(tier,index) in tiers" :key="index"><td><input v-model="tier.minAmount" required inputmode="decimal" aria-label="档位金额下限"/></td><td><input v-model="tier.rate" required inputmode="decimal" aria-label="档位比例费率"/></td><td><input v-model="tier.fixedFee" inputmode="decimal" aria-label="档位固定费用" placeholder="留空使用比例费率"/></td><td><button type="button" class="text-button danger" @click="tiers.splice(index,1)">移除档位</button></td></tr></tbody></table></div><div class="form-actions"><button type="button" class="button" @click="tiers.push({minAmount:'0.00',rate:'0.0015',fixedFee:''})">＋ 增加档位</button><button class="button primary" :disabled="busy>0 || !product">保存该类别所有档位</button></div></form></section><section class="panel"><h2>当前档位</h2><DataTable :rows="records" :columns="[{key:'fundName',label:'基金'}, {key:'minAmount',label:'金额下限（元）',kind:'money'}, {key:'rate',label:'比例费率',kind:'percent'}, {key:'fixedFee',label:'固定费用（元）',kind:'money'}]"/></section></template>
