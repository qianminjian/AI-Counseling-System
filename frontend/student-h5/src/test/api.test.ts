import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  getToken, setToken, getRefreshToken, setRefreshToken,
  clearToken, getUser, setUser,
  isConsentDone, markConsentDone,
  isAuthenticated, authFetch, tryRefresh, api,
  trialRegister, pinLogin, setPin, issueVoiceCredential,
  voiceLogin, requestGuardianConsent, confirmGuardianConsent,
} from '../api'

// mock fetch
const mockFetch = vi.fn()
vi.stubGlobal('fetch', mockFetch)

describe('api.ts', () => {
  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    mockFetch.mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('Token 管理（sessionStorage）', () => {
    it('setToken / getToken 读写一致', () => {
      setToken('abc123')
      expect(getToken()).toBe('abc123')
    })

    it('setRefreshToken / getRefreshToken', () => {
      setRefreshToken('refresh_xyz')
      expect(getRefreshToken()).toBe('refresh_xyz')
    })

    it('clearToken 清除所有会话数据', () => {
      setToken('t')
      setRefreshToken('r')
      setUser({ id: '1' })
      clearToken()
      expect(getToken()).toBeNull()
      expect(getRefreshToken()).toBeNull()
      expect(getUser()).toBeNull()
    })

    it('初始状态 token 为 null', () => {
      expect(getToken()).toBeNull()
    })
  })

  describe('User 管理', () => {
    it('setUser / getUser 对象序列化', () => {
      const user = { userId: '42', pseudonym: '小明', age: 10 }
      setUser(user)
      expect(getUser()).toEqual(user)
    })

    it('getUser 解析失败返回 null', () => {
      sessionStorage.setItem('mindsafe_student_user', 'invalid{json')
      expect(getUser()).toBeNull()
    })
  })

  describe('Consent（localStorage 跨会话）', () => {
    it('初始未完成', () => {
      expect(isConsentDone()).toBe(false)
    })

    it('markConsentDone 后持久', () => {
      markConsentDone()
      expect(isConsentDone()).toBe(true)
      // sessionStorage 清除不影响
      sessionStorage.clear()
      expect(isConsentDone()).toBe(true)
    })
  })

  describe('isAuthenticated', () => {
    it('无 token 返回 false', () => {
      expect(isAuthenticated()).toBe(false)
    })

    it('有 token 返回 true', () => {
      setToken('valid')
      expect(isAuthenticated()).toBe(true)
    })
  })

  describe('authFetch', () => {
    it('自动携带 Authorization header', async () => {
      setToken('my_token')
      mockFetch.mockResolvedValue({ status: 200, ok: true })
      await authFetch('/api/v1/test')
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/test', expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer my_token' }),
      }))
    })

    it('无 token 不携带 Authorization', async () => {
      mockFetch.mockResolvedValue({ status: 200, ok: true })
      await authFetch('/api/v1/test')
      const callHeaders = mockFetch.mock.calls[0][1].headers
      expect(callHeaders.Authorization).toBeUndefined()
    })

    it('401 时自动刷新并重试', async () => {
      setToken('expired')
      setRefreshToken('valid_refresh')
      // 第一次 401，刷新成功，第二次 200
      mockFetch
        .mockResolvedValueOnce({ status: 401 })
        .mockResolvedValueOnce({ json: () => Promise.resolve({ success: true, data: { token: 'new_t', refreshToken: 'new_r' } }) })
        .mockResolvedValueOnce({ status: 200, ok: true })
      const res = await authFetch('/api/v1/data')
      expect(res.status).toBe(200)
      expect(getToken()).toBe('new_t')
    })

    it('401 刷新失败不再重试', async () => {
      setToken('expired')
      setRefreshToken('bad_refresh')
      mockFetch
        .mockResolvedValueOnce({ status: 401 })
        .mockResolvedValueOnce({ json: () => Promise.resolve({ success: false }) })
      const res = await authFetch('/api/v1/data')
      expect(res.status).toBe(401)
      expect(mockFetch).toHaveBeenCalledTimes(2)
    })

    it('保留原始 init 参数（method/body）', async () => {
      setToken('tk')
      mockFetch.mockResolvedValue({ status: 200 })
      await authFetch('/api/v1/upload', { method: 'POST', body: 'data' })
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/upload', expect.objectContaining({
        method: 'POST',
        body: 'data',
      }))
    })
  })

  describe('tryRefresh', () => {
    it('无 refreshToken 返回 false', async () => {
      expect(await tryRefresh()).toBe(false)
    })

    it('刷新成功更新双 token', async () => {
      setRefreshToken('rt')
      mockFetch.mockResolvedValue({
        json: () => Promise.resolve({ success: true, data: { token: 'nt', refreshToken: 'nr' } }),
      })
      expect(await tryRefresh()).toBe(true)
      expect(getToken()).toBe('nt')
      expect(getRefreshToken()).toBe('nr')
    })

    it('网络异常返回 false', async () => {
      setRefreshToken('rt')
      mockFetch.mockRejectedValue(new Error('network'))
      expect(await tryRefresh()).toBe(false)
    })
  })

  describe('api（通用请求）', () => {
    it('成功返回 data 字段', async () => {
      setToken('tk')
      mockFetch.mockResolvedValue({
        status: 200,
        json: () => Promise.resolve({ success: true, data: { name: 'test' } }),
      })
      const result = await api('/chat/sessions')
      expect(result).toEqual({ name: 'test' })
    })

    it('success=false 抛出 Error', async () => {
      setToken('tk')
      mockFetch.mockResolvedValue({
        status: 200,
        json: () => Promise.resolve({ success: false, message: '参数错误' }),
      })
      await expect(api('/bad')).rejects.toThrow('参数错误')
    })

    it('自动拼接 /api/v1 前缀', async () => {
      setToken('tk')
      mockFetch.mockResolvedValue({
        status: 200,
        json: () => Promise.resolve({ success: true, data: null }),
      })
      await api('/auth/set-pin', { method: 'POST', body: '{}' })
      expect(mockFetch.mock.calls[0][0]).toBe('/api/v1/auth/set-pin')
    })
  })

  describe('trialRegister', () => {
    it('注册成功返回 AuthResult', async () => {
      const authData = { token: 'tk', userId: '1', pseudonym: '花花' }
      mockFetch.mockResolvedValue({
        json: () => Promise.resolve({ success: true, data: authData }),
      })
      const result = await trialRegister({ inviteCode: 'ABC', pseudonym: '花花', age: 9, consentVersion: 'v1' })
      expect(result).toEqual(authData)
    })

    it('注册失败抛出错误', async () => {
      mockFetch.mockResolvedValue({
        json: () => Promise.resolve({ success: false, message: '邀请码无效' }),
      })
      await expect(trialRegister({ inviteCode: 'X', pseudonym: 'x', age: 9, consentVersion: 'v1' }))
        .rejects.toThrow('邀请码无效')
    })
  })

  describe('pinLogin', () => {
    it('PIN 登录成功', async () => {
      mockFetch.mockResolvedValue({
        json: () => Promise.resolve({ success: true, data: { token: 'pin_tk', userId: '2' } }),
      })
      const result = await pinLogin('小明', '1234')
      expect(result.token).toBe('pin_tk')
    })

    it('PIN 错误抛出异常', async () => {
      mockFetch.mockResolvedValue({
        json: () => Promise.resolve({ success: false, message: 'PIN 码错误' }),
      })
      await expect(pinLogin('小明', '0000')).rejects.toThrow('PIN 码错误')
    })
  })

  describe('setPin', () => {
    it('设置 PIN 成功', async () => {
      setToken('tk')
      mockFetch.mockResolvedValue({
        status: 200,
        json: () => Promise.resolve({ success: true, data: null }),
      })
      await expect(setPin('1234')).resolves.toBeUndefined()
      expect(mockFetch.mock.calls[0][0]).toBe('/api/v1/auth/set-pin')
    })
  })

  describe('issueVoiceCredential', () => {
    it('签发凭证成功返回 voiceCredential', async () => {
      setToken('tk')
      mockFetch.mockResolvedValue({
        status: 200,
        json: () => Promise.resolve({ success: true, data: { voiceCredential: 'cred_jwt' } }),
      })
      const cred = await issueVoiceCredential()
      expect(cred).toBe('cred_jwt')
    })
  })

  describe('voiceLogin', () => {
    it('声纹登录成功', async () => {
      mockFetch.mockResolvedValue({
        json: () => Promise.resolve({ success: true, data: { token: 'v_tk', userId: 'u1' } }),
      })
      const result = await voiceLogin('device_cred')
      expect(result.token).toBe('v_tk')
      expect(mockFetch.mock.calls[0][0]).toBe('/api/v1/auth/voice-login')
    })

    it('声纹登录失败抛出错误', async () => {
      mockFetch.mockResolvedValue({
        json: () => Promise.resolve({ success: false, message: '凭证已过期' }),
      })
      await expect(voiceLogin('bad')).rejects.toThrow('凭证已过期')
    })
  })

  describe('requestGuardianConsent', () => {
    it('发起监护人同意请求', async () => {
      setToken('tk')
      mockFetch.mockResolvedValue({
        status: 200,
        json: () => Promise.resolve({ success: true, data: null }),
      })
      await expect(requestGuardianConsent('13800138000')).resolves.toBeUndefined()
    })
  })

  describe('confirmGuardianConsent', () => {
    it('确认监护人同意', async () => {
      setToken('tk')
      mockFetch.mockResolvedValue({
        status: 200,
        json: () => Promise.resolve({ success: true, data: null }),
      })
      await expect(confirmGuardianConsent('13800138000', '123456')).resolves.toBeUndefined()
    })
  })

  describe('api 401 刷新重试流程', () => {
    it('401 刷新成功后重试原请求并返回 data', async () => {
      setToken('expired')
      setRefreshToken('valid_rt')
      mockFetch
        // 第一次请求 401
        .mockResolvedValueOnce({ status: 401, json: () => Promise.resolve({}) })
        // 刷新 token 成功
        .mockResolvedValueOnce({ json: () => Promise.resolve({ success: true, data: { token: 'new_t', refreshToken: 'new_r' } }) })
        // 重试原请求成功
        .mockResolvedValueOnce({ status: 200, json: () => Promise.resolve({ success: true, data: { result: 'ok' } }) })
      const result = await api('/chat/sessions')
      expect(result).toEqual({ result: 'ok' })
      expect(getToken()).toBe('new_t')
    })

    it('401 刷新失败抛出登录过期错误', async () => {
      setToken('expired')
      setRefreshToken('bad_rt')
      // mock window.location.reload
      const reloadMock = vi.fn()
      Object.defineProperty(window, 'location', { value: { reload: reloadMock }, writable: true })
      mockFetch
        .mockResolvedValueOnce({ status: 401, json: () => Promise.resolve({}) })
        .mockResolvedValueOnce({ json: () => Promise.resolve({ success: false }) })
      await expect(api('/data')).rejects.toThrow('登录已过期')
      expect(reloadMock).toHaveBeenCalled()
    })
  })
})
