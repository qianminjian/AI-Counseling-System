import { describe, it, expect, vi, beforeEach } from 'vitest'

// 每个测试重新加载模块（重置模块级状态）
async function freshImport() {
  vi.resetModules()
  return import('../utils/audioUnlock')
}

describe('utils/audioUnlock', () => {
  let mockCtx: any
  let mockAudio: any

  beforeEach(() => {
    // Mock AudioContext
    mockCtx = {
      state: 'suspended',
      resume: vi.fn(),
      createBuffer: vi.fn(() => ({})),
      createBufferSource: vi.fn(() => ({ buffer: null, connect: vi.fn(), start: vi.fn() })),
      destination: {},
    }
    vi.stubGlobal('AudioContext', vi.fn(() => mockCtx))
    vi.stubGlobal('webkitAudioContext', undefined)

    // Mock Audio element
    mockAudio = {
      preload: '',
      playsInline: false,
      setAttribute: vi.fn(),
      src: '',
      volume: 1,
      currentTime: 0,
      play: vi.fn(() => Promise.resolve()),
      pause: vi.fn(),
    }
    vi.stubGlobal('Audio', vi.fn(() => mockAudio))

    // Mock speechSynthesis
    vi.stubGlobal('speechSynthesis', {
      speak: vi.fn(),
      cancel: vi.fn(),
    })
    vi.stubGlobal('SpeechSynthesisUtterance', vi.fn(() => ({ volume: 1 })))
  })

  describe('getGlobalAudioContext', () => {
    it('创建并复用 AudioContext', async () => {
      const { getGlobalAudioContext } = await freshImport()
      const ctx1 = getGlobalAudioContext()
      const ctx2 = getGlobalAudioContext()
      expect(ctx1).toBe(ctx2)
      expect(AudioContext).toHaveBeenCalledTimes(1)
    })
  })

  describe('getGlobalAudioElement', () => {
    it('创建并复用 Audio 元素', async () => {
      const { getGlobalAudioElement } = await freshImport()
      const el1 = getGlobalAudioElement()
      const el2 = getGlobalAudioElement()
      expect(el1).toBe(el2)
      expect(Audio).toHaveBeenCalledTimes(1)
    })

    it('设置 playsinline 属性（iOS 兼容）', async () => {
      const { getGlobalAudioElement } = await freshImport()
      const el = getGlobalAudioElement()
      expect(el.preload).toBe('auto')
      expect(el.setAttribute).toHaveBeenCalledWith('playsinline', '')
      expect(el.setAttribute).toHaveBeenCalledWith('webkit-playsinline', '')
    })
  })

  describe('unlockAudio', () => {
    it('幂等：多次调用只执行一次', async () => {
      const { unlockAudio, isAudioUnlocked } = await freshImport()
      unlockAudio()
      unlockAudio()
      unlockAudio()
      expect(isAudioUnlocked()).toBe(true)
      // AudioContext 只创建一次
      expect(AudioContext).toHaveBeenCalledTimes(1)
    })

    it('解锁时 resume suspended AudioContext', async () => {
      const { unlockAudio } = await freshImport()
      unlockAudio()
      expect(mockCtx.resume).toHaveBeenCalled()
    })

    it('预热 Audio 元素（静音 play）', async () => {
      const { unlockAudio } = await freshImport()
      unlockAudio()
      expect(mockAudio.play).toHaveBeenCalled()
      expect(mockAudio.volume).toBe(0) // 播放时静音
    })

    it('预热 speechSynthesis', async () => {
      const { unlockAudio } = await freshImport()
      unlockAudio()
      expect(speechSynthesis.speak).toHaveBeenCalled()
      expect(speechSynthesis.cancel).toHaveBeenCalled()
    })

    it('play() 被拒绝时恢复音量', async () => {
      mockAudio.play = vi.fn(() => Promise.reject(new Error('NotAllowedError')))
      const { unlockAudio } = await freshImport()
      unlockAudio()
      await new Promise(r => setTimeout(r, 10))
      expect(mockAudio.volume).toBe(1)
    })

    it('play() 成功后 pause 并重置', async () => {
      const { unlockAudio } = await freshImport()
      unlockAudio()
      await new Promise(r => setTimeout(r, 10))
      expect(mockAudio.pause).toHaveBeenCalled()
      expect(mockAudio.volume).toBe(1)
      expect(mockAudio.currentTime).toBe(0)
    })

    it('AudioContext 异常时不崩溃', async () => {
      mockCtx.createBuffer = vi.fn(() => { throw new Error('boom') })
      const { unlockAudio, isAudioUnlocked } = await freshImport()
      expect(() => unlockAudio()).not.toThrow()
      expect(isAudioUnlocked()).toBe(true)
    })

    it('speechSynthesis 异常时不崩溃', async () => {
      vi.stubGlobal('speechSynthesis', {
        speak: vi.fn(() => { throw new Error('tts boom') }),
        cancel: vi.fn(),
      })
      const { unlockAudio } = await freshImport()
      expect(() => unlockAudio()).not.toThrow()
    })
  })

  describe('isAudioUnlocked', () => {
    it('初始为 false', async () => {
      const { isAudioUnlocked } = await freshImport()
      expect(isAudioUnlocked()).toBe(false)
    })

    it('unlockAudio 后为 true', async () => {
      const { unlockAudio, isAudioUnlocked } = await freshImport()
      unlockAudio()
      expect(isAudioUnlocked()).toBe(true)
    })
  })
})
