// doing/73 T1（AC-3）：platform 适配层——PlatformRequest H5 实现（fetch 包装工厂）
// 语义（与迁移前 api/index.ts request() 等价）：
// - Bearer 注入 + JSON 编解码
// - 401 → refreshTokens 成功 → 重放原请求一次（_retried 防环）
// - 401 刷新失败 → onSessionExpired 统一登出决策点
// - 非 401 业务错误 → toApiError；网络异常 → 原样 reject
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createPlatformRequest, type PlatformRequestDeps } from './request'
import type { TokenStorage } from '../../../shared/src/auth-transport/tokenStorage'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function makeStorage(initial?: { token?: string; refresh?: string }): TokenStorage {
  const store = new Map<string, string>()
  if (initial?.token) store.set('token', initial.token)
  if (initial?.refresh) store.set('refresh', initial.refresh)
  return {
    getToken: () => store.get('token') ?? null,
    setToken: (t) => void store.set('token', t),
    getRefreshToken: () => store.get('refresh') ?? null,
    setRefreshToken: (t) => void store.set('refresh', t),
    clear: () => store.clear(),
  }
}

describe('createPlatformRequest（PlatformRequest H5 实现）', () => {
  let fetchMock: ReturnType<typeof vi.fn>
  let onSessionExpired: ReturnType<typeof vi.fn>
  let deps: PlatformRequestDeps
  let storage: TokenStorage

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    storage = makeStorage({ token: 't1' })
    onSessionExpired = vi.fn()
    deps = { storage, onSessionExpired }
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('GET 携带 Bearer 并返回 data（URL 前缀 /api/v1）', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { success: true, data: { n: 3 } }))
    const request = createPlatformRequest(deps)
    const res = await request<{ n: number }>('/parent/report?studentUserId=1')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/parent/report?studentUserId=1',
      expect.objectContaining({ method: 'GET', headers: expect.objectContaining({ Authorization: 'Bearer t1' }) })
    )
    expect(res).toEqual({ n: 3 })
  })

  it('POST 序列化 JSON body + Content-Type', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { success: true }))
    const request = createPlatformRequest(deps)
    await request('/parent/auth/login', { method: 'POST', data: { phone: '138', password: 'p' } })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/parent/auth/login',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ phone: '138', password: 'p' }),
      })
    )
  })

  it('无 token 时不带 Authorization', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { success: true }))
    const request = createPlatformRequest({ ...deps, storage: makeStorage() })
    await request('/parent/report?x=1')
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(init.headers).not.toHaveProperty('Authorization')
  })

  it('401 + 刷新成功 → 重放一次且携带新 token，不触发登出', async () => {
    storage.setRefreshToken('rt')
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(200, { success: true, data: { token: 'nt', refreshToken: 'nr' } }))
      .mockResolvedValueOnce(jsonResponse(200, { success: true, data: { ok: 1 } }))
    const request = createPlatformRequest(deps)
    const res = await request<{ ok: number }>('/parent/report?x=1')
    expect(fetchMock).toHaveBeenCalledTimes(3) // 原请求 + refresh + 重放
    expect(storage.getToken()).toBe('nt')
    expect(onSessionExpired).not.toHaveBeenCalled()
    expect(res).toEqual({ ok: 1 })
    const replayInit = fetchMock.mock.calls[2][1] as RequestInit
    expect((replayInit.headers as Record<string, string>).Authorization).toBe('Bearer nt')
  })

  it('401 + 刷新失败 → 调用 onSessionExpired 决策点（不再重放）', async () => {
    storage.setRefreshToken('rt')
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(200, { success: false, message: 'refresh 已过期' }))
    const request = createPlatformRequest(deps)
    await expect(request('/parent/report?x=1')).rejects.toThrow()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(onSessionExpired).toHaveBeenCalledTimes(1)
  })

  it('业务性 401（信封 success:false）→ 直接抛错，不刷新不登出（登录失败/同意已撤回）', async () => {
    fetchMock.mockResolvedValue(jsonResponse(401, { success: false, code: 20001, message: '监护人同意已撤回，链接已失效' }))
    const request = createPlatformRequest(deps)
    await expect(request('/parent/report?x=1')).rejects.toMatchObject({
      message: '监护人同意已撤回，链接已失效',
      code: 20001,
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(onSessionExpired).not.toHaveBeenCalled()
    expect(storage.getToken()).toBe('t1')
  })

  it('401 刷新请求 URL 不带双前缀（refresh 内部自带 /api/v1）', async () => {
    storage.setRefreshToken('rt')
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(200, { success: true, data: { token: 'nt', refreshToken: 'nr' } }))
      .mockResolvedValueOnce(jsonResponse(200, { success: true, data: { ok: 1 } }))
    const request = createPlatformRequest(deps)
    await request('/parent/report?x=1')
    const refreshUrl = fetchMock.mock.calls[1][0]
    expect(refreshUrl).toBe('/api/v1/auth/refresh')
  })

  it('刷新失败且无 refresh token → 直接登出', async () => {
    fetchMock.mockResolvedValue(jsonResponse(401, {}))
    const request = createPlatformRequest({ ...deps, storage: makeStorage({ token: 'expired' }) })
    await expect(request('/parent/report?x=1')).rejects.toThrow()
    expect(onSessionExpired).toHaveBeenCalledTimes(1)
  })

  it('非 401 业务错误 → toApiError（message 透传）', async () => {
    fetchMock.mockResolvedValue(jsonResponse(400, { code: 4001, message: '手机号格式错误' }))
    const request = createPlatformRequest(deps)
    await expect(request('/parent/auth/login', { method: 'POST' })).rejects.toMatchObject({
      message: '手机号格式错误',
    })
  })

  // DOC-073 F1（doing/77 §24）：成功判定统一为 success 契约（对齐 shared apiError 语义）
  it('HTTP 200 + success:false → toApiError 抛业务错误（不再当成功返回）', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { success: false, code: 4001, message: '邀请码已过期' }))
    const request = createPlatformRequest(deps)
    await expect(request('/parent/consent/withdraw', { method: 'POST' })).rejects.toMatchObject({
      message: '邀请码已过期',
      code: 4001,
    })
  })

  // F-04：request 收敛为 data 解包（信封 success 已抛错，解包安全）
  it('HTTP 200 + success:true → 返回解包 data（message 透传，withdraw 场景）', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, {
      success: true,
      code: 0,
      message: '已撤回同意，孩子账号已冻结。',
      data: { studentUserId: 'c1', status: 'withdrawn', message: '已撤回同意，孩子账号已冻结。' },
    }))
    const request = createPlatformRequest(deps)
    const res = await request<{ message?: string }>('/parent/consent/withdraw', { method: 'POST' })
    expect(res).toMatchObject({ status: 'withdrawn', message: expect.stringContaining('已撤回同意') })
  })

  it('HTTP 200 + 非信封 body（success 缺失）→ 抛请求失败', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { weird: 'body' }))
    const request = createPlatformRequest(deps)
    await expect(request('/parent/report?x=1')).rejects.toMatchObject({
      message: expect.stringContaining('请求失败'),
    })
  })

  it('非 401 且响应体非法 → 兜底状态码 message', async () => {
    fetchMock.mockResolvedValue(new Response('oops', { status: 500 }))
    const request = createPlatformRequest(deps)
    await expect(request('/parent/report?x=1')).rejects.toMatchObject({
      message: '请求失败 (500)',
    })
  })

  it('网络异常 → 原样 reject（不误触发登出）', async () => {
    fetchMock.mockRejectedValue(new Error('network down'))
    const request = createPlatformRequest(deps)
    await expect(request('/parent/report?x=1')).rejects.toThrow('network down')
    expect(onSessionExpired).not.toHaveBeenCalled()
  })
})
