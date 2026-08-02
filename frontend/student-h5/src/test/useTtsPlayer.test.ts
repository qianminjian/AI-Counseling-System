import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'

// 创建可控的 mock audio 元素
let mockAudioInstance: any

function createMockAudio() {
  const el: any = {
    play: vi.fn(() => {
      // 模拟播放完成：异步触发 onended
      setTimeout(() => el.onended?.(), 10)
      return Promise.resolve()
    }),
    pause: vi.fn(),
    src: '',
    onended: null as any,
    onerror: null as any,
  }
  return el
}

vi.mock('../utils/audioUnlock', () => ({
  getGlobalAudioElement: vi.fn(() => {
    if (!mockAudioInstance) mockAudioInstance = createMockAudio()
    return mockAudioInstance
  }),
  getGlobalAudioContext: vi.fn(() => ({ state: 'running', resume: vi.fn() })),
  unlockAudio: vi.fn(),
}))

const mockAuthFetch = vi.fn()
vi.mock('../api', () => ({
  authFetch: (...args: any[]) => mockAuthFetch(...args),
}))

import { useTtsPlayer } from '../hooks/useTtsPlayer'

describe('hooks/useTtsPlayer', () => {
  beforeEach(() => {
    mockAudioInstance = createMockAudio()
    Object.defineProperty(window, 'speechSynthesis', {
      value: {
        getVoices: vi.fn(() => [{ lang: 'zh-CN', name: 'mock' }]),
        speak: vi.fn(),
        cancel: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      },
      writable: true,
      configurable: true,
    })
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:mock'),
      revokeObjectURL: vi.fn(),
    })
    vi.stubGlobal('SpeechSynthesisUtterance', vi.fn(() => ({
      lang: '', rate: 1, pitch: 1, voice: null, onend: null, onerror: null,
    })))
    mockAuthFetch.mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  describe('初始状态', () => {
    it('默认不播放、不静音、引擎为 backend', () => {
      const { result } = renderHook(() => useTtsPlayer())
      expect(result.current.playing).toBe(false)
      expect(result.current.muted).toBe(false)
      expect(result.current.engine).toBe('backend')
      expect(result.current.currentSentenceIdx).toBe(-1)
      expect(result.current.currentSentenceText).toBe('')
    })
  })

  describe('toggleMute', () => {
    it('切换静音状态', () => {
      const { result } = renderHook(() => useTtsPlayer())
      act(() => { result.current.toggleMute() })
      expect(result.current.muted).toBe(true)
      act(() => { result.current.toggleMute() })
      expect(result.current.muted).toBe(false)
    })
  })

  describe('stop', () => {
    it('停止后状态重置', () => {
      const { result } = renderHook(() => useTtsPlayer())
      act(() => { result.current.stop() })
      expect(result.current.playing).toBe(false)
      expect(result.current.currentSentenceIdx).toBe(-1)
    })
  })

  describe('speak（静音时）', () => {
    it('静音时不调用后端', async () => {
      const { result } = renderHook(() => useTtsPlayer())
      act(() => { result.current.setMuted(true) })
      await act(async () => { await result.current.speak('你好') })
      expect(mockAuthFetch).not.toHaveBeenCalled()
    })
  })

  describe('speak（后端成功）', () => {
    it('后端合成成功时播放并复位', async () => {
      const mockBlob = new Blob(['audio'], { type: 'audio/mp3' })
      mockAuthFetch.mockResolvedValue({
        ok: true,
        status: 200,
        blob: () => Promise.resolve(mockBlob),
      })

      const { result } = renderHook(() => useTtsPlayer())
      await act(async () => {
        await result.current.speak('你好啊。今天开心吗？')
        // 等待 mock audio onended 触发
        await new Promise(r => setTimeout(r, 50))
      })
      expect(result.current.playing).toBe(false)
      expect(result.current.currentSentenceIdx).toBe(-1)
      expect(mockAuthFetch).toHaveBeenCalled()
    })
  })

  describe('speak（后端失败降级）', () => {
    it('后端失败后降级为浏览器 TTS', async () => {
      mockAuthFetch.mockRejectedValue(new Error('network'))
      // 让 browserSpeak 的 onend 立即触发
      const mockSpeak = window.speechSynthesis.speak as any
      mockSpeak.mockImplementation((utter: any) => {
        setTimeout(() => utter.onend?.(), 5)
      })

      const { result } = renderHook(() => useTtsPlayer())
      await act(async () => {
        await result.current.speak('测试。')
        await new Promise(r => setTimeout(r, 50))
      })
      expect(result.current.playing).toBe(false)
    })
  })

  describe('speakSentence', () => {
    it('单句播放后状态复位', async () => {
      const mockBlob = new Blob(['audio'], { type: 'audio/mp3' })
      mockAuthFetch.mockResolvedValue({
        ok: true,
        status: 200,
        blob: () => Promise.resolve(mockBlob),
      })

      const { result } = renderHook(() => useTtsPlayer())
      await act(async () => {
        await result.current.speakSentence('你好')
        await new Promise(r => setTimeout(r, 50))
      })
      expect(result.current.playing).toBe(false)
      expect(result.current.currentSentenceIdx).toBe(-1)
    })
  })

  describe('unlock', () => {
    it('调用 unlockAudio 全局模块', async () => {
      const { unlockAudio } = await import('../utils/audioUnlock')
      const { result } = renderHook(() => useTtsPlayer())
      act(() => { result.current.unlock() })
      expect(unlockAudio).toHaveBeenCalled()
    })
  })

  describe('自定义参数', () => {
    it('传入 persona/emotion/speed 影响请求体', async () => {
      const mockBlob = new Blob(['audio'], { type: 'audio/mp3' })
      mockAuthFetch.mockResolvedValue({
        ok: true,
        status: 200,
        blob: () => Promise.resolve(mockBlob),
      })

      const { result } = renderHook(() => useTtsPlayer({ persona: 'qiqiu', emotion: 'happy', speed: 1.2 }))
      await act(async () => {
        await result.current.speakSentence('你好')
        await new Promise(r => setTimeout(r, 50))
      })
      expect(mockAuthFetch).toHaveBeenCalledWith(
        '/api/v1/tts/synthesize-stream',
        expect.objectContaining({
          body: JSON.stringify({ text: '你好', persona: 'qiqiu', emotion: 'happy', speed: 1.2 }),
        })
      )
    })

    it('传入 dialect 时请求体包含 dialect 字段', async () => {
      const mockBlob = new Blob(['audio'], { type: 'audio/mp3' })
      mockAuthFetch.mockResolvedValue({
        ok: true,
        status: 200,
        blob: () => Promise.resolve(mockBlob),
      })

      const { result } = renderHook(() => useTtsPlayer({ persona: 'qiqiu', dialect: 'sichuan' }))
      await act(async () => {
        await result.current.speakSentence('你好')
        await new Promise(r => setTimeout(r, 50))
      })
      const callBody = JSON.parse(mockAuthFetch.mock.calls[0][1].body)
      expect(callBody.dialect).toBe('sichuan')
    })

    it('dialect 为 null 时请求体不包含 dialect 字段', async () => {
      const mockBlob = new Blob(['audio'], { type: 'audio/mp3' })
      mockAuthFetch.mockResolvedValue({
        ok: true,
        status: 200,
        blob: () => Promise.resolve(mockBlob),
      })

      const { result } = renderHook(() => useTtsPlayer({ persona: 'xiaoxing' }))
      await act(async () => {
        await result.current.speakSentence('你好')
        await new Promise(r => setTimeout(r, 50))
      })
      const callBody = JSON.parse(mockAuthFetch.mock.calls[0][1].body)
      expect(callBody).not.toHaveProperty('dialect')
    })
  })

  describe('stop 中断播放', () => {
    it('播放中 stop 立即停止并复位', async () => {
      // 让 audio.play 挂起（模拟正在播放）
      mockAudioInstance.play = vi.fn(() => new Promise(() => {}))
      const mockBlob = new Blob(['audio'], { type: 'audio/mp3' })
      mockAuthFetch.mockResolvedValue({
        ok: true,
        status: 200,
        blob: () => Promise.resolve(mockBlob),
      })

      const { result } = renderHook(() => useTtsPlayer())
      // 启动播放（不等待完成）
      let speakPromise: Promise<void>
      act(() => {
        speakPromise = result.current.speak('你好。')
      })
      await act(async () => { await new Promise(r => setTimeout(r, 20)) })
      // 此时应该在播放中
      expect(result.current.playing).toBe(true)
      // stop 中断
      act(() => { result.current.stop() })
      expect(result.current.playing).toBe(false)
      expect(result.current.currentSentenceIdx).toBe(-1)
      expect(mockAudioInstance.pause).toHaveBeenCalled()
    })
  })

  describe('浏览器 TTS 降级引擎', () => {
    it('后端不可用时降级为 speechSynthesis 播放', async () => {
      // 后端返回非 ok → synthesizeSentence 返回 null → 触发 browserSpeak
      mockAuthFetch.mockResolvedValue({ ok: false, status: 500 })
      const mockSpeak = window.speechSynthesis.speak as any
      mockSpeak.mockImplementation((utter: any) => {
        setTimeout(() => utter.onend?.(), 5)
      })

      const { result } = renderHook(() => useTtsPlayer())
      await act(async () => {
        await result.current.speak('测试降级。')
        await new Promise(r => setTimeout(r, 50))
      })
      // 验证降级发生了（speechSynthesis.speak 被调用）
      expect(mockSpeak).toHaveBeenCalled()
      expect(result.current.playing).toBe(false)
    })

    it('speechSynthesis 也不可用时不崩溃', async () => {
      mockAuthFetch.mockResolvedValue({ ok: false, status: 500 })
      // speechSynthesis.speak 抛异常模拟不可用
      const mockSpeak = window.speechSynthesis.speak as any
      mockSpeak.mockImplementation(() => { throw new Error('not available') })

      const { result } = renderHook(() => useTtsPlayer())
      await act(async () => {
        await result.current.speak('测试。')
        await new Promise(r => setTimeout(r, 50))
      })
      // 不崩溃，播放结束
      expect(result.current.playing).toBe(false)
    })
  })

  describe('currentSentenceText', () => {
    it('播放中返回当前句子文本', async () => {
      // 让 audio.play 挂起以保持播放状态
      mockAudioInstance.play = vi.fn(() => new Promise(() => {}))
      const mockBlob = new Blob(['audio'], { type: 'audio/mp3' })
      mockAuthFetch.mockResolvedValue({
        ok: true,
        status: 200,
        blob: () => Promise.resolve(mockBlob),
      })

      const { result } = renderHook(() => useTtsPlayer())
      act(() => { result.current.speak('第一句。第二句。') })
      await act(async () => { await new Promise(r => setTimeout(r, 20)) })
      // 播放中应该有当前句子
      if (result.current.playing) {
        expect(result.current.currentSentenceText).toBeTruthy()
      }
      act(() => { result.current.stop() })
    })
  })

  describe('playBlob 错误路径', () => {
    it('audio.onerror 触发时正常结束', async () => {
      // 模拟音频解码失败：play 后触发 onerror
      mockAudioInstance.play = vi.fn(() => {
        setTimeout(() => mockAudioInstance.onerror?.(), 5)
        return Promise.resolve()
      })
      const mockBlob = new Blob(['bad'], { type: 'audio/mp3' })
      mockAuthFetch.mockResolvedValue({
        ok: true,
        status: 200,
        blob: () => Promise.resolve(mockBlob),
      })

      const { result } = renderHook(() => useTtsPlayer())
      await act(async () => {
        await result.current.speak('测试。')
        await new Promise(r => setTimeout(r, 50))
      })
      expect(result.current.playing).toBe(false)
    })

    it('audio.play() 被拒绝时正常结束', async () => {
      mockAudioInstance.play = vi.fn(() => Promise.reject(new DOMException('NotAllowedError')))
      const mockBlob = new Blob(['audio'], { type: 'audio/mp3' })
      mockAuthFetch.mockResolvedValue({
        ok: true,
        status: 200,
        blob: () => Promise.resolve(mockBlob),
      })

      const { result } = renderHook(() => useTtsPlayer())
      await act(async () => {
        await result.current.speak('测试。')
        await new Promise(r => setTimeout(r, 50))
      })
      expect(result.current.playing).toBe(false)
    })
  })

  describe('speakSentence 浏览器降级', () => {
    it('后端不可用时单句降级为 speechSynthesis', async () => {
      mockAuthFetch.mockResolvedValue({ ok: false, status: 500 })
      const mockSpeak = window.speechSynthesis.speak as any
      mockSpeak.mockImplementation((utter: any) => {
        setTimeout(() => utter.onend?.(), 5)
      })

      const { result } = renderHook(() => useTtsPlayer())
      await act(async () => {
        await result.current.speakSentence('单句降级测试')
        await new Promise(r => setTimeout(r, 50))
      })
      expect(mockSpeak).toHaveBeenCalled()
      expect(result.current.playing).toBe(false)
    })

    it('speakSentence 浏览器 TTS 也不可用时不崩溃', async () => {
      mockAuthFetch.mockResolvedValue({ ok: false, status: 500 })
      const mockSpeak = window.speechSynthesis.speak as any
      mockSpeak.mockImplementation(() => { throw new Error('no tts') })

      const { result } = renderHook(() => useTtsPlayer())
      await act(async () => {
        await result.current.speakSentence('测试')
        await new Promise(r => setTimeout(r, 50))
      })
      expect(result.current.playing).toBe(false)
    })
  })

  describe('backendFailCount 连续失败降级', () => {
    it('连续失败 2 次后 synthesizeSentence 直接返回 null（不再请求后端）', async () => {
      // 前两次失败
      mockAuthFetch.mockResolvedValue({ ok: false, status: 500 })
      const mockSpeak = window.speechSynthesis.speak as any
      mockSpeak.mockImplementation((utter: any) => { setTimeout(() => utter.onend?.(), 5) })

      const { result } = renderHook(() => useTtsPlayer())
      // 第 1 次 speak：failCount → 1
      await act(async () => {
        await result.current.speak('句一。')
        await new Promise(r => setTimeout(r, 50))
      })
      // 第 2 次 speak：failCount → 2
      await act(async () => {
        await result.current.speak('句二。')
        await new Promise(r => setTimeout(r, 50))
      })
      expect(mockAuthFetch).toHaveBeenCalledTimes(2)

      // 第 3 次 speak：backendFailCount >= 2，不再调用后端
      mockAuthFetch.mockClear()
      await act(async () => {
        await result.current.speak('句三。')
        await new Promise(r => setTimeout(r, 50))
      })
      // 不再请求后端，直接浏览器降级
      expect(mockAuthFetch).not.toHaveBeenCalled()
      expect(mockSpeak).toHaveBeenCalled()
      expect(result.current.playing).toBe(false)
    })
  })
})
