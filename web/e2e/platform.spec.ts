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

test('任务中心区分空批次和等待项，只展示非零处理数量并保留选择的业务日期', async ({ page }, testInfo) => {
  let businessTime = '2026-10-13T09:10:00'
  const actualTime = '2026-10-04T14:10:00'
  const jobs = [
    { id: 9, type: 'ALL', date: '2026-10-13', status: 'COMPLETED', processed: 0, waiting: 0, failed: 0, details: '', createdAt: businessTime, actualRunAt: null },
    { id: 8, type: 'PAY', date: '2026-10-13', status: 'WAITING', processed: 0, waiting: 1, failed: 0, details: ['付款8：收款卡受阻，等待处置'], createdAt: businessTime, actualRunAt: actualTime },
    { id: 7, type: 'CONFIRM', date: '2026-10-13', status: 'FAILED', processed: 0, waiting: 0, failed: 1, details: '', createdAt: businessTime, actualRunAt: actualTime },
    { id: 6, type: 'ALL', date: '2026-10-13', status: 'PARTIAL', processed: 8, waiting: 2, failed: 1, details: ['本批次部分完成'], createdAt: businessTime, actualRunAt: actualTime }
  ]
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/admin/jobs' && route.request().method() === 'POST') {
      expect(route.request().postDataJSON()).toMatchObject({ type: 'ALL', date: '2026-10-10' })
      return route.fulfill({ json: { processed: 0, waiting: 0, failed: 0, details: [], status: 'NO_WORK', jobId: 10, businessTime, actualRunAt: actualTime } })
    }
    const responses: Record<string, unknown> = {
      '/api/auth/csrf': { token: 'test-token', headerName: 'X-CSRF-TOKEN' },
      '/api/me': { id: 1, username: 'admin', displayName: '管理员', role: 'ADMIN', mustChangePassword: false },
      '/api/admin/jobs/context': { businessTime, actualTime, demo: true },
      '/api/admin/clearing-runs': [], '/api/admin/jobs': jobs, '/api/admin/orders': [], '/api/admin/positions': [], '/api/admin/payouts': [], '/api/sip-plans': []
    }
    if (!(path in responses)) throw new Error('Unexpected request: ' + path)
    await route.fulfill({ json: responses[path] })
  })
  await page.goto('/admin/trading')
  await expect(page.getByLabel('业务日期', { exact: true })).toHaveValue('2026-10-13')
  await expect(page.getByRole('columnheader', { name: '处理情况', exact: true })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: /^(成功|等待|失败)处理项$/ })).toHaveCount(0)
  await expect(page.getByRole('columnheader', { name: '实际运行时间', exact: true })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '业务时钟', exact: true })).toBeVisible()
  const rows = page.locator('tbody tr')
  await expect(rows.nth(0)).toContainText('无待处理业务')
  await expect(rows.nth(0)).toContainText('旧记录未采集')
  await expect(rows.nth(0).locator('td').nth(4)).toHaveText('本次无需处理')
  await expect(rows.nth(1).locator('td').nth(4)).toHaveText('等待 1 项')
  await expect(rows.nth(2).locator('td').nth(4)).toHaveText('失败 1 项')
  await expect(rows.nth(3).locator('td').nth(4)).toContainText('成功 8 项')
  await expect(rows.nth(3).locator('td').nth(4)).toContainText('等待 2 项')
  await expect(rows.nth(3).locator('td').nth(4)).toContainText('失败 1 项')
  await expect(rows.nth(1)).toContainText('等待处理')
  await rows.nth(1).getByRole('button', { name: '结果详情' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('收款卡受阻，等待处置')
  await expect(dialog).toContainText('等待 1 项')
  await expect(dialog).not.toContainText(/(成功|失败)\s*0/)
  await expect(dialog.locator('pre')).toHaveCount(0)
  await dialog.getByRole('button', { name: 'Close this dialog' }).click()
  await rows.nth(2).getByRole('button', { name: '结果详情' }).click()
  await expect(dialog).toContainText('本批次未记录具体失败原因')
  await dialog.getByRole('button', { name: 'Close this dialog' }).click()
  await rows.nth(0).getByRole('button', { name: '结果详情' }).click()
  await expect(dialog).toContainText('本次无需处理')
  await expect(dialog).not.toContainText(/(成功|等待|失败)\s*(处理项\s*)?0/)
  await dialog.getByRole('button', { name: 'Close this dialog' }).click()
  await page.getByLabel('业务日期', { exact: true }).fill('2026-10-10')
  businessTime = '2026-10-15T09:10:00'
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByText('当前业务时间：2026-10-15 09:10:00')).toBeVisible()
  await expect(page.getByLabel('业务日期', { exact: true })).toHaveValue('2026-10-10')
  await page.getByRole('button', { name: '执行任务', exact: true }).click()
  await expect(page.getByText('本次执行：无待处理业务')).toBeVisible()
  await expect(page.locator('.job-result')).toContainText('本次无需处理')
  await expect(page.locator('.job-result')).not.toContainText(/(成功|等待|失败)\s*(处理项\s*)?0/)
  await expect(page.getByLabel('业务日期', { exact: true })).toHaveValue('2026-10-10')
  await page.screenshot({ path: testInfo.outputPath('job-display.png'), fullPage: true })
})

async function mockUpgrade(page: import('@playwright/test').Page, role='ADMIN') {
  await page.route('**/api/**', async route => {
    const path=new URL(route.request().url()).pathname, method=route.request().method()
    const rows: Record<string,unknown>={
      '/api/auth/csrf':{token:'test-token',headerName:'X-CSRF-TOKEN'},
      '/api/me':{id:1,username:'admin',displayName:'管理员',role,mustChangePassword:false},
      '/api/admin/jobs/context':{businessTime:'2026-10-14T16:00:00',actualTime:'2026-10-14T10:00:00'},
      '/api/admin/funds':[{id:1,code:'F1',name:'测试基金',state:'OPEN'}], '/api/registrars':[{id:1,name:'测试TA'}],
      '/api/admin/clearing-runs/41':{runId:41,state:'RUNNING',businessDate:'2026-10-14',type:'CONFIRM',progress:{percent:50},nodes:[{name:'CONFIRM',state:'RUNNING'}]},
      '/api/admin/reclearing/previews/51':{previewId:51,scopeVersion:'v1',state:'READY',affectedAccounts:[{tradingAccountId:7}],changes:[{sourceType:'ORDER',sourceId:1,tradingAccountId:7,before:{shares:'1000',amount:'1000'},after:{shares:'800',amount:'1000'}}],blockers:[]},
      '/api/admin/application-exports':[{id:61,businessDate:'2026-10-14',registrarId:1,state:'COMPLETED',rowCount:2,checksum:'abc',snapshotVersion:1}],
      '/api/orders':[{id:1,orderNo:'O1',applicationNo:'20261014000001',fundName:'测试基金',status:'COMPLETED'}],
      '/api/orders/1':{id:1,orderNo:'O1',applicationNo:'20261014000001',confirmationNo:'20261015000001'},
      '/api/orders/1/confirmations':[{confirmationNo:'20261015000001',roundId:41,status:'REVERSED',shares:'1000',effectiveDate:'2026-10-15'},{confirmationNo:'20261016000001',roundId:42,status:'ACTIVE',shares:'800',effectiveDate:'2026-10-15'}]
    }
    if (method==='POST' && path==='/api/admin/jobs') return route.fulfill({status:202,json:{runId:41,state:'QUEUED'}})
    if (method==='POST' && path==='/api/admin/nav-revisions') { expect(route.request().postDataJSON()).toMatchObject({fundClassId:1,marketDate:'2026-10-14',nav:'1.25',reason:'更正净值'});return route.fulfill({json:{revisionSetId:21}}) }
    if (method==='POST' && path==='/api/admin/reclearing/previews') return route.fulfill({json:rows['/api/admin/reclearing/previews/51']})
    if (method==='POST' && path==='/api/admin/reclearing/runs') {expect(route.request().postDataJSON()).toMatchObject({previewId:51,scopeVersion:'v1'});return route.fulfill({status:202,json:{runId:41}})}
    if (method==='POST' && path==='/api/admin/application-exports/61/regenerate') return route.fulfill({status:202,json:{exportId:61,runId:41}})
    await route.fulfill({json:rows[path]??[]})
  })
}
test('异步任务零计数保持运行状态并在刷新后恢复',async({page})=>{
  await mockUpgrade(page)
  await page.goto('/admin/trading')
  await page.getByRole('button',{name:'执行任务',exact:true}).click()
  await expect(page.locator('.job-result')).toContainText('运行中')
  await expect(page.locator('.job-result')).not.toContainText('无待处理业务')
  await page.reload()
  await expect(page.locator('.job-result')).toContainText('任务 41')
  await expect(page.locator('.job-result')).toContainText('50%')
})
test('候选修订到预演执行，文件下载与重导',async({page})=>{
  await mockUpgrade(page)
  await page.goto('/admin/products')
  await page.getByRole('button',{name:'净值修订',exact:true}).click()
  await page.getByLabel('行情所属日期').fill('2026-10-14')
  await page.getByLabel('修订单位净值').fill('1.25')
  await page.getByLabel('修订原因',{exact:true}).fill('更正净值')
  await page.getByRole('button',{name:'保存候选修订'}).click()
  await page.getByRole('link',{name:'查看影响预演'}).click()
  await page.getByRole('button',{name:'生成影响预演'}).click()
  await expect(page.getByText('1,000.0000', {exact:true})).toBeVisible()
  await expect(page.getByText('800.0000', {exact:true})).toBeVisible()
  await page.getByRole('button',{name:'确认执行重新清算',exact:true}).click()
  await expect(page.locator('.job-result')).toContainText('运行中')
  await page.getByRole('button',{name:'申请文件',exact:true}).click()
  await expect(page.getByRole('link',{name:'下载文件'})).toHaveAttribute('href','/api/admin/application-exports/61/download')
  await page.getByRole('button',{name:'重导同一快照'}).click()
  await expect(page.getByText('已提交文件任务 41')).toBeVisible()
})
test('投资者查看当前及旧确认轮次',async({page})=>{
  await mockUpgrade(page,'INVESTOR')
  await page.goto('/orders')
  await expect(page.getByText('20261014000001',{exact:true})).toBeVisible()
  await page.getByRole('button',{name:'详情',exact:true}).click()
  await expect(page.getByRole('dialog')).toContainText('确认历史')
  await expect(page.getByRole('dialog')).toContainText('20261016000001')
  await expect(page.getByRole('dialog')).toContainText('已冲正')
})

test('预演分页、资金不足与过期保护',async({page})=>{
  await mockUpgrade(page)
  let state='READY'
  await page.route('**/api/admin/reclearing/previews',route=>route.fulfill({json:{previewId:51,scopeVersion:'v1',state,affectedAccounts:[{tradingAccountId:7}],changes:[],nextCursor:200,changesTotal:201,blockers:state==='BLOCKED'?[{tradingAccountId:7,sourceType:'ORDER',sourceId:1,reason:'模拟现金不足',shortage:'20.00'}]:[]}}))
  await page.route('**/api/admin/reclearing/previews/51/items?afterId=200',route=>route.fulfill({json:{items:[{sourceType:'ORDER',sourceId:201,tradingAccountId:7,before:{shares:'1000'},after:{shares:'800'}}],nextCursor:null,total:201}}))
  await page.route('**/api/admin/reclearing/runs',route=>route.fulfill({status:409,json:{message:'预演已过期'}}))
  await page.goto('/admin/trading?revisionSetId=21')
  await page.getByRole('button',{name:'生成影响预演'}).click()
  await page.getByRole('button',{name:'加载更多差异'}).click()
  await expect(page.getByText('800.0000',{exact:true})).toBeVisible()
  await page.getByRole('button',{name:'确认执行重新清算'}).click()
  await expect(page.getByRole('alert').filter({hasText:'请重新生成影响预演'})).toBeVisible()
  state='BLOCKED'
  await page.getByRole('button',{name:'生成影响预演'}).click()
  await expect(page.getByText('模拟现金不足',{exact:true})).toBeVisible()
  await expect(page.getByText('20.00',{exact:true})).toBeVisible()
  await expect(page.getByRole('button',{name:'确认执行重新清算'})).toBeDisabled()
})
test('操作员可处理标准任务和文件，无法执行重新清算',async({page})=>{
  await mockUpgrade(page,'OPERATOR')
  await page.goto('/admin/trading?revisionSetId=21')
  await expect(page.getByRole('button',{name:'重新清算',exact:true})).toHaveCount(0)
  await expect(page.getByRole('button',{name:'生成影响预演'})).toHaveCount(0)
  await page.getByRole('button',{name:'任务中心',exact:true}).click()
  await expect(page.getByRole('button',{name:'执行任务',exact:true})).toBeVisible()
  await page.getByRole('button',{name:'申请文件',exact:true}).click()
  await expect(page.getByRole('link',{name:'下载文件'})).toBeVisible()
  await page.goto('/admin/products')
  await expect(page.getByRole('button',{name:'净值修订',exact:true})).toHaveCount(0)
})

test('募集结果异步提交后明确查看任务完成状态',async({page})=>{
  await mockUpgrade(page)
  await page.route('**/api/admin/funds',route=>route.fulfill({json:[{id:1,name:'募集基金',state:'RAISING',type:'MIXED'}]}))
  await page.route('**/api/admin/offerings/1/finalize',route=>route.fulfill({status:202,json:{runId:41,state:'QUEUED'}}))
  await page.goto('/admin/benefits')
  await page.getByRole('button',{name:'执行募集结果',exact:true}).click()
  await expect(page.getByText(/提交已受理，业务结果以任务完成状态为准/)).toBeVisible()
  await page.getByRole('link',{name:'查看任务进度与结果'}).click()
  await expect(page.locator('.job-result')).toContainText('运行中')
})

test('切换候选集合后必须重新预演',async({page})=>{
  await mockUpgrade(page)
  await page.goto('/admin/trading?revisionSetId=21')
  await page.getByRole('button',{name:'生成影响预演'}).click()
  await expect(page.getByRole('button',{name:'确认执行重新清算'})).toBeEnabled()
  await page.getByLabel('候选修订集合').fill('22')
  await expect(page.getByRole('button',{name:'确认执行重新清算'})).toHaveCount(0)
})
