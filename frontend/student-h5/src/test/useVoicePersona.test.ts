import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useVoicePersona, VOICE_PERSONAS } from '../hooks/useVoicePersona'

// mock api.getUser
vi.mock('../api', () => ({
  getUser: vi.fn(() => null),
}))

import { getUser } from '../api'
const mockGetUser = vi.mocked(getUser)

describe('hooks/useVoicePersona', () => {
  beforeEach(() => {
    localStorage.clear()
    mockGetUser.mockReturnValue(null)
  })

  describe('VOICE_PERSONAS 配置', () => {
    it('包含 4 种音色', () => {
      expect(Object.keys(VOICE_PERSONAS)).toHaveLength(4)
      expect(Object.keys(VOICE_PERSONAS)).toEqual(
        expect.arrayContaining(['xiaoxing', 'qiqiu', 'yueliang', 'xiaotaiyang'])
      )
    })

    it('每个音色包含 id/name/emoji/desc/detail/gender', () => {
      for (const p of Object.values(VOICE_PERSONAS)) {
        expect(p).toHaveProperty('id')
        expect(p).toHaveProperty('name')
        expect(p).toHaveProperty('emoji')
        expect(p).toHaveProperty('desc')
        expect(p).toHaveProperty('detail')
        expect(p).toHaveProperty('gender')
      }
    })

    it('小太阳为 male，其余为 female', () => {
      expect(VOICE_PERSONAS.xiaotaiyang.gender).toBe('male')
      expect(VOICE_PERSONAS.xiaoxing.gender).toBe('female')
      expect(VOICE_PERSONAS.qiqiu.gender).toBe('female')
      expect(VOICE_PERSONAS.yueliang.gender).toBe('female')
    })
  })

  describe('默认音色选择', () => {
    it('无用户信息默认 xiaoxing', () => {
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.personaId).toBe('xiaoxing')
    })

    it('男性用户默认 xiaotaiyang', () => {
      mockGetUser.mockReturnValue({ userId: '1', gender: 'male' } as any)
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.personaId).toBe('xiaotaiyang')
    })

    it('女性用户默认 xiaoxing', () => {
      mockGetUser.mockReturnValue({ userId: '2', gender: 'female' } as any)
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.personaId).toBe('xiaoxing')
    })

    it('localStorage 已保存则优先使用', () => {
      localStorage.setItem('mindsafe_voice_persona_v1', 'yueliang')
      mockGetUser.mockReturnValue({ userId: '1', gender: 'male' } as any)
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.personaId).toBe('yueliang')
    })

    it('localStorage 保存无效值则回退默认', () => {
      localStorage.setItem('mindsafe_voice_persona_v1', 'invalid_id')
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.personaId).toBe('xiaoxing')
    })
  })

  describe('changePersona', () => {
    it('切换音色并持久化', () => {
      const { result } = renderHook(() => useVoicePersona())
      act(() => { result.current.changePersona('qiqiu') })
      expect(result.current.personaId).toBe('qiqiu')
      expect(result.current.persona.name).toBe('气球')
      expect(localStorage.getItem('mindsafe_voice_persona_v1')).toBe('qiqiu')
    })

    it('忽略无效 ID', () => {
      const { result } = renderHook(() => useVoicePersona())
      act(() => { result.current.changePersona('nonexistent') })
      expect(result.current.personaId).toBe('xiaoxing')
    })
  })

  describe('返回值', () => {
    it('persona 对象与 personaId 一致', () => {
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.persona).toEqual(VOICE_PERSONAS.xiaoxing)
    })

    it('personas 返回完整配置', () => {
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.personas).toEqual(VOICE_PERSONAS)
    })
  })
})
