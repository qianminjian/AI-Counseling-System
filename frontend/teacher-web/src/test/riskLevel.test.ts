import { describe, expect, it } from 'vitest'
import { RISK_LEVEL_META, riskColor, riskLabel, riskHex, riskHexBright } from '../utils/riskLevel'

describe('riskLevel 单一源（FA-01，DOC-074）', () => {
  it('四等级全字段完整（antdColor/hex/hexBright/label 领域语义）', () => {
    expect(RISK_LEVEL_META).toEqual({
      0: { antdColor: 'default', hex: '#389E0D', hexBright: '#52c41a', label: '绿色' },
      1: { antdColor: 'gold', hex: '#D4B106', hexBright: '#ffd54f', label: '黄色' },
      2: { antdColor: 'orange', hex: '#D46B08', hexBright: '#ff9800', label: '橙色' },
      3: { antdColor: 'red', hex: '#CF1322', hexBright: '#f44336', label: '红色' },
    })
  })

  it('riskColor：0 级 default 灰 / 1-3 级递增预警色', () => {
    expect(riskColor(0)).toBe('default')
    expect(riskColor(1)).toBe('gold')
    expect(riskColor(2)).toBe('orange')
    expect(riskColor(3)).toBe('red')
  })

  it('riskLabel：0 级绿色（对齐领域语义，非大屏「安全」）', () => {
    expect(riskLabel(0)).toBe('绿色')
    expect(riskLabel(3)).toBe('红色')
  })

  it('riskHex：工作台亮底用风险色板契约值（黄/橙/红），3 级红色', () => {
    expect(riskHex(0)).toBe('#389E0D')
    expect(riskHex(1)).toBe('#D4B106')
    expect(riskHex(2)).toBe('#D46B08')
    expect(riskHex(3)).toBe('#CF1322')
  })

  it('riskHexBright：大屏暗底专用亮色系（保留项，doing/75 §7.3）', () => {
    expect(riskHexBright(0)).toBe('#52c41a')
    expect(riskHexBright(1)).toBe('#ffd54f')
    expect(riskHexBright(2)).toBe('#ff9800')
    expect(riskHexBright(3)).toBe('#f44336')
    expect(riskHexBright(null)).toBe('#999999')
  })

  it('越界/未知等级回退：undefined/null/负数/越界 → 灰「未知」，不渲染 undefined', () => {
    expect(riskColor(undefined)).toBe('default')
    expect(riskColor(null)).toBe('default')
    expect(riskColor(9)).toBe('default')
    expect(riskColor(-1)).toBe('default')
    expect(riskLabel(undefined)).toBe('未知')
    expect(riskHex(null)).toBe('#999999')
    expect(riskHexBright(9)).toBe('#999999')
  })
})
