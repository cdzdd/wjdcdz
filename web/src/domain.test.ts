import { describe, expect, it } from 'vitest'
import { buildOrderPayload, buildRedemptionGroup, decimal, jobOutcome, percent } from './domain'

describe('显式交易来源', () => {
  it('提交选定账户和卡版本，后续默认账户变化不能改写申请', () => {
    const selected = { id: 11, tradingAccountId: 7, bindingVersion: 2 }
    const payload = buildOrderPayload('PURCHASE', 9, selected, '1200.00', 'stable-request')
    selected.id = 22
    selected.tradingAccountId = 8
    selected.bindingVersion = 3
    expect(payload).toEqual({ type: 'PURCHASE', fundClassId: 9, tradingAccountId: 7, bankCardId: 11, bindingVersion: 2, amount: '1200.00', requestKey: 'stable-request' })
  })
  it('赎回提交份额而不是申购金额，保留四位精度', () => {
    expect(buildOrderPayload('REDEMPTION', 9, { id: 11, tradingAccountId: 7, bindingVersion: 2 }, '3.1250', 'r2')).toEqual({ type: 'REDEMPTION', fundClassId: 9, tradingAccountId: 7, bankCardId: 11, bindingVersion: 2, shares: '3.1250', requestKey: 'r2' })
  })
  it('拒绝零、负数及非十进制表达式，防止错误交易输入', () => {
    for (const value of ['0', '-1', '1e3', 'NaN', '', '1.234']) {
      expect(() => buildOrderPayload('PURCHASE', 9, { id: 11, tradingAccountId: 7, bindingVersion: 2 }, value, 'r3')).toThrow()
    }
  })
  it('多账户赎回不得静默忽略输入错误的来源账户', () => {
    expect(() => buildRedemptionGroup(9, [{ tradingAccountId:7, shares:'10.0000' }, { tradingAccountId:8, shares:'-1' }], 'group-1')).toThrow()
    expect(buildRedemptionGroup(9, [{ tradingAccountId:7, shares:'10.0000' }, { tradingAccountId:8, shares:'' }], 'group-1')).toEqual({ fundClassId:9, allocations:[{tradingAccountId:7,shares:'10.0000'}], requestKey:'group-1' })
  })
})
describe('金融展示', () => {
  it('大额十进制不经过二进制浮点而丢失分位', () => {
    expect(decimal('999999999999999999.99')).toBe('999,999,999,999,999,999.99')
  })
  it('展示负收益、零、缺失数据，并按四舍五入处理', () => {
    expect(decimal('-1200.125')).toBe('-1,200.13')
    expect(decimal('0')).toBe('0.00')
    expect(decimal(null)).toBe('—')
    expect(decimal('1.234567', 4)).toBe('1.2346')
    expect(percent('0.015')).toBe('1.50%')
  })
})

describe('任务批次结果', () => {
  it('历史 COMPLETED 记录的三个计数全零时明确没有待处理业务', () => {
    expect(jobOutcome({ status: 'COMPLETED', processed: 0, waiting: 0, failed: 0 })).toBe('NO_WORK')
  })
  it('没有成功项但存在等待项时保留等待结果', () => {
    expect(jobOutcome({ status: 'COMPLETED', processed: 0, waiting: 3, failed: 0 })).toBe('WAITING')
  })
  it('存在失败且没有成功项时显示失败，优先于等待', () => {
    expect(jobOutcome({ processed: 0, waiting: 2, failed: 1 })).toBe('FAILED')
  })
  it('成功与失败并存时显示部分失败', () => {
    expect(jobOutcome({ processed: 2, waiting: 1, failed: 1 })).toBe('PARTIAL')
  })
  it('有成功项和等待项时仍显示等待处理', () => {
    expect(jobOutcome({ processed: 2, waiting: 1, failed: 0 })).toBe('WAITING')
  })
  it('仅有成功项时显示已完成', () => {
    expect(jobOutcome({ processed: 4, waiting: 0, failed: 0 })).toBe('COMPLETED')
  })
})

describe('持久清算任务状态', () => {
  it('排队、运行和拒绝不会被零计数误判为空任务', () => {
    for (const state of ['QUEUED','RUNNING','REJECTED_QUEUE_FULL','INTERRUPTED','FAILED','WAITING']) expect(jobOutcome({state,processed:0,waiting:0,failed:0})).toBe(state)
    expect(jobOutcome({state:'COMPLETED',processed:0,waiting:0,failed:0})).toBe('COMPLETED')
  })
})
