let csrf: { token: string; headerName: string } | undefined
export class ApiError extends Error {
  constructor(message: string, public status: number, public requestId?: string, public code?: string) { super(message) }
}
export function resetCsrf() { csrf = undefined }
export async function refreshCsrf() { csrf = await request('/auth/csrf'); return csrf }
async function request<T = any>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const headers = new Headers({ Accept: 'application/json' })
  if (method !== 'GET' && method !== 'HEAD') {
    if (!csrf) await refreshCsrf()
    headers.set(csrf!.headerName, csrf!.token)
  }
  const multipart = body instanceof FormData
  if (body !== undefined && !multipart) headers.set('Content-Type', 'application/json')
  const response = await fetch('/api' + path, { method, credentials: 'include', headers, body: body === undefined ? undefined : multipart ? body : JSON.stringify(body) })
  const text = await response.text()
  let result: any
  try { result = text ? JSON.parse(text) : null } catch { result = { message: '服务暂时不可用，请检查后端服务' } }
  if (!response.ok) {
    if (response.status === 401) { resetCsrf(); if(typeof window !== 'undefined') window.dispatchEvent(new Event('session-expired')) }
    throw new ApiError(result?.message || '请求未完成，请稍后重试', response.status, result?.requestId, result?.code)
  }
  return result as T
}
export const api = {
  get: <T = any>(path: string) => request<T>(path),
  post: <T = any>(path: string, body: unknown = {}) => request<T>(path, 'POST', body),
  put: <T = any>(path: string, body: unknown = {}) => request<T>(path, 'PUT', body),
  patch: <T = any>(path: string, body: unknown = {}) => request<T>(path, 'PATCH', body),
  delete: <T = any>(path: string) => request<T>(path, 'DELETE')
}
export async function login(body: unknown) { const user = await api.post('/auth/login', body); await refreshCsrf(); return user }
export async function logout() { const result=await api.post('/auth/logout');resetCsrf();return result }
