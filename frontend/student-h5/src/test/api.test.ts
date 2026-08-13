import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
// DC-005：tryRefresh 别名已删，刷新行为直测共享模块（SPEC §19 验收：三端零副本）
import { refreshTokens } from '../../../shared/src/auth-transport/refresh'
import type { TokenStorage } from '../../../shared/src/auth-transport/tokenStorage'
import {
  getToken, setToken, getRefreshToken, setRefreshToken,
  clearToken, getUser, setUser,
  isConsentDone, markConsentDone, ConsentKeys,
  isAuthenticated, authFetch, api, fetchWarmPrompt,
  fetchSystemConfig, fetchLoginPrompt, fetchTtsSynthesize, fetchVoiceAnalyze,
  trialRegister, pinLogin, setPin, issueVoiceCredential,
  voiceLogin, requestGuardianConsent, confirmGuardianConsent,
} from '../api'

// DC-005：直测共享 refreshTokens 用的 storage（与 api.ts 同源 sessionStorage 键）
const tokenStorage: TokenStorage = {
  getToken, setToken, getRefreshToken, setRefreshToken, clear: clearToken,
}

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

  describe('ConsentKeys（F-9 同意键单点收敛，ARCH-005）', () => {
    it('枚举值对齐设计（_v1 语义键）', () => {
      expect(ConsentKeys.NOTICE).toBe('mindsafe_consent_v1')
      expect(ConsentKeys.VOICE).toBe('mindsafe_voice_consent_v1')
      expect(ConsentKeys.VOICE_CALL).toBe('mindsafe_voicecall_consent_v1')
    })

    it('markConsentDone 写入新键 mindsafe_consent_v1', () => {
      markConsentDone()
      expect(localStorage.getItem(ConsentKeys.NOTICE)).toBe('1')
      expect(isConsentDone()).toBe(true)
    })

    it('旧键 mindsafe_consent_done=1 → 迁移兼容返回 true 并写入新键', () => {
      localStorage.setItem('mindsafe_consent_done', '1')
      expect(isConsentDone()).toBe(true)
      expect(localStorage.getItem(ConsentKeys.NOTICE)).toBe('1')
    })

    it('无任何键时返回 false 且不写入新键', () => {
      expect(isConsentDone()).toBe(false)
      expect(localStorage.getItem(ConsentKeys.NOTICE)).toBeNull()
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
        .mockResolvedValueOnce({ json: () => Promise.resolve({ code: 0, data: { token: 'new_t', refreshToken: 'new_r' } }) })
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
        .mockResolvedValueOnce({ json: () => Promise.resolve({ code: 20001 }) })
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

  describe('fetchWarmPrompt（暖场请求，P0-1 统一认证接缝）', () => {
    it('POST 到 nudge 端点并携带 silenceSeconds 与 Authorization', async () => {
      setToken('tk')
      const res = { status: 200, ok: true }
      mockFetch.mockResolvedValue(res)
      const result = await fetchWarmPrompt('s1', 30)
      expect(result).toBe(res)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/chat/sessions/s1/nudge',
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({ Authorization: 'Bearer tk' }),
          body: JSON.stringify({ silenceSeconds: 30 }),
        })
      )
    })

    it('401 时自动刷新并重放（不静默失败）', async () => {
      setToken('expired')
      setRefreshToken('valid_rt')
      mockFetch
        .mockResolvedValueOnce({ status: 401 })
        .mockResolvedValueOnce({ json: () => Promise.resolve({ code: 0, data: { token: 'new_t', refreshToken: 'new_r' } }) })
        .mockResolvedValueOnce({ status: 200, ok: true })
      const res = await fetchWarmPrompt('s1', 25)
      expect(res.status).toBe(200)
      expect(getToken()).toBe('new_t')
    })

    it('返回原始 Response 供调用方解析 SSE', async () => {
      setToken('tk')
      const sseBody = { getReader: () => ({}) }
      const res = { status: 200, ok: true, body: sseBody }
      mockFetch.mockResolvedValue(res)
      const result = await fetchWarmPrompt('s1', 25)
      expect(result.body).toBe(sseBody)
    })
  })

  describe('端点收敛函数（F-2/F-3 具名 authFetch 接缝，ARCH-005）', () => {
    it('fetchSystemConfig：GET /api/v1/system/config 携带 signal 与 Accept', async () => {
      const controller = new AbortController()
      const res = { status: 200, ok: true, json: () => Promise.resolve({ code: 0, data: {} }) }
      mockFetch.mockResolvedValue(res)
      const result = await fetchSystemConfig(controller.signal)
      expect(result).toBe(res)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/system/config',
        expect.objectContaining({
          headers: expect.objectContaining({ Accept: 'application/json' }),
          signal: controller.signal,
        })
      )
    })

    it('fetchLoginPrompt：POST /api/v1/tts/login-prompt JSON { text, persona }', async () => {
      setToken('tk')
      const res = { status: 200, ok: true, blob: () => Promise.resolve(new Blob()) }
      mockFetch.mockResolvedValue(res)
      const result = await fetchLoginPrompt('你好呀', 'BOBO')
      expect(result).toBe(res)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/tts/login-prompt',
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({
            'Content-Type': 'application/json',
            Authorization: 'Bearer tk',
          }),
          body: JSON.stringify({ text: '你好呀', persona: 'BOBO' }),
        })
      )
    })

    it('fetchTtsSynthesize：POST /api/v1/tts/synthesize 透传 payload', async () => {
      setToken('tk')
      const payload = { text: '你好', persona: 'BOBO', emotion: 'happy', speed: 1.0, dialect: 'MANDARIN' }
      mockFetch.mockResolvedValue({ status: 200, ok: true, blob: () => Promise.resolve(new Blob()) })
      await fetchTtsSynthesize(payload)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/tts/synthesize',
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
          body: JSON.stringify(payload),
        })
      )
    })

    it('fetchVoiceAnalyze：POST /api/v1/voice/analyze 透传 multipart formData（不覆盖 Content-Type）', async () => {
      setToken('tk')
      const formData = new FormData()
      formData.append('file', new Blob(['audio']), 'a.webm')
      mockFetch.mockResolvedValue({ status: 200, ok: true })
      await fetchVoiceAnalyze(formData)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/voice/analyze',
        expect.objectContaining({
          method: 'POST',
          body: formData,
          headers: expect.objectContaining({ Authorization: 'Bearer tk' }),
        })
      )
    })
  })

  describe('refreshTokens（DC-005 共享模块）', () => {
    it('无 refreshToken 返回 false', async () => {
      expect(await refreshTokens(tokenStorage)).toBe(false)
    })

    it('刷新成功更新双 token', async () => {
      setRefreshToken('rt')
      mockFetch.mockResolvedValue({
        json: () => Promise.resolve({ code: 0, data: { token: 'nt', refreshToken: 'nr' } }),
      })
      expect(await refreshTokens(tokenStorage)).toBe(true)
      expect(getToken()).toBe('nt')
      expect(getRefreshToken()).toBe('nr')
    })

    it('网络异常返回 false', async () => {
      setRefreshToken('rt')
      mockFetch.mockRejectedValue(new Error('network'))
      expect(await refreshTokens(tokenStorage)).toBe(false)
    })
  })

  describe('api（通用请求）', () => {
    it('成功返回 data 字段', async () => {
      setToken('tk')
      mockFetch.mockResolvedValue({
        status: 200,
        json: () => Promise.resolve({ code: 0, data: { name: 'test' } }),
      })
      const result = await api('/chat/sessions')
      expect(result).toEqual({ name: 'test' })
    })

    it('success=false 抛出 Error', async () => {
      setToken('tk')
      mockFetch.mockResolvedValue({
        status: 200,
        json: () => Promise.resolve({ code: 20001, message: '参数错误' }),
      })
      await expect(api('/bad')).rejects.toThrow('参数错误')
    })

    it('自动拼接 /api/v1 前缀', async () => {
      setToken('tk')
      mockFetch.mockResolvedValue({
        status: 200,
        json: () => Promise.resolve({ code: 0, data: null }),
      })
      await api('/auth/set-pin', { method: 'POST', body: '{}' })
      expect(mockFetch.mock.calls[0][0]).toBe('/api/v1/auth/set-pin')
    })
  })

  describe('trialRegister', () => {
    it('注册成功返回 AuthResult', async () => {
      const authData = { token: 'tk', userId: '1', pseudonym: '花花' }
      mockFetch.mockResolvedValue({
        json: () => Promise.resolve({ code: 0, data: authData }),
      })
      const result = await trialRegister({ inviteCode: 'ABC', pseudonym: '花花', age: 9, consentVersion: 'v1' })
      expect(result).toEqual(authData)
    })

    it('注册失败抛出错误', async () => {
      mockFetch.mockResolvedValue({
        json: () => Promise.resolve({ code: 20001, message: '邀请码无效' }),
      })
      await expect(trialRegister({ inviteCode: 'X', pseudonym: 'x', age: 9, consentVersion: 'v1' }))
        .rejects.toThrow('邀请码无效')
    })
  })

  describe('pinLogin', () => {
    it('PIN 登录成功', async () => {
      mockFetch.mockResolvedValue({
        json: () => Promise.resolve({ code: 0, data: { token: 'pin_tk', userId: '2' } }),
      })
      const result = await pinLogin('小明', '1234')
      expect(result.token).toBe('pin_tk')
    })

    it('PIN 错误抛出异常', async () => {
      mockFetch.mockResolvedValue({
        json: () => Promise.resolve({ code: 20001, message: 'PIN 码错误' }),
      })
      await expect(pinLogin('小明', '0000')).rejects.toThrow('PIN 码错误')
    })
  })

  describe('setPin', () => {
    it('设置 PIN 成功', async () => {
      setToken('tk')
      mockFetch.mockResolvedValue({
        status: 200,
        json: () => Promise.resolve({ code: 0, data: null }),
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
        json: () => Promise.resolve({ code: 0, data: { voiceCredential: 'cred_jwt' } }),
      })
      const cred = await issueVoiceCredential()
      expect(cred).toBe('cred_jwt')
    })
  })

  describe('voiceLogin', () => {
    it('声纹登录成功', async () => {
      mockFetch.mockResolvedValue({
        json: () => Promise.resolve({ code: 0, data: { token: 'v_tk', userId: 'u1' } }),
      })
      const result = await voiceLogin('device_cred')
      expect(result.token).toBe('v_tk')
      expect(mockFetch.mock.calls[0][0]).toBe('/api/v1/auth/voice-login')
    })

    it('声纹登录失败抛出错误', async () => {
      mockFetch.mockResolvedValue({
        json: () => Promise.resolve({ code: 20001, message: '凭证已过期' }),
      })
      await expect(voiceLogin('bad')).rejects.toThrow('凭证已过期')
    })
  })

  describe('requestGuardianConsent', () => {
    it('发起监护人同意请求', async () => {
      setToken('tk')
      mockFetch.mockResolvedValue({
        status: 200,
        json: () => Promise.resolve({ code: 0, data: null }),
      })
      await expect(requestGuardianConsent('13800138000')).resolves.toBeUndefined()
    })
  })

  describe('confirmGuardianConsent', () => {
    it('确认监护人同意', async () => {
      setToken('tk')
      mockFetch.mockResolvedValue({
        status: 200,
        json: () => Promise.resolve({ code: 0, data: null }),
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
        .mockResolvedValueOnce({ json: () => Promise.resolve({ code: 0, data: { token: 'new_t', refreshToken: 'new_r' } }) })
        // 重试原请求成功
        .mockResolvedValueOnce({ status: 200, json: () => Promise.resolve({ code: 0, data: { result: 'ok' } }) })
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
        .mockResolvedValueOnce({ json: () => Promise.resolve({ code: 20001 }) })
      await expect(api('/data')).rejects.toThrow('登录已过期')
      expect(reloadMock).toHaveBeenCalled()
    })
  })
})
