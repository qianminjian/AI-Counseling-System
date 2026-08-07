// @vitest-environment jsdom
/**
 * DC-005 共享认证传输模块：createAuthFetch 测试
 * （SPEC §19：401 刷新重放/刷新失败原样返回/非 401 透传/Headers 实例合并）
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createAuthFetch } from './authFetch'
import { createSessionStorageTokens, type TokenStorage } from './tokenStorage'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('createAuthFetch', () => {
  let storage: TokenStorage

  beforeEach(() => {
    sessionStorage.clear()
    vi.unstubAllGlobals()
    storage = createSessionStorageTokens('app_')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('无 token 时请求不带 Authorization 头', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, {}))
    vi.stubGlobal('fetch', fetchMock)
    const authFetch = createAuthFetch(storage)

    await authFetch('/api/v1/data')

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/data')
    expect((init as RequestInit).headers).not.toHaveProperty('Authorization')
  })

  it('有 token 时携带 Authorization: Bearer', async () => {
    storage.setToken('access-1')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, {}))
    vi.stubGlobal('fetch', fetchMock)
    const authFetch = createAuthFetch(storage)

    await authFetch('/api/v1/data')

    const [, init] = fetchMock.mock.calls[0]
    expect((init as RequestInit).headers).toEqual(
      expect.objectContaining({ Authorization: 'Bearer access-1' })
    )
  })

  it('headers 为 Headers 实例时（FormData 场景）合并保留既有头', async () => {
    storage.setToken('access-1')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, {}))
    vi.stubGlobal('fetch', fetchMock)
    const authFetch = createAuthFetch(storage)

    const headers = new Headers({ 'X-Custom': 'keep-me' })
    await authFetch('/api/v1/upload', { method: 'POST', headers, body: new FormData() })

    const [, init] = fetchMock.mock.calls[0]
    const merged = (init as RequestInit).headers as Record<string, string>
    expect(merged['x-custom']).toBe('keep-me')
    expect(merged.Authorization).toBe('Bearer access-1')
  })

  it('401 → 刷新成功 → 用新 token 重放原请求并返回成功响应', async () => {
    storage.setToken('access-1')
    storage.setRefreshToken('refresh-1')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(200, { success: true, data: { token: 'access-2', refreshToken: 'refresh-2' } }))
      .mockResolvedValueOnce(jsonResponse(200, {}))
    vi.stubGlobal('fetch', fetchMock)
    const authFetch = createAuthFetch(storage)

    const res = await authFetch('/api/v1/data')

    expect(res.status).toBe(200)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/auth/refresh')
    const [, retryInit] = fetchMock.mock.calls[2]
    expect((retryInit as RequestInit).headers).toEqual(
      expect.objectContaining({ Authorization: 'Bearer access-2' })
    )
    expect(storage.getToken()).toBe('access-2')
  })

  it('401 → 刷新失败 → 返回 401 Response 且不清 token（登出决策交调用方）', async () => {
    storage.setToken('access-1')
    storage.setRefreshToken('refresh-expired')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(401, {}))
    vi.stubGlobal('fetch', fetchMock)
    const authFetch = createAuthFetch(storage)

    const res = await authFetch('/api/v1/data')

    expect(res.status).toBe(401)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(storage.getToken()).toBe('access-1')
    expect(storage.getRefreshToken()).toBe('refresh-expired')
  })

  it('无 refresh token 时 401 → 直接返回 401（不发起 refresh）', async () => {
    storage.setToken('access-1')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(401, {}))
    vi.stubGlobal('fetch', fetchMock)
    const authFetch = createAuthFetch(storage)

    const res = await authFetch('/api/v1/data')

    expect(res.status).toBe(401)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('401 → 刷新成功但重放仍 401 → 返回 401（不无限循环）', async () => {
    storage.setToken('access-1')
    storage.setRefreshToken('refresh-1')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(200, { success: true, data: { token: 'access-2', refreshToken: 'refresh-2' } }))
      .mockResolvedValueOnce(jsonResponse(401, {}))
    vi.stubGlobal('fetch', fetchMock)
    const authFetch = createAuthFetch(storage)

    const res = await authFetch('/api/v1/data')

    expect(res.status).toBe(401)
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('非 401 响应直接透传（不触发刷新）', async () => {
    storage.setToken('access-1')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(500, {}))
    vi.stubGlobal('fetch', fetchMock)
    const authFetch = createAuthFetch(storage)

    const res = await authFetch('/api/v1/data')

    expect(res.status).toBe(500)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
