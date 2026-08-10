/**
 * doing/85 TOC-001/002：toC 登录/档案表单校验纯函数测试
 * 覆盖：手机号（空/非 11 位/合法）、验证码（空/非 6 位/合法）、昵称（空/合法）。
 */
import { describe, it, expect } from 'vitest'
import { validateTocPhone, validateTocCode, validateTocNickname } from '../utils/tocAuth'

describe('validateTocPhone', () => {
  it('空手机号拒绝', () => {
    expect(validateTocPhone('')).toBe('请输入手机号')
  })

  it('非 11 位手机号拒绝', () => {
    expect(validateTocPhone('123')).toBe('请输入 11 位手机号')
    expect(validateTocPhone('138001380001')).toBe('请输入 11 位手机号')
  })

  it('合法手机号通过', () => {
    expect(validateTocPhone('13800138000')).toBeNull()
  })
})

describe('validateTocCode', () => {
  it('空验证码拒绝', () => {
    expect(validateTocCode('')).toBe('请输入验证码')
  })

  it('非 6 位验证码拒绝', () => {
    expect(validateTocCode('12345')).toBe('请输入 6 位验证码')
  })

  it('6 位验证码通过', () => {
    expect(validateTocCode('123456')).toBeNull()
  })
})

describe('validateTocNickname', () => {
  it('空昵称拒绝', () => {
    expect(validateTocNickname('')).toBe('昵称必填')
    expect(validateTocNickname('  ')).toBe('昵称必填')
  })

  it('合法昵称通过', () => {
    expect(validateTocNickname('小明')).toBeNull()
  })
})
