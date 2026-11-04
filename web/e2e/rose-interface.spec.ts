import { expect, test, type Page, type Route } from '@playwright/test'

const learner = { id: 9001, username: 'visual_learner', displayName: '界面演示', role: 'INVESTOR', opened: true, mustChangePassword: false }
async function capture(page: Page, name: string) {
  if (process.env.UI_SCREENSHOTS) await page.screenshot({ path: process.env.UI_SCREENSHOTS + '/' + name + '.png', fullPage: true, animations: 'disabled' })
}

async function mockAuth(page: Page, role = 'INVESTOR', handler?: (route: Route, path: string) => Promise<boolean>) {
  let authenticated = false
  const requests: { path: string; body: unknown }[] = []
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (handler && await handler(route, path)) return
    if (path === '/api/auth/csrf') return route.fulfill({ json: { token: 'visual-csrf', headerName: 'X-CSRF-TOKEN' } })
    if (path === '/api/me') return route.fulfill(authenticated ? { json: { ...learner, role } } : { status: 401, json: { message: '请先登录' } })
    if (path === '/api/auth/login' || path === '/api/auth/register') {
      expect(route.request().headers()['x-csrf-token']).toBe('visual-csrf')
      requests.push({ path, body: route.request().postDataJSON() })
      if (path.endsWith('/login')) authenticated = true
      return route.fulfill({ json: { ...learner, role } })
    }
    const fixtures: Record<string, unknown> = {
      '/api/portfolio': { positions: [], accounts: [], totalAssets: 100000, available: 100000, frozen: 0, invested: 0, pending: 0, totalIncome: 0 },
      '/api/funds': [], '/api/admin/dashboard': {}, '/api/admin/jobs': [], '/api/admin/reconciliation': { ok: true, issues: [] }
    }
    if (!(path in fixtures)) throw new Error('Unexpected visual test request: ' + path)
    return route.fulfill({ json: fixtures[path] })
  })
  return requests
}

test('启动动画随连接完成结束，加载失败仍可重试', async ({ page }) => {
  let release: (() => void) | undefined
  const waiting = new Promise<void>(resolve => { release = resolve })
  let first = true
  await mockAuth(page, 'INVESTOR', async (route, path) => {
    if (path !== '/api/auth/csrf' || !first) return false
    first = false
    await waiting
    await route.fulfill({ status: 503, json: { message: '服务暂不可用' } })
    return true
  })
  await page.goto('/')
  await expect(page.getByRole('status')).toContainText('正在连接基金模拟平台')
  await capture(page, 'loading')
  release!()
  await expect(page.getByRole('alert')).toContainText('服务暂不可用')
  await page.getByRole('button', { name: '重试连接', exact: true }).click()
  await expect(page.getByRole('heading', { name: '欢迎回来', exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('暂停装饰不隐藏表单，错误登录可改正后重试', async ({ page }) => {
  let rejectFirst = true
  const requests = await mockAuth(page, 'INVESTOR', async (route, path) => {
    if (path !== '/api/auth/login' || !rejectFirst) return false
    rejectFirst = false
    await route.fulfill({ status: 401, json: { message: '用户名或密码不正确' } })
    return true
  })
  await page.goto('/')
  await page.getByRole('button', { name: '暂停动效', exact: true }).click()
  await expect(page.getByRole('button', { name: '开启动效', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await page.getByLabel('用户名', { exact: true }).fill('visual_learner')
  await page.getByLabel('密码', { exact: true }).fill('Preview12345!')
  await page.getByLabel('密码', { exact: true }).press('Enter')
  await expect(page.getByRole('alert')).toContainText('用户名或密码不正确')
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '我的资产', exact: true })).toBeVisible()
  expect(requests).toHaveLength(1)
  await expect(page.locator('.platform-loader')).toHaveCount(0)
  await capture(page, 'investor')
})

test('注册保留昵称、密码校验和CSRF流程，成功进入投资者页面', async ({ page }) => {
  const requests = await mockAuth(page)
  await page.goto('/')
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
  await page.goto('/')
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
  await page.goto('/')
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeInViewport()
  await capture(page, 'login-mobile')
  for (const width of [320, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await expect(page.getByLabel('用户名', { exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  }
  expect(await page.locator('.hg-chart-path').evaluate(element => getComputedStyle(element).animationName)).toBe('none')
  await page.getByRole('button', { name: '注册账户', exact: true }).click()
  await expect(page.getByLabel('模拟持卡人 / 昵称', { exact: true })).toBeVisible()
})
