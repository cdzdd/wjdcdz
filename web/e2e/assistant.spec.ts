import { expect, test, type Page, type Route } from '@playwright/test'

const firstId = 'd02f9b4b-bc6a-41a6-8e18-a1726bcdce01'
const secondId = 'd02f9b4b-bc6a-41a6-8e18-a1726bcdce02'
const createdAt = '2026-11-04T08:00:00Z'
const summary = (id = firstId, title = '申购等待确认') => ({ id, title, createdAt, updatedAt: createdAt })
const detail = (id = firstId, title = '申购等待确认', status = 'idle', messages: unknown[] = []) => ({ ...summary(id, title), status, messages })
const ready = { available: true, reason: 'ready', activeRequests: 0, capacity: 2, queueDepth: 0 }

async function setup(page: Page, handler: (route: Route, path: string, method: string) => Promise<void>) {
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname, method = route.request().method()
    if (path === '/api/auth/csrf') return route.fulfill({ json: { token: 'csrf-test', headerName: 'X-CSRF-TOKEN' } })
    if (path === '/api/me') return route.fulfill({ json: { id: 1, username: 'learner', displayName: '学习者', role: 'INVESTOR', mustChangePassword: false } })
    await handler(route, path, method)
  })
}

test('模型离线仍能查看会话和帮助，消息与参考内容按纯文本展示', async ({ page }) => {
  await setup(page, async (route, path) => {
    if (path.endsWith('/status')) return route.fulfill({ json: { ...ready, available: false, reason: 'offline' } })
    if (path === '/api/assistant/conversations') return route.fulfill({ json: { items: [summary()], total: 1 } })
    if (path.endsWith('/' + firstId)) return route.fulfill({ json: detail(firstId, '申购等待确认', 'completed', [{ id: 'm1', role: 'assistant', status: 'completed', createdAt, content: '<img src=x onerror="alert(1)">按来源账户查看交易记录。', sources: [{ title: '<script>说明</script>', quote: '<b>原路回款</b>' }] }]) })
    throw new Error('Unexpected assistant request: ' + path)
  })
  await page.goto('/assistant')
  await expect(page.getByText('模型节点离线，请稍后检查连接', { exact: true })).toBeVisible()
  await expect(page.getByRole('log')).toContainText('<img src=x onerror="alert(1)">')
  await expect(page.getByRole('log').locator('img,script')).toHaveCount(0)
  await page.getByText('参考说明 · 1 条').click()
  await expect(page.getByRole('log')).toContainText('<b>原路回款</b>')
  await expect(page.getByRole('button', { name: '发送问题', exact: true })).toBeDisabled()
  await page.getByRole('link', { name: '查看使用帮助', exact: true }).click()
  await expect(page.getByRole('heading', { name: '使用帮助', exact: true })).toBeVisible()
})

test('切换会话后到达的旧响应不会覆盖当前会话', async ({ page }) => {
  let releaseFirst: (() => void) | undefined
  const firstWaiting = new Promise<void>(resolve => { releaseFirst = resolve })
  await setup(page, async (route, path) => {
    if (path.endsWith('/status')) return route.fulfill({ json: ready })
    if (path === '/api/assistant/conversations') return route.fulfill({ json: { items: [summary(), summary(secondId, '默认卡说明')], total: 2 } })
    if (path.endsWith('/' + firstId)) { await firstWaiting; return route.fulfill({ json: detail(firstId, '旧会话迟到的内容') }) }
    if (path.endsWith('/' + secondId)) return route.fulfill({ json: detail(secondId, '默认卡说明') })
    throw new Error('Unexpected assistant request: ' + path)
  })
  await page.goto('/assistant')
  await page.getByRole('button', { name: /默认卡说明/ }).click()
  await expect(page.locator('.assistant-chat').getByRole('heading', { name: '默认卡说明', exact: true })).toBeVisible()
  const oldResponse = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/' + firstId))
  releaseFirst!()
  await (await oldResponse).finished()
  await page.getByLabel('你的问题', { exact: true }).fill('当前会话的问题')
  await expect(page.locator('.assistant-chat')).not.toContainText('旧会话迟到的内容')
  await expect(page.getByLabel('你的问题', { exact: true })).toBeEnabled()
})

test('未知网络结果重试沿用请求编号，发送期间阻止重复提交', async ({ page }) => {
  const submitted: { content: string; requestId: string }[] = []
  let answered = false
  let releaseFirstPost: (() => void) | undefined
  const firstPostWaiting = new Promise<void>(resolve => { releaseFirstPost = resolve })
  await setup(page, async (route, path, method) => {
    if (path.endsWith('/status')) return route.fulfill({ json: ready })
    if (path === '/api/assistant/conversations') return route.fulfill({ json: { items: [summary()], total: 1 } })
    if (path.endsWith('/messages') && method === 'POST') {
      const body = route.request().postDataJSON()
      expect(Object.keys(body).sort()).toEqual(['content', 'requestId'])
      expect(route.request().headers()['authorization']).toBeUndefined()
      expect(route.request().headers()['x-fund-user-id']).toBeUndefined()
      expect(route.request().headers()['x-csrf-token']).toBe('csrf-test')
      submitted.push(body)
      if (submitted.length === 1) { await firstPostWaiting; return route.abort('failed') }
      answered = true
      return route.fulfill({ status: 202, json: { conversationId: firstId, requestId: body.requestId, status: 'queued' } })
    }
    if (path.endsWith('/' + firstId)) return route.fulfill({ json: detail(firstId, '申购等待确认', answered ? 'completed' : 'idle', answered ? [{ id: 'm1', role: 'user', content: submitted[0].content, requestId: submitted[0].requestId, status: 'completed', createdAt }, { id: 'm2', role: 'assistant', content: '请在交易记录中查看等待原因。', status: 'completed', createdAt }] : []) })
    throw new Error('Unexpected assistant request: ' + path)
  })
  await page.goto('/assistant')
  await page.getByLabel('你的问题', { exact: true }).fill('我的申购为何等待？')
  await page.getByRole('button', { name: '发送问题', exact: true }).click()
  await expect(page.getByRole('button', { name: '正在提交…', exact: true })).toBeDisabled()
  expect(submitted).toHaveLength(1)
  releaseFirstPost!()
  await expect(page.getByRole('alert')).toContainText('助手请求暂未完成')
  await expect(page.getByLabel('你的问题', { exact: true })).toHaveValue('我的申购为何等待？')
  await page.getByRole('button', { name: '发送问题', exact: true }).click()
  await expect(page.getByRole('log')).toContainText('请在交易记录中查看等待原因。')
  expect(submitted).toHaveLength(2)
  expect(submitted[1]).toEqual(submitted[0])
  expect(submitted[0].requestId).toMatch(/^[0-9a-f-]{36}$/)
  await expect(page.getByLabel('你的问题', { exact: true })).toHaveValue('')
})

test('仅等待中的会话轮询，取消及离开页面都会停止轮询', async ({ page }) => {
  await page.clock.install()
  let state = 'queued', reads = 0
  await setup(page, async (route, path, method) => {
    if (path.endsWith('/status')) return route.fulfill({ json: { ...ready, reason: 'busy', activeRequests: 1, queueDepth: 1 } })
    if (path === '/api/assistant/conversations') return route.fulfill({ json: { items: [summary()], total: 1 } })
    if (path.endsWith('/cancel') && method === 'POST') { state = 'cancelled'; return route.fulfill({ json: { status: state } }) }
    if (path.endsWith('/' + firstId)) { reads++; return route.fulfill({ json: detail(firstId, '申购等待确认', state, [{ id: 'm2', role: 'assistant', content: '', status: state, createdAt }]) }) }
    throw new Error('Unexpected assistant request: ' + path)
  })
  await page.goto('/assistant')
  await expect(page.getByText('问题已提交，正在排队…', { exact: true })).toBeVisible()
  await page.clock.fastForward(2100)
  await expect.poll(() => reads).toBe(2)
  await page.getByRole('button', { name: '取消本次回答', exact: true }).click()
  await expect(page.getByText('本次回答已取消。', { exact: true })).toBeVisible()
  const afterCancel = reads
  await page.clock.fastForward(8000)
  expect(reads).toBe(afterCancel)
  state = 'queued'
  await page.getByRole('button', { name: '刷新记录', exact: true }).click()
  await expect(page.getByText('问题已提交，正在排队…', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: '查看使用帮助', exact: true }).click()
  await expect(page.getByRole('heading', { name: '使用帮助', exact: true })).toBeVisible()
  const afterLeave = reads
  await page.clock.fastForward(8000)
  expect(reads).toBe(afterLeave)
})
