import { test, expect } from '@playwright/test'
test('登录入口可键盘操作，表单展示明确模拟说明', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
  await expect(page.getByLabel('用户名', { exact: true })).toBeVisible()
  await expect(page.getByLabel('密码', { exact: true })).toBeVisible()
  await expect(page.getByText('教学模拟 · 使用虚拟资金 · 不涉及真实投资')).toBeVisible()
  await page.getByRole('button', { name: '注册账户', exact: true }).click()
  await expect(page.getByRole('heading', { name: '创建学习账户' })).toBeVisible()
  await expect(page.getByLabel('模拟持卡人 / 昵称')).toBeVisible()
})
test('真实API会话可查看市场与逐卡账户', async ({ page }) => {
  test.skip(!process.env.E2E_API, '设置E2E_API=1并启动后端后执行')
  await page.goto('/')
  await page.getByLabel('用户名', { exact: true }).fill('demo')
  await page.getByLabel('密码', { exact: true }).fill('Demo12345!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '我的资产' })).toBeVisible()
  await page.getByRole('link', { name: '基金市场' }).click()
  await expect(page.getByRole('heading', { name: '基金市场' })).toBeVisible()
  await expect(page.locator('.fund-card').first()).toBeVisible()
  await page.getByRole('link', { name: '银行卡与资金' }).click()
  await expect(page.getByRole('heading', { name: '银行卡与资金' })).toBeVisible()
  await expect(page.locator('[role=alert]')).toHaveCount(0)
})

test('任务中心区分空批次和等待项，展示双时钟并保留选择的业务日期', async ({ page }) => {
  let businessTime = '2026-10-13T09:10:00'
  const actualTime = '2026-10-04T14:10:00'
  const jobs = [
    { id: 9, type: 'ALL', date: '2026-10-13', status: 'COMPLETED', processed: 0, waiting: 0, failed: 0, details: '', createdAt: businessTime, actualRunAt: null },
    { id: 8, type: 'PAY', date: '2026-10-13', status: 'WAITING', processed: 0, waiting: 1, failed: 0, details: ['付款8：收款卡受阻，等待处置'], createdAt: businessTime, actualRunAt: actualTime },
    { id: 7, type: 'CONFIRM', date: '2026-10-13', status: 'FAILED', processed: 0, waiting: 0, failed: 1, details: '', createdAt: businessTime, actualRunAt: actualTime }
  ]
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/admin/jobs' && route.request().method() === 'POST') {
      expect(route.request().postDataJSON()).toEqual({ type: 'ALL', date: '2026-10-10' })
      return route.fulfill({ json: { processed: 0, waiting: 0, failed: 0, details: [], status: 'NO_WORK', jobId: 10, businessTime, actualRunAt: actualTime } })
    }
    const responses: Record<string, unknown> = {
      '/api/auth/csrf': { token: 'test-token', headerName: 'X-CSRF-TOKEN' },
      '/api/me': { id: 1, username: 'admin', displayName: '管理员', role: 'ADMIN', mustChangePassword: false },
      '/api/admin/jobs/context': { businessTime, actualTime, demo: true },
      '/api/admin/jobs': jobs, '/api/admin/orders': [], '/api/admin/positions': [], '/api/admin/payouts': [], '/api/sip-plans': []
    }
    if (!(path in responses)) throw new Error('Unexpected request: ' + path)
    await route.fulfill({ json: responses[path] })
  })
  await page.goto('/admin/trading')
  await expect(page.getByLabel('业务日期', { exact: true })).toHaveValue('2026-10-13')
  await expect(page.getByRole('columnheader', { name: '成功处理项', exact: true })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '实际运行时间', exact: true })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '业务时钟', exact: true })).toBeVisible()
  const rows = page.locator('tbody tr')
  await expect(rows.nth(0)).toContainText('无待处理业务')
  await expect(rows.nth(0)).toContainText('旧记录未采集')
  await expect(rows.nth(1)).toContainText('等待处理')
  await rows.nth(1).getByRole('button', { name: '结果详情' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('收款卡受阻，等待处置')
  await expect(dialog.locator('pre')).toHaveCount(0)
  await dialog.getByRole('button', { name: 'Close this dialog' }).click()
  await rows.nth(2).getByRole('button', { name: '结果详情' }).click()
  await expect(dialog).toContainText('本批次未记录具体失败原因')
  await dialog.getByRole('button', { name: 'Close this dialog' }).click()
  await page.getByLabel('业务日期', { exact: true }).fill('2026-10-10')
  businessTime = '2026-10-15T09:10:00'
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByText('当前业务时间：2026-10-15 09:10:00')).toBeVisible()
  await expect(page.getByLabel('业务日期', { exact: true })).toHaveValue('2026-10-10')
  await page.getByRole('button', { name: '执行任务', exact: true }).click()
  await expect(page.getByText('本次执行：无待处理业务')).toBeVisible()
  await expect(page.getByLabel('业务日期', { exact: true })).toHaveValue('2026-10-10')
})
