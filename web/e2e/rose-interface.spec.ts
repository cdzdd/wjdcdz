import { expect, test, type Page, type Route } from '@playwright/test'

const learner = { id: 9001, username: 'visual_learner', displayName: '界面演示', role: 'INVESTOR', opened: true, mustChangePassword: false }
async function capture(page: Page, name: string) {
  if (process.env.UI_SCREENSHOTS) await page.screenshot({ path: process.env.UI_SCREENSHOTS + '/' + name + '.png', fullPage: true, animations: 'disabled' })
}

async function mockAuth(page: Page, role = 'INVESTOR', handler?: (route: Route, path: string) => Promise<boolean>, mustChangePassword = false) {
  let authenticated = false
  const identity = { ...learner, role, mustChangePassword }
  const requests: { path: string; body: unknown }[] = []
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (handler && await handler(route, path)) return
    if (path === '/api/auth/csrf') return route.fulfill({ json: { token: 'visual-csrf', headerName: 'X-CSRF-TOKEN' } })
    if (path === '/api/me') return route.fulfill(authenticated ? { json: identity } : { status: 401, json: { message: '请先登录' } })
    if (path === '/api/auth/logout') {
      expect(route.request().headers()['x-csrf-token']).toBe('visual-csrf')
      authenticated = false
      return route.fulfill({ json: {} })
    }
    if (path === '/api/auth/login' || path === '/api/auth/register') {
      expect(route.request().headers()['x-csrf-token']).toBe('visual-csrf')
      requests.push({ path, body: route.request().postDataJSON() })
      if (path.endsWith('/login')) authenticated = true
      return route.fulfill({ json: identity })
    }
    const fixtures: Record<string, unknown> = {
      '/api/portfolio': { positions: [], accounts: [], totalAssets: 100000, available: 100000, frozen: 0, invested: 0, pending: 0, totalIncome: 0 },
      '/api/public/news': { items: [], stale: false, updatedAt: null },
      '/api/funds': [], '/api/orders': [], '/api/bank-cards': [], '/api/trading-accounts': [], '/api/sip-plans': [],
      '/api/admin/dashboard': {}, '/api/admin/jobs': [], '/api/admin/reconciliation': { ok: true, issues: [] }
    }
    if (!(path in fixtures)) throw new Error('Unexpected visual test request: ' + path)
    return route.fulfill({ json: fixtures[path] })
  })
  return requests
}

test('大厅独立于会话连接渲染官方资讯，登录失败状态可重试并通过Esc关闭', async ({ page }) => {
  let release: (() => void) | undefined
  const waiting = new Promise<void>(resolve => { release = resolve })
  let first = true
  let newsRequests = 0
  const official = { title: '官方数据发布样例标题', source: '国家统计局', publishedAt: '2026-11-06T01:00:00Z', url: 'https://www.stats.gov.cn/sj/zxfb/example.html' }
  await mockAuth(page, 'INVESTOR', async (route, path) => {
    if (path === '/api/public/news') {
      newsRequests++
      await route.fulfill({ json: { items: [official, { ...official, title: '伪造来源', url: 'https://www.stats.gov.cn.example.org/' }], updatedAt: '2026-11-06T09:00:00+08:00', stale: true } })
      return true
    }
    if (path !== '/api/me' || !first) return false
    first = false
    await waiting
    await route.fulfill({ status: 503, json: { message: '服务暂不可用' } })
    return true
  })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '模拟投资挑战 三步上手' })).toBeVisible()
  await expect(page.getByText('引自国家统计局网站（www.stats.gov.cn）', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: /官方数据发布样例标题/ })).toHaveAttribute('href', official.url)
  await expect(page.getByText('11-06 09:00', { exact: true })).toHaveAttribute('datetime', official.publishedAt)
  await expect(page.getByText('2026-11-06 09:00', { exact: true })).toHaveAttribute('datetime', '2026-11-06T09:00:00+08:00')
  await expect(page.getByText('伪造来源')).toHaveCount(0)
  await expect(page.getByText('上次获取的条目')).toBeVisible()
  await capture(page, 'lobby')
  const loginEntry = page.getByRole('link', { name: '登录', exact: true })
  await loginEntry.click()
  await expect(page.getByRole('dialog', { name: '欢迎回来' })).toBeVisible()
  await expect(page.locator('.lobby')).toBeVisible()
  await capture(page, 'login-dialog')
  release!()
  await expect(page.getByRole('alert')).toContainText('暂时无法确认登录状态')
  await page.getByRole('button', { name: '重试连接', exact: true }).click()
  await expect(page.getByRole('heading', { name: '欢迎回来', exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await page.keyboard.press('Escape')
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(loginEntry).toBeFocused()
  expect(newsRequests).toBe(1)
})

test('直达登录浮层，错误登录可改正后重试', async ({ page }) => {
  let rejectFirst = true
  const requests = await mockAuth(page, 'INVESTOR', async (route, path) => {
    if (path !== '/api/auth/login' || !rejectFirst) return false
    rejectFirst = false
    await route.fulfill({ status: 401, json: { message: '用户名或密码不正确' } })
    return true
  })
  await page.goto('/login')
  await expect(page.getByRole('dialog', { name: '欢迎回来' })).toBeVisible()
  await expect(page.getByRole('img', { name: '学习成长轨迹示意图' })).toBeVisible()
  await page.getByLabel('用户名', { exact: true }).fill('visual_learner')
  await page.getByLabel('密码', { exact: true }).fill('Preview12345!')
  await page.getByLabel('密码', { exact: true }).press('Enter')
  await expect(page.getByRole('alert')).toContainText('用户名或密码不正确')
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '我的资产', exact: true })).toBeVisible()
  await expect(page).toHaveURL(/\/dashboard$/)
  expect(requests).toHaveLength(1)
  await expect(page.locator('.platform-loader')).toHaveCount(0)
  await capture(page, 'investor')
})

test('注册保留昵称、密码校验和CSRF流程，成功进入投资者页面', async ({ page }) => {
  const requests = await mockAuth(page)
  await page.goto('/login')
  await page.getByRole('button', { name: '注册账户', exact: true }).click()
  await expect(page.getByRole('heading', { name: '创建学习账户', exact: true })).toBeVisible()
  await page.getByLabel('用户名', { exact: true }).fill('visual_learner')
  await page.getByLabel('模拟持卡人 / 昵称', { exact: true }).fill('界面演示')
  await expect(page.getByLabel('密码', { exact: true })).toHaveAttribute('autocomplete', 'new-password')
  await page.getByLabel('密码', { exact: true }).fill('short')
  await page.getByRole('button', { name: '注册并登录', exact: true }).click()
  expect(requests).toHaveLength(0)
  await page.getByLabel('密码', { exact: true }).fill('Preview12345!')
  await page.getByRole('button', { name: '注册并登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '我的资产', exact: true })).toBeVisible()
  expect(requests.map(item => item.path)).toEqual(['/api/auth/register', '/api/auth/login'])
  expect(requests[0].body).toMatchObject({ username: 'visual_learner', displayName: '界面演示' })
})

test('提交中阻止重复请求和模式切换，管理员保持正确跳转', async ({ page }) => {
  let release: (() => void) | undefined
  const waiting = new Promise<void>(resolve => { release = resolve })
  let posts = 0
  await mockAuth(page, 'ADMIN', async (_route, path) => {
    if (path !== '/api/auth/login') return false
    posts++
    await waiting
    return false
  })
  await page.goto('/login')
  await page.getByLabel('用户名', { exact: true }).fill('visual_admin')
  await page.getByLabel('密码', { exact: true }).fill('Preview12345!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '处理中…', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '注册账户', exact: true })).toBeDisabled()
  expect(posts).toBe(1)
  release!()
  await expect(page).toHaveURL(/\/admin\/overview$/)
  await expect(page.getByRole('heading', { name: '运营总览', exact: true })).toBeVisible()
  await expect(page.locator('.platform-loader')).toHaveCount(0)
  await capture(page, 'administrator')
})

test('减少动画及窄屏仍可直接操作登录表单，无横向溢出', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await mockAuth(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/login')
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeInViewport()
  await capture(page, 'login-mobile')
  for (const width of [320, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await expect(page.getByLabel('用户名', { exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  }
  expect(await page.locator('.lobby-auth-dialog').evaluate(element => getComputedStyle(element).animationName)).toBe('none')
  await page.getByRole('button', { name: '注册账户', exact: true }).click()
  await expect(page.getByLabel('模拟持卡人 / 昵称', { exact: true })).toBeVisible()
})

async function submitLogin(page: Page) {
  await page.getByLabel('用户名', { exact: true }).fill('visual_learner')
  await page.getByLabel('密码', { exact: true }).fill('Preview12345!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
}

test('受保护直达链接保存查询和片段，登录、失效与退出正确切换', async ({ page }) => {
  await mockAuth(page)
  const target = '/orders?status=waiting#history'
  await page.goto(target)
  await expect(page.getByRole('dialog', { name: '欢迎回来' })).toBeVisible()
  expect(new URL(page.url()).searchParams.get('redirect')).toBe(target)
  await submitLogin(page)
  await expect(page).toHaveURL(new RegExp('/orders\\?status=waiting#history$'))
  await expect(page.getByRole('heading', { name: '交易记录', exact: true })).toBeVisible()
  await page.route('**/api/orders', route => route.fulfill({ status: 401, json: { message: '请先登录' } }), { times: 1 })
  await page.reload()
  await expect(page.getByRole('dialog', { name: '欢迎回来' })).toBeVisible()
  expect(new URL(page.url()).searchParams.get('redirect')).toBe(target)
  await submitLogin(page)
  await expect(page.getByRole('heading', { name: '交易记录', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '退出', exact: true }).click()
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('link', { name: '登录', exact: true })).toBeVisible()
})

test('外站回跳和投资者后台回跳都回到资产总览', async ({ page }) => {
  await mockAuth(page)
  for (const redirect of ['//outside.example/', '/admin/trading']) {
    await page.goto('/login?redirect=' + encodeURIComponent(redirect))
    await submitLogin(page)
    await expect(page).toHaveURL(/\/dashboard$/)
    await expect(page.getByRole('heading', { name: '我的资产', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '退出', exact: true }).click()
    await expect(page).toHaveURL(/\/$/)
  }
})

test('强制改密优先于原业务链接', async ({ page }) => {
  await mockAuth(page, 'OPERATOR', undefined, true)
  await page.goto('/login?redirect=%2Fadmin%2Ftrading')
  await submitLogin(page)
  await expect(page).toHaveURL(/\/profile$/)
  await expect(page.getByText('管理员已重置密码。', { exact: false })).toBeVisible()
  await page.getByRole('link', { name: '交易与任务' }).click()
  await expect(page).toHaveURL(/\/profile$/)
})

test('资讯接口失败时保留大厅和官方栏目入口', async ({ page }) => {
  await mockAuth(page, 'INVESTOR', async (route, path) => {
    if (path !== '/api/public/news') return false
    await route.fulfill({ status: 503, json: {} })
    return true
  })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '国家统计局数据发布' })).toBeVisible()
  await expect(page.getByRole('link', { name: '前往数据发布栏目 ↗' })).toHaveAttribute('href', 'https://www.stats.gov.cn/sj/zxfb/')
  await expect(page.getByRole('link', { name: '登录', exact: true })).toBeVisible()
})
