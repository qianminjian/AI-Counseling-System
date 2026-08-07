// doing/73 T3a：services 业务 API 测试（走真实 createPlatformRequest 逻辑，不整模块 mock）
// 策略（T4 §8.2）：mock 全局 fetch + 真实 sessionStorage 认证存取，覆盖成功/401 刷新/业务错误分支
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { parentLogin, parentRegister, getReport, withdrawConsent } from '../services/index'
import { setToken, setRefreshToken, getToken } from '../utils/auth'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('services 业务 API（平台化 request）', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    sessionStorage.clear()
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('parentLogin 携带 Bearer + JSON body，返回 data', async () => {
    setToken('tk')
    fetchMock.mockResolvedValue(jsonResponse(200, { success: true, data: { parentId: 'p1' } }))
    const res = await parentLogin({ phone: '13800138000', password: 'secret1' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/parent/auth/login',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ Authorization: 'Bearer tk' }),
        body: JSON.stringify({ phone: '13800138000', password: 'secret1' }),
      })
    )
    expect(res.data).toEqual({ parentId: 'p1' })
  })

  it('parentRegister 调注册端点', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { success: true, data: {} }))
    await parentRegister({ familyCode: 'ABC123', phone: '138', password: 'p1', relation: 'mother' })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/parent/auth/register',
      expect.objectContaining({ method: 'POST' })
    )
  })

  it('getReport 走 GET 且带 query', async () => {
    setToken('tk')
    fetchMock.mockResolvedValue(jsonResponse(200, { success: true, data: { sessionCount: 3 } }))
    const res = await getReport('c1')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/parent/report?studentUserId=c1',
      expect.objectContaining({ method: 'GET' })
    )
    expect(res.data).toEqual({ sessionCount: 3 })
  })

  it('withdrawConsent 调撤回端点', async () => {
    setToken('tk')
    fetchMock.mockResolvedValue(jsonResponse(200, { success: true, data: { message: 'ok' } }))
    await withdrawConsent('c1')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/parent/consent/withdraw',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ studentUserId: 'c1' }) })
    )
  })

  it('401 + refresh 成功 → 重放并返回最终 data（真实刷新编排）', async () => {
    setToken('tk')
    setRefreshToken('rt')
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(200, { success: true, data: { token: 'nt', refreshToken: 'nr' } }))
      .mockResolvedValueOnce(jsonResponse(200, { success: true, data: { sessionCount: 5 } }))
    const res = await getReport('c1')
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(res.data).toEqual({ sessionCount: 5 })
    // 新 token 已写回存储
    expect(getToken()).toBe('nt')
  })

  it('401 + 无 refresh token → 统一登出（清 token + 跳转登录页 + throw）', async () => {
    setToken('tk')
    const mockLocation = { href: '', assign: vi.fn(), reload: vi.fn() }
    Object.defineProperty(window, 'location', { value: mockLocation, configurable: true, writable: true })
    fetchMock.mockResolvedValue(jsonResponse(401, {}))
    await expect(getReport('c1')).rejects.toThrow('登录已过期')
    expect(mockLocation.assign).toHaveBeenCalledWith('/parent/')
    expect(getToken()).toBe('')
  })

  it('非 401 业务错误 → toApiError message', async () => {
    fetchMock.mockResolvedValue(jsonResponse(400, { code: 4001, message: '手机号或密码错误' }))
    await expect(parentLogin({ phone: '1', password: '2' })).rejects.toMatchObject({
      message: '手机号或密码错误',
    })
  })
})
