/**
 * CFG-004（doing/84 §四.3）：绑定表单校验纯函数测试
 * 覆盖：空归属 ID / 非 6 位验证码 / 合法输入（AC-84-10/12 校验规则）。
 */
import { describe, it, expect } from 'vitest'
import { validateBindInput } from '../utils/deviceBind'

describe('validateBindInput（AC-84-10/12 校验规则）', () => {
  it('归属 ID 为空 → 提示填写归属 ID', () => {
    expect(validateBindInput('', '123456')).toBe('请填写归属 ID')
    expect(validateBindInput('  ', '123456')).toBe('请填写归属 ID')
  })

  it('验证码非 6 位数字 → 提示验证码格式', () => {
    expect(validateBindInput('room-1', '12345')).toBe('请输入设备语音播报的 6 位验证码')
    expect(validateBindInput('room-1', '1234567')).toBe('请输入设备语音播报的 6 位验证码')
    expect(validateBindInput('room-1', 'abcdef')).toBe('请输入设备语音播报的 6 位验证码')
  })

  it('归属 ID + 6 位数字验证码 → 合法（返回 null）', () => {
    expect(validateBindInput('3a1b2c3d-0000-0000-0000-000000000001', '123456')).toBeNull()
  })
})
