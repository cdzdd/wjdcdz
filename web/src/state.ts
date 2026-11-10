import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api, ApiError } from './api'
import type { Row } from './domain'
export const user = ref<Row | null>(null)
export const busy = ref(0)
export const sessionReady = ref(false)
export const sessionError = ref('')
let sessionCheck: Promise<void> | undefined
export async function refreshUser() { user.value = await api.get('/me') }
export function initializeSession(force = false): Promise<void> {
  if (sessionCheck) return sessionCheck
  if (sessionReady.value && !force) return Promise.resolve()
  sessionError.value = ''
  sessionCheck = refreshUser().catch(error => {
    if (error instanceof ApiError && error.status === 401) user.value = null
    else sessionError.value = '暂时无法确认登录状态，请重试连接。'
  }).finally(() => {
    sessionReady.value = true
    sessionCheck = undefined
  })
  return sessionCheck
}
export function message(error: unknown): string {
  return error instanceof ApiError ? error.message + (error.requestId ? '（请求编号 ' + error.requestId + '）' : '') : error instanceof Error ? error.message : '操作未完成'
}
export async function perform<T>(operation: () => Promise<T>, done?: () => Promise<unknown> | void): Promise<T | undefined> {
  busy.value++
  try { const result = await operation(); ElMessage.success('操作成功'); await done?.(); return result }
  catch (error) { ElMessage.error(message(error)); return undefined }
  finally { busy.value-- }
}
export function usePage(loader: () => Promise<unknown>) {
  const loading = ref(false), error = ref('')
  async function load() { loading.value = true; error.value = ''; try { await loader() } catch (e) { error.value = message(e) } finally { loading.value = false } }
  onMounted(load)
  return { loading, error, load }
}
