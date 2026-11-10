<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, ApiError } from '../api'
import PageHeader from '../components/PageHeader.vue'

type ChatState = 'idle' | 'queued' | 'generating' | 'completed' | 'failed' | 'cancelled'
type Summary = { id: string; title: string; createdAt: string; updatedAt: string }
type Message = { id: string; role: 'user' | 'assistant'; content: string; status: ChatState; createdAt: string; requestId?: string; errorCode?: string; sources?: { title: string; quote: string }[] }
type Conversation = Summary & { status: ChatState; messages: Message[] }
type Availability = { available: boolean; reason: string; activeRequests: number; capacity: number; queueDepth: number }
const conversations = ref<Summary[]>([]), conversation = ref<Conversation | null>(null), selectedId = ref('')
const availability = ref<Availability | null>(null), statusLoading = ref(false), listLoading = ref(false), detailLoading = ref(false)
const listError = ref(''), detailError = ref(''), actionError = ref(''), draft = ref('')
const creating = ref(false), sending = ref(false), cancelling = ref(false)
const pending = computed(() => ['queued', 'generating'].includes(conversation.value?.status || ''))
const questions = ['申购为什么一直等待确认？', '多张银行卡的赎回款会回到哪里？', '修改默认卡会影响已有交易吗？', '风险测评和基金风险等级有什么关系？']
const statusText = computed(() => ({ ready: '业务助手在线', busy: '模型正在处理请求，可稍后查看排队进度', offline: '模型节点离线，请稍后检查连接', knowledge_pending: '业务知识正在准备，请稍后再试', disabled: '业务助手暂未启用' }[availability.value?.reason || ''] || '业务助手暂时不可用'))
const progressText = computed(() => conversation.value?.status === 'queued' ? '问题已提交，正在排队…' : '正在整理业务说明…')
let active = true, selectionVersion = 0, detailRequest = 0, pollTimer: ReturnType<typeof setTimeout> | undefined
let lastSubmission: { conversationId: string; content: string; requestId: string } | undefined

function errorText(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 401) return '登录已失效，请重新登录。'
    if (error.status === 404) return '会话不存在或无法访问，请刷新会话列表。'
    if (error.status === 429) return '助手请求较多，请稍后再试。'
    if (error.status === 409) return '当前会话已有请求正在处理，请刷新后查看。'
    if (error.status === 503 || error.status === 504) return '助手暂时不可用，请稍后重试。基金业务仍可正常使用。'
  }
  return '助手请求暂未完成，请刷新查看最新记录，再尝试重发。'
}
function stopPolling() { if (pollTimer) clearTimeout(pollTimer); pollTimer = undefined }
function current(id: string, version: number) { return active && selectedId.value === id && selectionVersion === version }
async function checkStatus() {
  if (statusLoading.value) return
  statusLoading.value = true
  try { const result = await api.get<Availability>('/assistant/status'); if (active) availability.value = result }
  catch { if (active) availability.value = { available: false, reason: 'unavailable', activeRequests: 0, capacity: 0, queueDepth: 0 } }
  finally { if (active) statusLoading.value = false }
}
async function loadList() {
  if (listLoading.value) return
  listLoading.value = true; listError.value = ''
  try {
    const result = await api.get<{ items: Summary[] }>('/assistant/conversations')
    if (!active) return
    conversations.value = result.items
    if (!selectedId.value && result.items.length) await selectConversation(result.items[0].id)
  } catch (error) { if (active) listError.value = errorText(error) }
  finally { if (active) listLoading.value = false }
}
async function loadConversation(id: string, version: number, showLoading = false) {
  stopPolling()
  const request = ++detailRequest
  if (showLoading) detailLoading.value = true
  try {
    const result = await api.get<Conversation>('/assistant/conversations/' + id)
    if (!current(id, version) || request !== detailRequest) return
    conversation.value = result; detailError.value = ''
    if (lastSubmission?.conversationId === id && result.messages.some(item => item.requestId === lastSubmission?.requestId)) {
      if (draft.value.trim() === lastSubmission.content) draft.value = ''
      lastSubmission = undefined; actionError.value = ''
    }
    if (['queued', 'generating'].includes(result.status)) pollTimer = setTimeout(() => loadConversation(id, version), 2000)
  } catch (error) { if (current(id, version) && request === detailRequest) detailError.value = errorText(error) }
  finally { if (current(id, version) && request === detailRequest) detailLoading.value = false }
}
async function selectConversation(id: string) {
  stopPolling(); selectedId.value = id; conversation.value = null; draft.value = ''; detailError.value = ''; actionError.value = ''
  await loadConversation(id, ++selectionVersion, true)
}
async function newConversation() {
  if (creating.value || sending.value) return
  creating.value = true; actionError.value = ''
  try {
    const result = await api.post<Summary>('/assistant/conversations', {})
    if (!active) return
    conversations.value.unshift(result)
    await selectConversation(result.id)
  } catch (error) { if (active) actionError.value = errorText(error) }
  finally { if (active) creating.value = false }
}
async function chooseQuestion(question: string) {
  if (!selectedId.value) await newConversation()
  if (active && selectedId.value && !pending.value) draft.value = question
}
async function send() {
  const content = draft.value.trim(), id = selectedId.value, version = selectionVersion
  if (!id || !content || content.length > 2000 || sending.value || pending.value || !availability.value?.available) return
  sending.value = true; actionError.value = ''
  if (lastSubmission?.conversationId !== id || lastSubmission.content !== content) lastSubmission = { conversationId: id, content, requestId: crypto.randomUUID() }
  try {
    await api.post('/assistant/conversations/' + id + '/messages', { content, requestId: lastSubmission.requestId })
    lastSubmission = undefined
    if (!current(id, version)) return
    if (draft.value.trim() === content) draft.value = ''
    await loadConversation(id, version, true)
    void loadList()
  } catch (error) { if (current(id, version)) actionError.value = errorText(error) }
  finally { if (active) sending.value = false }
}
async function cancel() {
  const id = selectedId.value, version = selectionVersion
  if (!id || !pending.value || cancelling.value) return
  cancelling.value = true; actionError.value = ''
  try { await api.post('/assistant/conversations/' + id + '/cancel', {}); if (current(id, version)) await loadConversation(id, version) }
  catch (error) { if (current(id, version)) actionError.value = errorText(error) }
  finally { if (active) cancelling.value = false }
}
function failedMessage(code?: string) {
  if (code?.includes('OFFLINE')) return '模型节点离线，本次未能生成回答。恢复连接后可重新提问。'
  if (code?.includes('TIMEOUT') || code?.includes('DEADLINE')) return '等待回答超时，请稍后重新提问。'
  if (code?.includes('BUSY')) return '请求队列繁忙，请稍后重新提问。'
  return '本次回答未完成，请稍后重新提问。'
}
function formatTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
onMounted(() => { void checkStatus(); void loadList() })
onBeforeUnmount(() => { active = false; selectionVersion++; stopPolling() })
</script>

<template>
  <PageHeader title="业务助手" subtitle="根据平台说明，解答操作步骤、模拟交易规则和常见问题。">
    <RouterLink to="/help" class="button">查看使用帮助</RouterLink>
  </PageHeader>
  <div class="assistant-availability notice" :class="{ warning: !availability?.available }" role="status">
    <div><strong>{{ statusLoading ? '正在检查助手状态…' : statusText }}</strong><span>提供业务解释与操作指导；不读取账户资产，不执行交易。助手不可用时，基金业务可继续使用。</span><span v-if="availability?.available && availability.queueDepth > 0">当前有 {{ availability.queueDepth }} 个请求等待模型处理。</span></div>
    <button class="text-button" :disabled="statusLoading" @click="checkStatus">{{ statusLoading ? '检查中…' : '检查连接' }}</button>
  </div>
  <div class="assistant-layout">
    <aside class="panel assistant-history" aria-label="助手会话列表">
      <div class="section-title"><h2>我的会话</h2><button class="text-button" :disabled="listLoading" @click="loadList">刷新列表</button></div>
      <button class="button primary full" :disabled="creating || sending" @click="newConversation">{{ creating ? '正在新建…' : '新建会话' }}</button>
      <p v-if="listLoading" class="loading">正在读取会话…</p>
      <p v-if="listError" class="notice error" role="alert">{{ listError }}</p>
      <div class="assistant-conversations">
        <button v-for="item in conversations" :key="item.id" class="assistant-conversation" :class="{ selected: item.id === selectedId }" :aria-current="item.id === selectedId ? 'true' : undefined" :disabled="creating" @click="selectConversation(item.id)">
          <span>{{ item.title || '新的业务咨询' }}</span><small>{{ formatTime(item.updatedAt || item.createdAt) }}</small>
        </button>
      </div>
      <p v-if="!listLoading && !listError && !conversations.length" class="muted assistant-history-empty">还没有会话，点击“新建会话”开始。</p>
      <p class="muted small">会话随你的基金账号保存。</p>
    </aside>
    <section class="panel assistant-chat" aria-label="业务助手聊天">
      <div class="section-title"><h2>{{ conversation?.title || '从一个业务问题开始' }}</h2><button v-if="selectedId" class="text-button" :disabled="detailLoading" @click="loadConversation(selectedId, selectionVersion, true)">刷新记录</button></div>
      <p v-if="detailLoading" class="loading">正在读取消息…</p>
      <p v-if="detailError" class="notice error" role="alert">{{ detailError }} 自动刷新已暂停，可点击“刷新记录”重试。</p>
      <div v-if="!detailLoading && !conversation?.messages.length && !detailError" class="assistant-welcome">
        <span class="assistant-mark" aria-hidden="true">◌</span><h3>读懂规则，再做模拟交易</h3><p class="muted">可以询问平台中的开户、多卡账户、申购赎回或等待状态。回答依据专属业务知识库，请结合页面实际参数核对。</p>
        <div class="assistant-questions"><button v-for="question in questions" :key="question" :disabled="creating || sending" @click="chooseQuestion(question)">{{ question }}</button></div>
      </div>
      <div v-if="conversation?.messages.length" class="assistant-messages" role="log" aria-label="会话消息" aria-live="polite">
        <article v-for="item in conversation.messages" :key="item.id" class="assistant-message" :class="item.role">
          <div class="assistant-message-meta"><strong>{{ item.role === 'user' ? '我' : '业务助手' }}</strong><time>{{ formatTime(item.createdAt) }}</time></div>
          <p v-if="item.content" class="assistant-message-content">{{ item.content }}</p>
          <p v-else-if="['queued', 'generating'].includes(item.status)" class="muted">{{ item.status === 'queued' ? '等待模型处理…' : '正在生成回答…' }}</p>
          <p v-if="item.status === 'failed'" class="assistant-message-error">{{ failedMessage(item.errorCode) }}</p>
          <p v-if="item.status === 'cancelled'" class="muted">本次回答已取消。</p>
          <details v-if="item.sources?.length" class="assistant-sources"><summary>参考说明 · {{ item.sources.length }} 条</summary><div v-for="(source, index) in item.sources" :key="index"><strong>{{ source.title }}</strong><blockquote>{{ source.quote }}</blockquote></div></details>
        </article>
      </div>
      <div v-if="pending" class="assistant-progress" role="status"><span>{{ progressText }}</span><button class="text-button" :disabled="cancelling" @click="cancel">{{ cancelling ? '正在取消…' : '取消本次回答' }}</button></div>
      <p v-if="actionError" class="notice error" role="alert">{{ actionError }}</p>
      <form class="assistant-compose" @submit.prevent="send">
        <label><span>你的问题</span><textarea v-model="draft" name="question" rows="3" maxlength="2000" placeholder="例如：申购为什么一直等待确认？" :disabled="!selectedId || pending || sending || detailLoading"/></label>
        <div class="assistant-compose-actions"><span class="muted small">{{ draft.length }} / 2000 · 回答供教学模拟参考</span><button class="button primary" :disabled="!selectedId || !draft.trim() || draft.trim().length > 2000 || !availability?.available || pending || sending || detailLoading" type="submit">{{ sending ? '正在提交…' : '发送问题' }}</button></div>
      </form>
    </section>
  </div>
</template>

<style scoped>
.assistant-availability{display:flex;align-items:center;justify-content:space-between;gap:18px}.assistant-availability strong{display:block;font-size:13px;margin-bottom:4px}.assistant-availability span{display:block;font-size:12px}.assistant-availability button{flex-shrink:0}.assistant-layout{display:grid;grid-template-columns:245px minmax(0,1fr);gap:20px}.assistant-history{align-self:start}.assistant-history h2,.assistant-chat h2{font-size:16px}.assistant-conversations{max-height:430px;overflow-y:auto;margin:15px -8px}.assistant-conversation{width:100%;text-align:left;border:0;background:transparent;color:var(--ink);padding:12px 10px;border-radius:6px;margin-bottom:4px}.assistant-conversation:hover,.assistant-conversation.selected{background:var(--brand-soft)}.assistant-conversation.selected{box-shadow:inset 3px 0 var(--brand)}.assistant-conversation span{display:block;font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.assistant-conversation small{display:block;color:var(--muted);font-size:10px;margin-top:7px}.assistant-history-empty{margin:22px 0}.assistant-chat{min-height:570px;display:flex;flex-direction:column;min-width:0}.assistant-welcome{max-width:640px;margin:auto;padding:28px 10px;text-align:center}.assistant-mark{display:block;color:var(--brand);font-size:42px;margin-bottom:16px}.assistant-welcome h3{font-size:19px}.assistant-welcome p{font-size:13px}.assistant-questions{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:23px}.assistant-questions button{text-align:left;background:#f5f8fc;border:1px solid var(--line);border-radius:7px;padding:13px;font-size:12px;line-height:1.7;color:#39516b}.assistant-questions button:hover{border-color:var(--brand)}.assistant-messages{display:flex;flex-direction:column;gap:20px;padding:5px 0 22px}.assistant-message{padding:18px;background:#f8fafd;border:1px solid var(--line);border-radius:8px}.assistant-message.user{background:#edf2f8;border-color:#d8e1ec;margin-left:35px}.assistant-message-meta{display:flex;align-items:center;justify-content:space-between;gap:15px;margin-bottom:10px;font-size:12px}.assistant-message-meta time{font-size:10px;color:var(--muted)}.assistant-message-content{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px;line-height:1.9;margin-bottom:0}.assistant-message-error{color:#ae584d;font-size:12px;margin:10px 0 0}.assistant-sources{font-size:12px;border-top:1px solid var(--line);margin-top:17px;padding-top:12px}.assistant-sources summary{color:var(--brand);cursor:pointer}.assistant-sources>div{padding-top:15px}.assistant-sources blockquote{white-space:pre-wrap;overflow-wrap:anywhere;color:var(--muted);margin:8px 0 0;padding-left:13px;border-left:2px solid #c9d9ec;line-height:1.8}.assistant-compose{margin-top:auto;border-top:1px solid var(--line);padding-top:20px}.assistant-compose textarea{resize:vertical;min-height:94px;max-height:260px}.assistant-compose-actions{display:flex;align-items:center;justify-content:space-between;gap:15px;margin-top:12px}.assistant-progress{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:12px 15px;background:#edf2f8;border-radius:6px;margin-bottom:16px;font-size:12px;color:#39516b}
@media(max-width:1050px){.assistant-layout{grid-template-columns:205px minmax(0,1fr)}.assistant-questions{grid-template-columns:1fr}}
@media(max-width:760px){.assistant-layout{grid-template-columns:1fr}.assistant-history{margin-bottom:0}.assistant-conversations{max-height:160px}.assistant-chat{min-height:480px}.assistant-availability{align-items:flex-start}.assistant-message.user{margin-left:15px}.assistant-compose-actions{align-items:flex-end}.assistant-compose-actions span{max-width:55%}}
</style>
