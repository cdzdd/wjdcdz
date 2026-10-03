<script setup lang="ts">
import { ref,watch,computed } from 'vue'
import { ElMessageBox } from 'element-plus'
import { api } from '../api'
import { message,perform,busy } from '../state'
import type { Row } from '../domain'
import DataTable from './DataTable.vue'
import LoadState from './LoadState.vue'
const props=defineProps<{modelValue:boolean;groupId?:number}>()
const emit=defineEmits<{ 'update:modelValue':[boolean];changed:[] }>()
const group=ref<Row>({orders:[]}),loading=ref(false),error=ref('')
const completed=computed(()=>group.value.orders.filter((o:Row)=>o.status==='COMPLETED').length)
const canCancel=computed(()=>group.value.orders.length>0&&group.value.orders.every((o:Row)=>o.status==='SUBMITTED'))
async function load(){loading.value=true;error.value='';try{group.value=await api.get('/redemption-groups/'+props.groupId)}catch(e){error.value=message(e)}finally{loading.value=false}}
watch(()=>props.modelValue,open=>{if(open)load()})
async function cancel(){try{await ElMessageBox.confirm('整组撤销需所有子单均处于可撤时间范围，任何一笔不可撤则整组不变。确定申请整组撤销？','撤销赎回组',{confirmButtonText:'申请整组撤销',cancelButtonText:'返回'})}catch{return};await perform(()=>api.post('/redemption-groups/'+props.groupId+'/cancel'),async()=>{await load();emit('changed')})}
</script>
<template><el-dialog :model-value="modelValue" :title="'多账户赎回组 #'+groupId" width="950px" @update:model-value="emit('update:modelValue',$event)"><LoadState :loading="loading" :error="error" @retry="load"/><p class="notice">已到账 {{ completed }} / {{ group.orders.length }} 笔。每个子单分别确认、原路支付；某个子单到账或失败不代表整组全部到账。</p><DataTable :rows="group.orders" :columns="[{key:'orderNo',label:'子单'}, {key:'fundName',label:'基金'}, {key:'tradingAccountId',label:'来源账户'}, {key:'bankName',label:'结算银行'}, {key:'sourceCardId',label:'历史来源卡'}, {key:'shares',label:'份额',kind:'shares'}, {key:'fee',label:'费用（元）',kind:'money'}, {key:'netAmount',label:'净回款（元）',kind:'money'}, {key:'status',label:'进度',kind:'state'}, {key:'reason',label:'说明'}]"/><div class="form-actions"><button class="button" :disabled="busy>0" @click="load">刷新子单进度</button><button v-if="canCancel" class="button danger" :disabled="busy>0" @click="cancel">申请整组撤销</button></div></el-dialog></template>
