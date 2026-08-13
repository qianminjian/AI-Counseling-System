/**
 * ARCH-008 F-6：authFetch 统一认证接缝测试（401 刷新+重放，刷新失败才登出）
 *
 * 对齐 student-h5 authFetch 语义（doing/65 样板）：
 * - 自动携带 Bearer token（含 Headers 实例合并，multipart 场景）
 * - 401 → tryRefresh 成功 → 重放原请求一次
 * - 刷新失败 → 返回原始 401 Response，登出决策交给调用方（不静默清 token）
 * - 非 401 直接透传
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { authFetch } from '../api'

const TOKEN_KEY = 'mindsafe_token'
const REFRESH_KEY = 'mindsafe_refresh'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('authFetch：token 携带', () => {
  it('无 token 时请求不带 Authorization 头', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { success: true }))
    vi.stubGlobal('fetch', fetchMock)

    await authFetch('/api/v1/teacher/dashboard')

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/teacher/dashboard')
    expect((init as RequestInit).headers).not.toHaveProperty('Authorization')
  })

  it('有 token 时携带 Authorization: Bearer', async () => {
    sessionStorage.setItem(TOKEN_KEY, 'access-1')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { success: true }))
    vi.stubGlobal('fetch', fetchMock)

    await authFetch('/api/v1/teacher/dashboard')

    const [, init] = fetchMock.mock.calls[0]
    expect((init as RequestInit).headers).toEqual(
      expect.objectContaining({ Authorization: 'Bearer access-1' })
    )
  })

  it('headers 为 Headers 实例时（FormData 场景）合并保留既有头', async () => {
    sessionStorage.setItem(TOKEN_KEY, 'access-1')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { success: true }))
    vi.stubGlobal('fetch', fetchMock)

    const headers = new Headers({ 'X-Custom': 'keep-me' })
    await authFetch('/api/v1/admin/invite-codes/import-students', { method: 'POST', headers, body: new FormData() })

    const [, init] = fetchMock.mock.calls[0]
    // authFetch 将 Headers 实例展开为普通对象后传入 fetch（保留既有头 + 追加 token）；
    // Headers 规范键小写化（X-Custom → x-custom）
    const merged = (init as RequestInit).headers as Record<string, string>
    expect(merged['x-custom']).toBe('keep-me')
    expect(merged.Authorization).toBe('Bearer access-1')
  })
})

describe('authFetch：401 刷新与重放', () => {
  it('401 → 刷新成功 → 用新 token 重放原请求并返回成功响应', async () => {
    sessionStorage.setItem(TOKEN_KEY, 'access-1')
    sessionStorage.setItem(REFRESH_KEY, 'refresh-1')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { code: 20001 }))
      .mockResolvedValueOnce(jsonResponse(200, { code: 0, data: { token: 'access-2', refreshToken: 'refresh-2' } }))
      .mockResolvedValueOnce(jsonResponse(200, { code: 0, data: {} }))
    vi.stubGlobal('fetch', fetchMock)

    const res = await authFetch('/api/v1/teacher/dashboard')

    expect(res.status).toBe(200)
    // 三次调用：原请求 → refresh → 重放
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/auth/refresh')
    // 重放请求携带新 token
    const [, retryInit] = fetchMock.mock.calls[2]
    expect((retryInit as RequestInit).headers).toEqual(
      expect.objectContaining({ Authorization: 'Bearer access-2' })
    )
    // 新 token 已持久化
    expect(sessionStorage.getItem(TOKEN_KEY)).toBe('access-2')
  })

  it('401 → 刷新失败 → 返回 401 Response 且不清 token（登出决策交调用方）', async () => {
    sessionStorage.setItem(TOKEN_KEY, 'access-1')
    sessionStorage.setItem(REFRESH_KEY, 'refresh-expired')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { code: 20001 }))
      .mockResolvedValueOnce(jsonResponse(401, { code: 20001 }))
    vi.stubGlobal('fetch', fetchMock)

    const res = await authFetch('/api/v1/teacher/dashboard')

    expect(res.status).toBe(401)
    expect(fetchMock).toHaveBeenCalledTimes(2) // 原请求 + refresh，无重放
    // token 保留：未越权清 sessionStorage
    expect(sessionStorage.getItem(TOKEN_KEY)).toBe('access-1')
    expect(sessionStorage.getItem(REFRESH_KEY)).toBe('refresh-expired')
  })

  it('无 refresh token 时 401 → 直接返回 401（不发起 refresh）', async () => {
    sessionStorage.setItem(TOKEN_KEY, 'access-1')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(401, { code: 20001 }))
    vi.stubGlobal('fetch', fetchMock)

    const res = await authFetch('/api/v1/teacher/dashboard')

    expect(res.status).toBe(401)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('401 → 刷新成功但重放仍 401 → 返回 401（不无限循环）', async () => {
    sessionStorage.setItem(TOKEN_KEY, 'access-1')
    sessionStorage.setItem(REFRESH_KEY, 'refresh-1')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { code: 20001 }))
      .mockResolvedValueOnce(jsonResponse(200, { code: 0, data: { token: 'access-2', refreshToken: 'refresh-2' } }))
      .mockResolvedValueOnce(jsonResponse(401, { code: 20001 }))
    vi.stubGlobal('fetch', fetchMock)

    const res = await authFetch('/api/v1/teacher/dashboard')

    expect(res.status).toBe(401)
    expect(fetchMock).toHaveBeenCalledTimes(3) // 原请求 + refresh + 重放一次，止步
  })

  it('刷新响应非 success（业务拒绝）→ 视为刷新失败，返回 401', async () => {
    sessionStorage.setItem(TOKEN_KEY, 'access-1')
    sessionStorage.setItem(REFRESH_KEY, 'refresh-1')
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { code: 20001 }))
      .mockResolvedValueOnce(jsonResponse(200, { code: 20001, message: 'refresh 已过期' }))
    vi.stubGlobal('fetch', fetchMock)

    const res = await authFetch('/api/v1/teacher/dashboard')

    expect(res.status).toBe(401)
    expect(sessionStorage.getItem(TOKEN_KEY)).toBe('access-1')
  })

  it('非 401 响应直接透传（不触发刷新）', async () => {
    sessionStorage.setItem(TOKEN_KEY, 'access-1')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(500, { code: 20001 }))
    vi.stubGlobal('fetch', fetchMock)

    const res = await authFetch('/api/v1/teacher/dashboard')

    expect(res.status).toBe(500)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
