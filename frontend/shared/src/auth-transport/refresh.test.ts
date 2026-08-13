// @vitest-environment jsdom
/**
 * DC-005 共享认证传输模块：refreshTokens 测试
 * （SPEC §19：无 rt→false/成功双 token/网络异常→false/业务拒绝→false）
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { refreshTokens } from './refresh'
import { createSessionStorageTokens, type TokenStorage } from './tokenStorage'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('refreshTokens', () => {
  let storage: TokenStorage

  beforeEach(() => {
    sessionStorage.clear()
    vi.unstubAllGlobals()
    storage = createSessionStorageTokens('app_')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('无 refresh token → false 且不发起请求', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    expect(await refreshTokens(storage)).toBe(false)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('刷新成功 → 双 token 写入并返回 true（POST /api/v1/auth/refresh）', async () => {
    storage.setRefreshToken('rt')
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(200, { code: 0, data: { token: 'nt', refreshToken: 'nr' } })
    )
    vi.stubGlobal('fetch', fetchMock)
    expect(await refreshTokens(storage)).toBe(true)
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/auth/refresh', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ refreshToken: 'rt' }),
    }))
    expect(storage.getToken()).toBe('nt')
    expect(storage.getRefreshToken()).toBe('nr')
  })

  it('业务拒绝（success=false）→ false 且不写 token', async () => {
    storage.setRefreshToken('rt')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, { code: 20001, message: 'refresh 已过期' })))
    expect(await refreshTokens(storage)).toBe(false)
    expect(storage.getToken()).toBeNull()
  })

  it('网络异常 → false', async () => {
    storage.setRefreshToken('rt')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network')))
    expect(await refreshTokens(storage)).toBe(false)
  })

  // doing/73 T1（AC-4）：fetchImpl 注入 —— parent 平台适配层传入 PlatformRequest 的 fetch 包装
  it('fetchImpl 注入 → 使用注入实现而非全局 fetch', async () => {
    storage.setRefreshToken('rt')
    const injected = vi.fn().mockResolvedValue(
      jsonResponse(200, { code: 0, data: { token: 'nt', refreshToken: 'nr' } })
    )
    const globalFetch = vi.fn()
    vi.stubGlobal('fetch', globalFetch)
    expect(await refreshTokens(storage, '', injected)).toBe(true)
    expect(injected).toHaveBeenCalledWith('/api/v1/auth/refresh', expect.objectContaining({ method: 'POST' }))
    expect(globalFetch).not.toHaveBeenCalled()
    expect(storage.getToken()).toBe('nt')
  })

  it('fetchImpl 注入且 baseUrl 非空 → URL 带前缀', async () => {
    storage.setRefreshToken('rt')
    const injected = vi.fn().mockResolvedValue(jsonResponse(200, { code: 0, data: { token: 'nt', refreshToken: 'nr' } }))
    await refreshTokens(storage, 'http://localhost:8080', injected)
    expect(injected).toHaveBeenCalledWith('http://localhost:8080/api/v1/auth/refresh', expect.anything())
  })
})
