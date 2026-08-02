import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useVoicePersona, VOICE_PERSONAS, SUPPORTED_DIALECTS, NATIVE_DIALECT_IDS } from '../hooks/useVoicePersona'

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

  describe('VOICE_PERSONAS 配置（design/56 7 音色矩阵）', () => {
    it('包含 7 种音色', () => {
      expect(Object.keys(VOICE_PERSONAS)).toHaveLength(7)
      expect(Object.keys(VOICE_PERSONAS)).toEqual(
        expect.arrayContaining(['xiaoxing', 'bobo', 'yueliang', 'xiaotaiyang', 'dashu', 'doudou', 'qiqiu'])
      )
    })

    it('每个音色包含 id/name/emoji/desc/detail/gender/dialectCapable', () => {
      for (const p of Object.values(VOICE_PERSONAS)) {
        expect(p).toHaveProperty('id')
        expect(p).toHaveProperty('name')
        expect(p).toHaveProperty('emoji')
        expect(p).toHaveProperty('desc')
        expect(p).toHaveProperty('detail')
        expect(p).toHaveProperty('gender')
        expect(p).toHaveProperty('dialectCapable')
      }
    })

    it('性别分布：4女+3男', () => {
      const females = Object.values(VOICE_PERSONAS).filter(p => p.gender === 'female')
      const males = Object.values(VOICE_PERSONAS).filter(p => p.gender === 'male')
      expect(females).toHaveLength(4) // xiaoxing, bobo, yueliang, qiqiu
      expect(males).toHaveLength(3) // xiaotaiyang, dashu, doudou
    })

    it('仅 qiqiu 拥有 dialectCapable=true', () => {
      for (const p of Object.values(VOICE_PERSONAS)) {
        if (p.id === 'qiqiu') {
          expect(p.dialectCapable).toBe(true)
        } else {
          expect(p.dialectCapable).toBe(false)
        }
      }
    })

    it('qiqiu 名称为“方言”', () => {
      expect(VOICE_PERSONAS.qiqiu.name).toBe('方言')
    })
  })

  describe('SUPPORTED_DIALECTS 配置', () => {
    it('包含 8 种方言（粤语/闽南话为原生，其余为 Instruct）', () => {
      expect(Object.keys(SUPPORTED_DIALECTS)).toHaveLength(8)
      expect(SUPPORTED_DIALECTS.sichuan.label).toBe('四川话')
      expect(SUPPORTED_DIALECTS.cantonese.label).toBe('粤语')
      expect(SUPPORTED_DIALECTS.minnan.label).toBe('闽南话')
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
      expect(result.current.persona.name).toBe('方言')
      expect(localStorage.getItem('mindsafe_voice_persona_v1')).toBe('qiqiu')
    })

    it('忽略无效 ID', () => {
      const { result } = renderHook(() => useVoicePersona())
      act(() => { result.current.changePersona('nonexistent') })
      expect(result.current.personaId).toBe('xiaoxing')
    })

    it('切换到 qiqiu → 方言自动启用', () => {
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.dialectEnabled).toBe(false)
      act(() => { result.current.changePersona('qiqiu') })
      expect(result.current.dialectEnabled).toBe(true)
    })

    it('切换离开 qiqiu → 方言自动禁用', () => {
      localStorage.setItem('mindsafe_voice_persona_v1', 'qiqiu')
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.dialectEnabled).toBe(true)
      act(() => { result.current.changePersona('xiaoxing') })
      expect(result.current.dialectEnabled).toBe(false)
    })
  })

  describe('方言状态管理（design/56 §三：条件启用）', () => {
    it('默认非 qiqiu 音色，方言禁用', () => {
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.dialectEnabled).toBe(false)
      expect(result.current.activeDialect).toBeNull()
    })

    it('学生配置 dialect 后，selectedDialect 取默认值', () => {
      mockGetUser.mockReturnValue({ userId: '1', gender: 'female', dialect: 'sichuan' } as any)
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.selectedDialect).toBe('sichuan')
    })

    it('qiqiu 音色 + 有 dialect → activeDialect 生效', () => {
      mockGetUser.mockReturnValue({ userId: '1', gender: 'female', dialect: 'cantonese' } as any)
      localStorage.setItem('mindsafe_voice_persona_v1', 'qiqiu')
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.activeDialect).toBe('cantonese')
    })

    it('非 qiqiu 音色 → activeDialect 为 null（即使有 selectedDialect）', () => {
      mockGetUser.mockReturnValue({ userId: '1', gender: 'female', dialect: 'sichuan' } as any)
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.selectedDialect).toBe('sichuan')
      expect(result.current.activeDialect).toBeNull()
    })

    it('切换方言类型', () => {
      localStorage.setItem('mindsafe_voice_persona_v1', 'qiqiu')
      const { result } = renderHook(() => useVoicePersona())
      act(() => { result.current.changeDialect('henan') })
      expect(result.current.selectedDialect).toBe('henan')
      expect(result.current.activeDialect).toBe('henan')
    })

    it('忽略无效方言 ID', () => {
      const { result } = renderHook(() => useVoicePersona())
      act(() => { result.current.changeDialect('invalid') })
      expect(result.current.selectedDialect).toBeNull()
    })
  })

  describe('原生方言音色（粤语/闽南话）', () => {
    it('NATIVE_DIALECT_IDS 包含粤语/闽南话', () => {
      expect(NATIVE_DIALECT_IDS).toEqual(['cantonese', 'minnan'])
    })

    it('粤语 → hasNativeVoice=true', () => {
      localStorage.setItem('mindsafe_voice_persona_v1', 'qiqiu')
      localStorage.setItem('mindsafe_dialect_v1', 'cantonese')
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.hasNativeVoice).toBe(true)
    })

    it('闽南话 → hasNativeVoice=true', () => {
      localStorage.setItem('mindsafe_voice_persona_v1', 'qiqiu')
      localStorage.setItem('mindsafe_dialect_v1', 'minnan')
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.hasNativeVoice).toBe(true)
    })

    it('非原生方言 → hasNativeVoice=false', () => {
      localStorage.setItem('mindsafe_voice_persona_v1', 'qiqiu')
      localStorage.setItem('mindsafe_dialect_v1', 'sichuan')
      const { result } = renderHook(() => useVoicePersona())
      expect(result.current.hasNativeVoice).toBe(false)
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
