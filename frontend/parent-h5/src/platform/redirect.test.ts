// doing/73 T1（AC-3）：platform 适配层——PlatformRedirect H5 实现
// 语义：整页跳转（location.assign），与现状 location.href 赋值等价（P1 小程序端换 Taro.reLaunch）
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { locationRedirect } from './redirect'

describe('locationRedirect（PlatformRedirect H5 实现）', () => {
  let mockLocation: { assign: ReturnType<typeof vi.fn>; href: string; reload: ReturnType<typeof vi.fn> }

  beforeEach(() => {
    mockLocation = { assign: vi.fn(), href: '', reload: vi.fn() }
    Object.defineProperty(window, 'location', { value: mockLocation, configurable: true, writable: true })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('跳转目标 URL（assign 调用）', () => {
    locationRedirect('/parent/')
    expect(mockLocation.assign).toHaveBeenCalledWith('/parent/')
  })

  it('空串 → 整页刷新（兼容 handleSessionExpired 缺省 loginPath 语义）', () => {
    locationRedirect('')
    expect(mockLocation.assign).not.toHaveBeenCalled()
    expect(mockLocation.reload).toHaveBeenCalledTimes(1)
  })
})
