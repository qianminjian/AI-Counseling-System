/**
 * DC-005 共享认证传输模块：ApiError / toApiError 测试
 * （SPEC §19：错误模型统一——code 传导三端，缺省 code 0 / message 请求失败）
 */
import { describe, it, expect } from 'vitest'
import { ApiError, toApiError } from './apiError'

describe('ApiError', () => {
  it('携带 code/message，name 为 ApiError，instanceof Error', () => {
    const err = new ApiError(20003, '需要监护人同意')
    expect(err.code).toBe(20003)
    expect(err.message).toBe('需要监护人同意')
    expect(err.name).toBe('ApiError')
    expect(err instanceof Error).toBe(true)
  })
})

describe('toApiError', () => {
  it('带值透传（code + message）', () => {
    const err = toApiError({ code: 1001, message: '参数错误' })
    expect(err.code).toBe(1001)
    expect(err.message).toBe('参数错误')
  })

  it('缺省 code 0 / message 请求失败（后端无码业务错误）', () => {
    const err = toApiError({})
    expect(err.code).toBe(0)
    expect(err.message).toBe('请求失败')
  })
})
