<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { api } from '../api'
import { perform, busy, message } from '../state'
import { buildOrderPayload, decimal, requestKey } from '../domain'
import type { Row } from '../domain'
const props = defineProps<{ modelValue: boolean; fund: Row | null; type?: 'PURCHASE' | 'REDEMPTION'; accountId?: number; offering?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; submitted: [] }>()
const cards = ref<Row[]>([]), accountId = ref<number | ''>(''), selected = ref<Row | null>(null), value = ref(''), key = ref(''), quote = ref<Row | null>(null), error = ref(''), loading = ref(false)
const eligible = computed(() => cards.value.filter(c => props.type === 'REDEMPTION' ? !['UNBOUND', 'REPLACED'].includes(c.status) : c.status === 'ACTIVE'))
watch(() => props.modelValue, async open => {
  if (!open) return
  value.value = ''; quote.value = null; error.value = ''; key.value = requestKey(); selected.value = null; loading.value = true
  try { cards.value = await api.get('/bank-cards'); accountId.value = props.accountId || eligible.value.find(c => c.isDefault)?.tradingAccountId || eligible.value[0]?.tradingAccountId || ''; selectAccount() }
  catch (e) { error.value = message(e) } finally { loading.value = false }
})
function selectAccount() { selected.value = { ...cards.value.find(c => c.tradingAccountId === Number(accountId.value)) }; quote.value = null; key.value = requestKey() }
watch(value, () => { quote.value = null; key.value = requestKey() })
function payload() {
  if (!selected.value?.id || !props.fund) throw new Error('请选择明确的交易账户')
  return buildOrderPayload(props.offering ? 'OFFERING' : props.type || 'PURCHASE', props.fund.id, { id: Number(selected.value.id), tradingAccountId: Number(selected.value.tradingAccountId), bindingVersion: Number(selected.value.bindingVersion) }, value.value, key.value)
}
async function preview() {
  error.value = ''; loading.value = true
  try { const data = payload(); quote.value = await api.post('/trades/quotes', { type: data.type, fundClassId: data.fundClassId, tradingAccountId: data.tradingAccountId, amount: data.amount, shares: data.shares }) }
  catch (e) { error.value = message(e) } finally { loading.value = false }
}
async function submit() {
  error.value = ''
  let data: Row
  try { data = payload() } catch (e) { error.value = message(e); return }
  const result = await perform(() => api.post(props.offering ? '/offering-orders' : '/orders', data), () => { emit('submitted'); emit('update:modelValue', false) })
  if (result === undefined) error.value = '申请未提交成功，请核对服务端提示。重试保留同一请求编号。'
}
</script>
<template><el-dialog :model-value="modelValue" :title="(offering ? '基金认购' : type === 'REDEMPTION' ? '基金赎回' : '基金申购') + ' · ' + (fund?.name || '')" width="560px" @update:model-value="emit('update:modelValue', $event)"><div v-if="error" class="notice error" role="alert">{{ error }}</div><form @submit.prevent="submit"><label><span>来源交易账户 / 结算卡</span><select v-model="accountId" required @change="selectAccount"><option disabled value="">请先绑定模拟银行卡</option><option v-for="card in eligible" :key="card.id" :value="card.tradingAccountId">{{ card.bankName }} · {{ card.simNumber }} · 账户 {{ card.accountNo }}</option></select></label><p v-if="selected?.id" class="muted small">可用资金 ¥{{ decimal(selected.available) }} · 绑定版本 {{ selected.bindingVersion }}<br/>申请固定此账户及卡版本，回款遵循原结算路径。</p><p v-if="selected?.receiveState === 'BLOCKED'" class="notice warning">该结算卡收款受阻，赎回确认后将保留待付款，需联系后台处置。</p><p v-if="type === 'REDEMPTION'" class="notice">若剩余不足最低起点，可以全额赎回清零。回款仍按该账户的来源结算路径处理。</p><label><span>{{ type === 'REDEMPTION' ? '赎回份额（份，最多四位小数）' : '购入金额（元，最多两位小数）' }}</span><input v-model="value" inputmode="decimal" required :placeholder="type === 'REDEMPTION' ? '例如 100.0000' : '例如 1000.00'"/></label><div v-if="offering" class="notice" style="margin-top:18px">认购结果等待募集成立处理；募集失败按原结算路径退款。示例认购不支持用户撤单。</div><div v-else><div v-if="quote" class="trade-summary"><div><span>参考净值 / 日期</span><b>{{ decimal(quote.nav, 6) }}</b><small>{{ quote.navDate }}</small></div><div><span>预估费用（元）</span><b>{{ decimal(quote.fee) }}</b></div><div><span>{{ type === 'REDEMPTION' ? '预估净回款（元）' : '预估份额（份）' }}</span><b>{{ type === 'REDEMPTION' ? decimal(quote.netAmount) : decimal(quote.shares, 4) }}</b></div><div><span>归属交易日</span><b style="font-size:15px">{{ quote.applicationDate }}</b></div></div><p class="muted small" style="margin-top:15px">预估按已公布数据展示，实际成交采用归属交易日净值，由后台最终确认。</p></div><div class="form-actions"><button v-if="!offering" class="button" type="button" :disabled="loading || busy > 0 || !accountId" @click="preview">{{ loading ? '计算中…' : '预估费用与份额' }}</button><button class="button primary" :disabled="busy > 0 || loading || !selected?.id || (!offering && !quote)" type="submit">{{ busy ? '提交中…' : '确认提交申请' }}</button></div></form></el-dialog></template>
