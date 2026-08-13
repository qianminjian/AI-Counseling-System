/**
 * useVoiceInputPipeline 黑盒测试（ARCH-006 F-4，doing/66 §3.1）
 *
 * 覆盖状态机全分支：正常录音→分析→发送 / 无录音降级 / 上传失败降级 /
 * 分析失败无转写 / 取消 / 过短 / 30s 超时 / SpeechRecognition 缺失 / 防重入。
 *
 * 测试策略：hook 级黑盒——mock useAudioRecorder（捕获 onComplete 回调）+ 假 SpeechRecognition +
 * mock fetchVoiceAnalyze，不触碰 ChatRoom 组件内部。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useVoiceInputPipeline } from '../hooks/useVoiceInputPipeline'

// ===== mock useAudioRecorder：可控状态 + 捕获 onComplete（模拟 MediaRecorder onstop） =====
const mockRecorder = {
  recording: false,
  analyzing: false,
  supported: true,
  startRecording: vi.fn(),
  stopRecording: vi.fn(),
  cancelRecording: vi.fn(),
  warmUp: vi.fn(),
  releaseStream: vi.fn(),
}
let capturedComplete: ((blob: Blob | null) => Promise<unknown>) | null = null
vi.mock('../hooks/useAudioRecorder', () => ({
  useAudioRecorder: (cb: any) => {
    capturedComplete = cb
    return mockRecorder
  },
}))

// ===== mock api：仅暴露 fetchVoiceAnalyze（pipeline 唯一外部网络依赖） =====
const mockFetchVoiceAnalyze = vi.fn()
vi.mock('../api', () => ({
  fetchVoiceAnalyze: (...args: any[]) => mockFetchVoiceAnalyze(...args),
}))

// ===== 假 SpeechRecognition（jsdom 无此 API，Black-box 注入） =====
class FakeSpeechRecognition {
  lang = ''
  continuous = false
  interimResults = false
  onresult: ((e: any) => void) | null = null
  onerror: ((e: any) => void) | null = null
  start = vi.fn()
  stop = vi.fn()
  static last: FakeSpeechRecognition | null = null
  constructor() {
    FakeSpeechRecognition.last = this
  }
}
function installFakeSpeechRecognition() {
  FakeSpeechRecognition.last = null
  ;(window as any).SpeechRecognition = FakeSpeechRecognition
}
function removeFakeSpeechRecognition() {
  ;(window as any).SpeechRecognition = undefined
}

/** 构造一次 onresult 事件（含 final/interim 结果列表） */
function resultEvent(items: { text: string; isFinal: boolean }[]) {
  return {
    results: items.map((it) => [{ transcript: it.text }].concat([{ transcript: it.text }].slice(1))).map((pair, i) => {
      const item: any = { 0: { transcript: items[i].text }, isFinal: items[i].isFinal, length: 1 }
      return item
    }),
  }
}

const BLOCK_RESULT = { ok: true, json: async () => ({ code: 0, data: { text: '我很开心', emotion: { labelEn: 'happy', label: '开心', confidence: 0.9 } } }) }

describe('useVoiceInputPipeline（ARCH-006 语音编排抽取）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockRecorder.recording = false
    mockRecorder.analyzing = false
    mockRecorder.supported = true
    installFakeSpeechRecognition()
  })
  afterEach(() => {
    removeFakeSpeechRecognition()
    capturedComplete = null
  })

  // ===== 初始态 =====
  it('初始态：IDLE（无录音/分析/发送/错误）', () => {
    const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription: vi.fn() }))
    expect(result.current.isRecording).toBe(false)
    expect(result.current.isAnalyzing).toBe(false)
    expect(result.current.isSending).toBe(false)
    expect(result.current.error).toBeNull()
    expect(result.current.supported).toBe(true)
    expect(result.current.liveTranscript).toBe('')
  })

  // ===== start → RECORDING =====
  it('start()：启动录音 + SpeechRecognition（continuous/interim）+ 清空转写', () => {
    const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription: vi.fn() }))
    act(() => { result.current.start() })
    expect(mockRecorder.startRecording).toHaveBeenCalledTimes(1)
    // FE-003：下一行 expect(rec).toBeTruthy() 已断言非空，非空断言不改变运行时取值
    const rec = FakeSpeechRecognition.last!
    expect(rec).toBeTruthy()
    expect(rec.lang).toBe('zh-CN')
    expect(rec.continuous).toBe(true)
    expect(rec.interimResults).toBe(true)
    expect(rec.start).toHaveBeenCalledTimes(1)
  })

  it('start()：SpeechRecognition 缺失 → 仍启动录音（纯录音降级）', () => {
    removeFakeSpeechRecognition()
    const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription: vi.fn() }))
    act(() => { result.current.start() })
    expect(mockRecorder.startRecording).toHaveBeenCalledTimes(1)
  })

  it('防重入：recording 中再次 start() 不重复启动', () => {
    mockRecorder.recording = true
    const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription: vi.fn() }))
    act(() => { result.current.start() })
    expect(mockRecorder.startRecording).not.toHaveBeenCalled()
    expect(FakeSpeechRecognition.last).toBeNull()
  })

  // ===== SpeechRecognition onresult → liveTranscript + final 去重 =====
  it('onresult：interim+final 拼接实时转写', () => {
    const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription: vi.fn() }))
    act(() => { result.current.start() })
    act(() => {
      FakeSpeechRecognition.last!.onresult?.(resultEvent([
        { text: '我今天', isFinal: false },
        { text: '很开心', isFinal: true },
      ]))
    })
    expect(result.current.liveTranscript).toBe('我今天很开心')
  })

  it('onresult：跳过连续相同的 final 条目（Android 重复 bug 防护）', () => {
    const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription: vi.fn() }))
    act(() => { result.current.start() })
    act(() => {
      FakeSpeechRecognition.last!.onresult?.(resultEvent([
        { text: '开心', isFinal: true },
        { text: '开心', isFinal: true },
        { text: '啊', isFinal: true },
      ]))
    })
    expect(result.current.liveTranscript).toBe('开心啊')
  })

  // ===== stop → 正常发送链 =====
  it('stop()：停止识别实例 + 触发录音停止（→ 分析 → 自动发送）', () => {
    vi.useFakeTimers()
    try {
      const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription: vi.fn() }))
      act(() => {
        result.current.start()
        mockRecorder.recording = true // 模拟录音进行中（startRecording 内部置位）
      })
      vi.setSystemTime(Date.now() + 2000) // 超过过短阈值
      act(() => { result.current.stop() })
      expect(FakeSpeechRecognition.last!.stop).toHaveBeenCalledTimes(1)
      expect(mockRecorder.stopRecording).toHaveBeenCalledTimes(1)
      expect(mockRecorder.cancelRecording).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('过短（<1000ms）：取消录音 + 提示，不进入发送', () => {
    vi.useFakeTimers()
    try {
      const onTranscription = vi.fn()
      const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription }))
      act(() => {
        result.current.start()
        mockRecorder.recording = true // 模拟录音进行中
      })
      vi.setSystemTime(Date.now() + 300) // 仅 300ms
      act(() => { result.current.stop() })
      expect(mockRecorder.cancelRecording).toHaveBeenCalledTimes(1)
      expect(mockRecorder.stopRecording).not.toHaveBeenCalled()
      expect(result.current.error).toContain('说话时间太短')
      expect(onTranscription).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('30s 超时：录音中到时自动 stop（等价松手）', () => {
    vi.useFakeTimers()
    try {
      const { result, rerender } = renderHook(() => useVoiceInputPipeline({ onTranscription: vi.fn() }))
      act(() => { result.current.start() })
      act(() => {
        mockRecorder.recording = true // 模拟录音开始
        rerender() // 强制 rerender 使 30s effect 依赖更新
      })
      act(() => { vi.advanceTimersByTime(30000) })
      expect(mockRecorder.stopRecording).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })

  // ===== cancel =====
  it('cancel()：停止识别 + 取消录音，不触发发送', () => {
    const onTranscription = vi.fn()
    const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription }))
    act(() => {
      result.current.start()
      mockRecorder.recording = true // 模拟录音进行中
    })
    act(() => { result.current.cancel() })
    expect(FakeSpeechRecognition.last!.stop).toHaveBeenCalledTimes(1)
    expect(mockRecorder.cancelRecording).toHaveBeenCalledTimes(1)
    expect(mockRecorder.stopRecording).not.toHaveBeenCalled()
    expect(onTranscription).not.toHaveBeenCalled()
  })

  // ===== 录音完成（onComplete）→ 上传分析 → 自动发送 =====
  it('录音完成：fetchVoiceAnalyze(formData) + onTranscription(text, emotion)', async () => {
    mockFetchVoiceAnalyze.mockResolvedValue(BLOCK_RESULT)
    const onTranscription = vi.fn()
    const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription }))
    const blob = new Blob(['audio'], { type: 'audio/webm' })
    await act(async () => { await capturedComplete!(blob) })
    expect(mockFetchVoiceAnalyze).toHaveBeenCalledTimes(1)
    expect(mockFetchVoiceAnalyze.mock.calls[0][0]).toBeInstanceOf(FormData)
    expect(onTranscription).toHaveBeenCalledTimes(1)
    expect(onTranscription.mock.calls[0][0]).toBe('我很开心')
    expect(onTranscription.mock.calls[0][1]).toEqual({ labelEn: 'happy', label: '开心', confidence: 0.9 })
    expect(result.current.error).toBeNull()
  })

  it('录音完成：上传返回无文字 → 降级浏览器转写（有转写则发送）', async () => {
    mockFetchVoiceAnalyze.mockResolvedValue({ ok: true, json: async () => ({ code: 20001 }) })
    const onTranscription = vi.fn()
    const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription }))
    // 预先注入浏览器转写
    act(() => {
      result.current.start()
      FakeSpeechRecognition.last!.onresult?.(resultEvent([{ text: '我用浏览器识别的', isFinal: true }]))
    })
    const blob = new Blob(['audio'], { type: 'audio/webm' })
    await act(async () => { await capturedComplete!(blob) })
    expect(onTranscription).toHaveBeenCalledTimes(1)
    expect(onTranscription.mock.calls[0][0]).toBe('我用浏览器识别的')
    expect(onTranscription.mock.calls[0][1]).toBeNull()
    expect(result.current.error).toContain('已用浏览器识别')
  })

  it('录音完成：上传失败 → 降级浏览器转写（有转写则发送）', async () => {
    mockFetchVoiceAnalyze.mockRejectedValue(new Error('server down'))
    const onTranscription = vi.fn()
    const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription }))
    act(() => {
      result.current.start()
      FakeSpeechRecognition.last!.onresult?.(resultEvent([{ text: '降级文本', isFinal: true }]))
    })
    const blob = new Blob(['audio'], { type: 'audio/webm' })
    await act(async () => { await capturedComplete!(blob) })
    expect(onTranscription).toHaveBeenCalledTimes(1)
    expect(onTranscription.mock.calls[0][0]).toBe('降级文本')
    expect(result.current.error).toContain('已用浏览器识别')
  })

  it('录音完成：上传失败且无转写 → 错误提示，不发送', async () => {
    mockFetchVoiceAnalyze.mockRejectedValue(new Error('server down'))
    const onTranscription = vi.fn()
    const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription }))
    const blob = new Blob(['audio'], { type: 'audio/webm' })
    await act(async () => { await capturedComplete!(blob) })
    expect(onTranscription).not.toHaveBeenCalled()
    expect(result.current.error).toContain('语音识别暂不可用')
  })

  // ===== 无录音（onComplete(null)）降级路径 =====
  it('无录音 + 有浏览器转写 → 直接发送转写文本', async () => {
    const onTranscription = vi.fn()
    const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription }))
    act(() => {
      result.current.start()
      FakeSpeechRecognition.last!.onresult?.(resultEvent([{ text: '浏览器结果', isFinal: true }]))
    })
    await act(async () => { await capturedComplete!(null) })
    expect(onTranscription).toHaveBeenCalledTimes(1)
    expect(onTranscription.mock.calls[0][0]).toBe('浏览器结果')
    expect(onTranscription.mock.calls[0][1]).toBeNull()
    expect(result.current.error).toContain('已用浏览器识别')
  })

  it('无录音 + 无转写 → 没有听清提示', async () => {
    const onTranscription = vi.fn()
    const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription }))
    await act(async () => { await capturedComplete!(null) })
    expect(onTranscription).not.toHaveBeenCalled()
    expect(result.current.error).toContain('没有听清')
  })

  // ===== 状态透传 =====
  it('analyzing 状态透传（useAudioRecorder 单一事实源）', () => {
    mockRecorder.analyzing = true
    const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription: vi.fn() }))
    expect(result.current.isAnalyzing).toBe(true)
  })

  it('supported 状态透传', () => {
    mockRecorder.supported = false
    const { result } = renderHook(() => useVoiceInputPipeline({ onTranscription: vi.fn() }))
    expect(result.current.supported).toBe(false)
  })
})
