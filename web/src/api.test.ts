import { afterEach, expect, it, vi } from 'vitest'
import { api, login, logout, resetCsrf } from './api'
afterEach(() => { vi.unstubAllGlobals(); resetCsrf() })
it('登录轮换后重新获取CSRF，下一笔写请求使用新令牌及会话Cookie', async () => {
  const calls: { url: string; init: RequestInit }[] = []
  const replies = [{ token: 'before', headerName: 'X-CSRF-TOKEN' }, { id: 1, username: 'demo' }, { token: 'after', headerName: 'X-CSRF-TOKEN' }, { id: 2 }]
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { calls.push({ url, init }); return new Response(JSON.stringify(replies.shift()), { status: 200, headers: { 'content-type': 'application/json' } }) })
  await login({ username: 'demo', password: 'Demo12345!' })
  await api.post('/orders', { amount: '100.01', tradingAccountId: 7 })
  expect(calls.map(c => c.url)).toEqual(['/api/auth/csrf', '/api/auth/login', '/api/auth/csrf', '/api/orders'])
  expect(new Headers(calls[1].init.headers).get('X-CSRF-TOKEN')).toBe('before')
  expect(new Headers(calls[3].init.headers).get('X-CSRF-TOKEN')).toBe('after')
  expect(calls.every(c => c.init.credentials === 'include')).toBe(true)
  expect(JSON.parse(String(calls[3].init.body))).toEqual({ amount: '100.01', tradingAccountId: 7 })
})
it('错误保留服务端业务原因与请求编号，不吞掉失败', async () => {
  vi.stubGlobal('fetch', async () => new Response(JSON.stringify({ code: 'INSUFFICIENT_BALANCE', message: '所选账户余额不足', requestId: 'r-123' }), { status: 409 }))
  await expect(api.get('/portfolio')).rejects.toMatchObject({ message: '所选账户余额不足', requestId: 'r-123', status: 409 })
})
it('退出后再次登录先获取新会话CSRF，不复用已失效的令牌', async () => {
  const calls:{url:string;init:RequestInit}[]=[]
  const replies=[{token:'first',headerName:'X-CSRF-TOKEN'},{id:1},{token:'logged-in',headerName:'X-CSRF-TOKEN'},null,{token:'fresh-session',headerName:'X-CSRF-TOKEN'},{id:1},{token:'new-login',headerName:'X-CSRF-TOKEN'}]
  vi.stubGlobal('fetch',async(url:string,init:RequestInit)=>{calls.push({url,init});return new Response(JSON.stringify(replies.shift()),{status:200})})
  await login({username:'demo',password:'Demo12345!'})
  await logout()
  await login({username:'demo',password:'Demo12345!'})
  expect(calls.map(c=>c.url)).toEqual(['/api/auth/csrf','/api/auth/login','/api/auth/csrf','/api/auth/logout','/api/auth/csrf','/api/auth/login','/api/auth/csrf'])
  expect(new Headers(calls[5].init.headers).get('X-CSRF-TOKEN')).toBe('fresh-session')
})
