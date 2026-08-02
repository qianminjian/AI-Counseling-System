import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

/**
 * config/remote.ts 单元测试（CFG-002 前端运行时配置注入）
 *
 * 覆盖：
 * - fetchRemoteConfig 成功时缓存配置
 * - fetchRemoteConfig 失败时返回 null（不阻塞启动）
 * - getRemoteConfig 返回缓存值
 * - getConfigValue 带 fallback 的取值逻辑
 * - 嵌套路径取值（voiceprint.verifyThreshold）
 * - 超时处理
 */

// 动态 import 以便每个测试重置模块状态
let remote: typeof import('../config/remote')

beforeEach(async () => {
  vi.resetModules()
  vi.stubGlobal('fetch', vi.fn())
  remote = await import('../config/remote')
})

afterEach(() => {
  vi.restoreAllMocks()
})

const MOCK_CONFIG = {
  code: 0,
  message: 'success',
  data: {
    voiceprint: {
      verifyThreshold: 0.70,
      maxTemplates: 8,
      enrollSegments: 3,
      verifySegments: 2,
    },
    wakeWord: {
      modelId: 'onnx-community/whisper-tiny',
      windowSeconds: 2.0,
      silenceRmsThreshold: 0.03,
    },
    tts: {
      defaultPersona: 'xiaoxing',
      personas: ['xiaoxing', 'bobo', 'yueliang', 'xiaotaiyang', 'dashu', 'doudou', 'qiqiu'],
    },
    guideScripts: {
      verify: [
        { prompt: '嗨！我是波波，跟我打个招呼吧！', hint: '对波波说"你好"就行', duration: 4 },
        { prompt: '真棒！再跟我说一句：今天心情真好呀！', hint: '跟我说：今天心情真好呀！', duration: 4 },
      ],
      enroll: [
        { prompt: '嗨！我是波波，很高兴认识你！跟我打个招呼吧！', hint: '对波波说"你好"就行', duration: 4 },
      ],
    },
  },
  timestamp: '2026-07-28T00:00:00Z',
}

describe('config/remote', () => {
  describe('initRemoteConfig', () => {
    it('成功时缓存配置并返回 true', async () => {
      ;(fetch as any).mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(MOCK_CONFIG),
      })

      const result = await remote.initRemoteConfig()

      expect(result).toBe(true)
      expect(fetch).toHaveBeenCalledWith('/api/v1/system/config', expect.objectContaining({
        signal: expect.any(AbortSignal),
      }))
    })

    it('API 返回非 ok 时返回 false（降级到本地默认值）', async () => {
      ;(fetch as any).mockResolvedValue({ ok: false, status: 500 })

      const result = await remote.initRemoteConfig()

      expect(result).toBe(false)
    })

    it('网络异常时返回 false（不抛错，不阻塞启动）', async () => {
      ;(fetch as any).mockRejectedValue(new TypeError('Failed to fetch'))

      const result = await remote.initRemoteConfig()

      expect(result).toBe(false)
    })

    it('API 返回 code!=0 时返回 false', async () => {
      ;(fetch as any).mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ code: 1, message: 'error', data: null }),
      })

      const result = await remote.initRemoteConfig()

      expect(result).toBe(false)
    })
  })

  describe('getRemoteConfig', () => {
    it('未初始化时返回 null', () => {
      expect(remote.getRemoteConfig()).toBeNull()
    })

    it('初始化成功后返回完整配置对象', async () => {
      ;(fetch as any).mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(MOCK_CONFIG),
      })
      await remote.initRemoteConfig()

      const config = remote.getRemoteConfig()
      expect(config).not.toBeNull()
      expect(config!.voiceprint.verifyThreshold).toBe(0.70)
      expect(config!.tts.personas).toHaveLength(7)
    })
  })

  describe('getConfigValue（带 fallback 的取值）', () => {
    it('远程配置存在时返回远程值', async () => {
      ;(fetch as any).mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(MOCK_CONFIG),
      })
      await remote.initRemoteConfig()

      expect(remote.getConfigValue('voiceprint.verifyThreshold', 0.55)).toBe(0.70)
      expect(remote.getConfigValue('voiceprint.maxTemplates', 5)).toBe(8)
      expect(remote.getConfigValue('tts.defaultPersona', 'bobo')).toBe('xiaoxing')
    })

    it('远程配置不存在时返回 fallback 值', () => {
      // 未初始化，应返回 fallback
      expect(remote.getConfigValue('voiceprint.verifyThreshold', 0.55)).toBe(0.55)
      expect(remote.getConfigValue('tts.defaultPersona', 'bobo')).toBe('bobo')
    })

    it('路径不存在时返回 fallback 值', async () => {
      ;(fetch as any).mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(MOCK_CONFIG),
      })
      await remote.initRemoteConfig()

      expect(remote.getConfigValue('nonexistent.path', 'default')).toBe('default')
      expect(remote.getConfigValue('voiceprint.nonexistent', 42)).toBe(42)
    })

    it('支持数组类型取值', async () => {
      ;(fetch as any).mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(MOCK_CONFIG),
      })
      await remote.initRemoteConfig()

      const personas = remote.getConfigValue('tts.personas', [])
      expect(personas).toEqual(['xiaoxing', 'bobo', 'yueliang', 'xiaotaiyang', 'dashu', 'doudou', 'qiqiu'])
    })

    it('支持嵌套对象取值（guideScripts.verify）', async () => {
      ;(fetch as any).mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(MOCK_CONFIG),
      })
      await remote.initRemoteConfig()

      const scripts = remote.getConfigValue('guideScripts.verify', [])
      expect(scripts).toHaveLength(2)
      expect(scripts[0].prompt).toContain('波波')
    })
  })

  describe('超时处理', () => {
    it('超过 3 秒自动中止（AbortController）', async () => {
      vi.useFakeTimers()
      let abortSignal: AbortSignal | undefined

      ;(fetch as any).mockImplementation((_url: string, opts: any) => {
        abortSignal = opts.signal
        return new Promise((_, reject) => {
          opts.signal.addEventListener('abort', () => {
            reject(new DOMException('Aborted', 'AbortError'))
          })
        })
      })

      const promise = remote.initRemoteConfig()
      vi.advanceTimersByTime(3100)

      const result = await promise
      expect(result).toBe(false)
      expect(abortSignal?.aborted).toBe(true)

      vi.useRealTimers()
    })
  })
})
