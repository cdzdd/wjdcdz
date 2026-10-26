export type Row = Record<string, any>
export interface CardSnapshot { id: number; tradingAccountId: number; bindingVersion: number }
export interface Column { key: string; label: string; kind?: 'money' | 'shares' | 'nav' | 'percent' | 'state' | 'bool' | 'date' }
export interface Field { key: string; label: string; type?: 'text' | 'date' | 'datetime-local' | 'textarea' | 'select' | 'checkbox' | 'password'; options?: { value: string | number; label: string }[]; required?: boolean; hint?: string }
export function positiveDecimal(value: string, places = 2): boolean {
  return new RegExp('^\\d+(\\.\\d{1,' + places + '})?$').test(value) && /[1-9]/.test(value)
}
export function buildOrderPayload(type: string, fundClassId: number, card: CardSnapshot, value: string, requestKey: string): Row {
  if (!positiveDecimal(value, type === 'REDEMPTION' ? 4 : 2)) throw new Error(type === 'REDEMPTION' ? '请输入大于零、最多四位小数的份额' : '请输入大于零、最多两位小数的金额')
  return { type, fundClassId, tradingAccountId: card.tradingAccountId, bankCardId: card.id, bindingVersion: card.bindingVersion, [type === 'REDEMPTION' ? 'shares' : 'amount']: value, requestKey }
}
export function buildRedemptionGroup(fundClassId:number,rows:{tradingAccountId:number;shares:string}[],requestKey:string):Row {
  const allocations=rows.filter(row=>row.shares!=='')
  if(!fundClassId||!allocations.length)throw new Error('请选择基金，并至少输入一个来源账户的赎回份额')
  for(const row of allocations)if(!positiveDecimal(row.shares,4))throw new Error('交易账户 '+row.tradingAccountId+' 的份额需大于零，最多四位小数')
  return {fundClassId,allocations:allocations.map(row=>({...row})),requestKey}
}
export function decimal(value: unknown, places = 2): string {
  if (value == null || value === '') return '—'
  const match = String(value).match(/^(-?)(\d+)(?:\.(\d+))?$/)
  if (!match) return '—'
  const fraction = (match[3] || '').padEnd(places + 1, '0')
  let units = BigInt(match[2] + fraction.slice(0, places))
  if (Number(fraction[places]) >= 5) units += 1n
  const padded = units.toString().padStart(places + 1, '0')
  const integer = (places ? padded.slice(0, -places) : padded).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return (units > 0n ? match[1] : '') + integer + (places ? '.' + padded.slice(-places) : '')
}
export function percent(value: unknown): string {
  if (value == null || value === '') return '—'
  const match = String(value).match(/^(-?)(\d+)(?:\.(\d+))?$/)
  if (!match) return '—'
  const part = (match[3] || '').padEnd(2, '0')
  return decimal(match[1] + (match[2] + part.slice(0, 2)).replace(/^0+(?=\d)/, '') + '.' + (part.slice(2) || '0')) + '%'
}
export function jobOutcome(row: Row): string {
  if (row.state) return row.state
  if (row.failed > 0) return row.processed > 0 ? 'PARTIAL' : 'FAILED'
  if (row.waiting > 0) return 'WAITING'
  return row.processed > 0 ? 'COMPLETED' : 'NO_WORK'
}
export const labels: Record<string, string> = {
  QUEUED:'排队中', RUNNING:'运行中', INTERRUPTED:'运行中断', REJECTED:'任务被拒绝', REJECTED_QUEUE_FULL:'队列已满', READY:'可执行', STALE:'预演已过期', REVERSED:'已冲正', RECLEAR:'重新清算', SEAL_APPLICATIONS:'申请封账', EXPORT_APPLICATIONS:'申请文件导出', IMPORT_MARKET:'行情导入', RECONCILE:'资产对账',
  ACTIVE: '可用', DISABLED: '已停用', UNBOUND: '已解绑', REPLACED: '已换卡', RECEIVABLE: '可收款', BLOCKED: '收款受阻',
  OPEN: '开放交易', NORMAL: '正常', RAISING: '募集认购', OFFERING: '认购', BUILDING: '封闭建仓', CLOSED: '封闭建仓', SUSPENDED: '暂停交易', TERMINATED: '已终止', LIQUIDATED: '已清盘',
  SUBMITTED: '待确认', CONFIRMED: '待到账', COMPLETED: '已完成', CANCELLED: '已撤销', FAILED: '失败', PARTIAL: '部分失败', NO_WORK: '无待处理业务', PENDING: '待处理', PAUSED: '已暂停', APPROVED: '已批准', WAITING: '等待处理', PAID: '已支付', DRAFT: '草稿', SNAPSHOTTED: '权益已登记', PROCESSING: '处理中', REFUND_PENDING: '待原路退款',
  PURCHASE: '申购', REDEMPTION: '赎回', RECOGNITION: '认购', OFFERING_ORDER: '认购', CONVERSION: '转换', CASH: '现金分红', REINVEST: '红利再投资',
  STOCK: '股票型', BOND: '债券型', MIXED: '混合型', MONEY: '货币型', WEEKLY: '每周', MONTHLY: '每月', USER: '投资者', INVESTOR: '投资者', ADMIN: '管理员', OPERATOR: '操作员',
  ESTABLISHED: '募集成立', CONFIRM: '交易确认', PAY: '款项支付', PAYOUT: '款项结算', SIP: '定投执行', MARKET: '市场数据', ALL: '全部任务', TOP_UP: '模拟充值', TOPUP: '模拟充值', WITHDRAWAL: '模拟提现', WITHDRAW: '模拟提现', INITIAL: '初始资金', ORDER: '交易', DIVIDEND: '分红', MONEY_INCOME: '货币收益', MONEY_EXIT_INCOME: '货币基金末期收益', MONEY_TAIL: '收益尾差处置', LIQUIDATION: '清盘', CREDIT: '现金入账', DEBIT: '现金扣除', RESERVE: '预留冻结', RELEASE: '释放冻结', CORRECTION: '现金更正'
}
export function label(value: unknown): string { return value == null || value === '' ? '—' : labels[String(value)] || String(value) }
export function stateTone(value: unknown): string { return ['FAILED', 'BLOCKED', 'TERMINATED','REJECTED','REJECTED_QUEUE_FULL','INTERRUPTED','STALE'].includes(String(value)) ? 'danger' : ['QUEUED','RUNNING','SUBMITTED', 'CONFIRMED', 'PENDING', 'WAITING', 'PARTIAL', 'RAISING', 'PAUSED', 'PROCESSING', 'REFUND_PENDING', 'DRAFT'].includes(String(value)) ? 'warning' : 'success' }
export function cell(value: unknown, kind?: Column['kind']): string {
  if (kind === 'money') return decimal(value)
  if (kind === 'shares') return decimal(value, 4)
  if (kind === 'nav') return decimal(value, 6)
  if (kind === 'percent') return percent(value)
  if (kind === 'bool') return value === true || value === 1 || value === '1' ? '是' : '否'
  if (kind === 'date') return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
  return typeof value === 'object' && value !== null ? JSON.stringify(value) : label(value)
}
export function requestKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  // getRandomValues is available on HTTP as well as HTTPS.
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
export const fundTypes = [{ value: 'STOCK', label: '股票型' }, { value: 'BOND', label: '债券型' }, { value: 'MIXED', label: '混合型' }, { value: 'MONEY', label: '货币型' }]
