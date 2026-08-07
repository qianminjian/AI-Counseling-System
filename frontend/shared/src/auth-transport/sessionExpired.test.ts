// @vitest-environment jsdom
/**
 * DC-005 共享认证传输模块：handleSessionExpired 测试
 * （SPEC §19：loginPath 分支 → location.href；缺省 → location.reload；
 *  统一 401 登出决策点：clear 后跳转并抛错，调用方后续代码不可达）
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { handleSessionExpired } from './sessionExpired'
import { createSessionStorageTokens } from './tokenStorage'

describe('handleSessionExpired', () => {
  let mockLocation: { href: string; reload: ReturnType<typeof vi.fn> }

  beforeEach(() => {
    sessionStorage.clear()
    mockLocation = { href: '', reload: vi.fn() }
    Object.defineProperty(window, 'location', { value: mockLocation, configurable: true, writable: true })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loginPath 传入 → 清 token 并跳转 loginPath，且抛错（never 语义）', () => {
    const storage = createSessionStorageTokens('app_')
    storage.setToken('t')
    storage.setRefreshToken('r')
    sessionStorage.setItem('app_user', '{"id":1}')

    expect(() => handleSessionExpired(storage, '/parent/')).toThrow('登录已过期')
    expect(mockLocation.href).toBe('/parent/')
    expect(mockLocation.reload).not.toHaveBeenCalled()
    expect(storage.getToken()).toBeNull()
    expect(storage.getRefreshToken()).toBeNull()
    expect(sessionStorage.getItem('app_user')).toBeNull()
  })

  it('缺省 loginPath → 清 token 并整页刷新（location.reload）', () => {
    const storage = createSessionStorageTokens('app_')
    storage.setToken('t')
    storage.setRefreshToken('r')

    expect(() => handleSessionExpired(storage)).toThrow('登录已过期')
    expect(mockLocation.reload).toHaveBeenCalledTimes(1)
    expect(mockLocation.href).toBe('')
    expect(storage.getToken()).toBeNull()
  })

  // doing/73 T1（AC-4）：redirect 注入 —— parent 平台适配层传入 PlatformRedirect（H5 为 locationRedirect）
  it('redirect 注入 → 调用注入函数且不触碰 window.location', () => {
    const storage = createSessionStorageTokens('app_')
    storage.setToken('t')
    const redirect = vi.fn()

    expect(() => handleSessionExpired(storage, '/parent/', redirect)).toThrow('登录已过期')
    expect(redirect).toHaveBeenCalledWith('/parent/')
    expect(mockLocation.href).toBe('')
    expect(mockLocation.reload).not.toHaveBeenCalled()
    expect(storage.getToken()).toBeNull()
  })

  it('redirect 注入且缺省 loginPath → 注入函数收到空串（H5 实现内自行 reload 语义）', () => {
    const storage = createSessionStorageTokens('app_')
    const redirect = vi.fn()

    expect(() => handleSessionExpired(storage, undefined, redirect)).toThrow('登录已过期')
    expect(redirect).toHaveBeenCalledWith('')
    expect(mockLocation.reload).not.toHaveBeenCalled()
  })
})
