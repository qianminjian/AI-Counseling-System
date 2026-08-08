import { describe, it, expect, vi, afterEach } from 'vitest'
import { createSpeechRecognition } from '../utils/speechRecognition'

/**
 * SpeechRecognition 共享装配层单测（FA-10）
 * - 装配：探测 / lang / continuous / interimResults / start
 * - dedupe 策略：Android 重复 final 去重（此前只在 useVoiceInputPipeline 修过）
 * - concat 策略：全量拼接（useVoiceCallMode 防抖判断依赖）
 * - stop silent：主动停止不触发 onEnd
 */

class FakeRecorder {
  lang = ''
  continuous = false
  interimResults = false
  onresult: ((e: any) => void) | null = null
  onend: (() => void) | null = null
  onerror: ((e: any) => void) | null = null
  start = vi.fn()
  stop = vi.fn()
  static last: FakeRecorder | null = null
  constructor() {
    FakeRecorder.last = this
  }
}

function installFake() {
  FakeRecorder.last = null
  ;(window as any).SpeechRecognition = FakeRecorder
}

function resultEvent(results: Array<{ text: string; isFinal: boolean }>) {
  return {
    results: results.map((r) => {
      const item: any = { 0: { transcript: r.text }, isFinal: r.isFinal, length: 1 }
      return item
    }),
  }
}

afterEach(() => {
  delete (window as any).SpeechRecognition
  delete (window as any).webkitSpeechRecognition
  vi.restoreAllMocks()
})

describe('createSpeechRecognition 装配（FA-10）', () => {
  it('浏览器不支持（无 SpeechRecognition/webkitSpeechRecognition）→ 返回 null', () => {
    expect(createSpeechRecognition()).toBeNull()
    ;(window as any).webkitSpeechRecognition = FakeRecorder
    expect(createSpeechRecognition()).not.toBeNull()
  })

  it('标准配置：zh-CN + continuous + interimResults + start', () => {
    installFake()
    const handle = createSpeechRecognition()
    expect(handle).not.toBeNull()
    const rec = FakeRecorder.last!
    expect(rec.lang).toBe('zh-CN')
    expect(rec.continuous).toBe(true)
    expect(rec.interimResults).toBe(true)
    expect(rec.start).toHaveBeenCalledTimes(1)
  })

  it('自定义 lang', () => {
    installFake()
    createSpeechRecognition({ lang: 'en-US' })
    expect(FakeRecorder.last!.lang).toBe('en-US')
  })

  it('构造抛错 → 返回 null 不崩溃', () => {
    ;(window as any).SpeechRecognition = class { constructor() { throw new Error('boom') } }
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    expect(createSpeechRecognition()).toBeNull()
    warnSpy.mockRestore()
  })
})

describe('dedupe 聚合策略（Android 重复 final bug 单点修复）', () => {
  it('连续相同 final 只保留一次；interim 按出现顺序参与展示', () => {
    installFake()
    const onText = vi.fn()
    createSpeechRecognition({ onText })
    const rec = FakeRecorder.last!
    // 第一轮：interim「你好」+ final「你好」（Android 重复条目）
    rec.onresult!(resultEvent([
      { text: '你好', isFinal: false },
      { text: '你好', isFinal: true },
      { text: '你好', isFinal: true },
    ]))
    // 发送文本 = 去重后 final；展示 = interim + final 按顺序混合
    expect(onText).toHaveBeenLastCalledWith('你好', '你好你好')
    // 第二轮：新增 final「世界」
    rec.onresult!(resultEvent([
      { text: '你好', isFinal: true },
      { text: '你好', isFinal: true },
      { text: '世界', isFinal: true },
    ]))
    // 发送文本 = 去重后全量 final「你好世界」；展示与发送一致（无 interim）
    expect(onText).toHaveBeenLastCalledWith('你好世界', '你好世界')
  })

  it('仅 interim 时发送文本回退 interim', () => {
    installFake()
    const onText = vi.fn()
    createSpeechRecognition({ onText })
    FakeRecorder.last!.onresult!(resultEvent([{ text: '正在', isFinal: false }]))
    expect(onText).toHaveBeenLastCalledWith('正在', '正在')
  })

  it('interim 在前 final 在后：display 按出现顺序混合（不重排）', () => {
    installFake()
    const onText = vi.fn()
    createSpeechRecognition({ onText })
    FakeRecorder.last!.onresult!(resultEvent([
      { text: '我今天', isFinal: false },
      { text: '很开心', isFinal: true },
    ]))
    expect(onText).toHaveBeenLastCalledWith('很开心', '我今天很开心')
  })
})

describe('concat 聚合策略（唤醒通话链防抖依赖完整文本）', () => {
  it('全量拼接（含 interim + final，不去重不区分）', () => {
    installFake()
    const onText = vi.fn()
    createSpeechRecognition({ aggregate: 'concat', onText })
    FakeRecorder.last!.onresult!(resultEvent([
      { text: '我', isFinal: false },
      { text: '想', isFinal: false },
      { text: '聊天', isFinal: true },
    ]))
    expect(onText).toHaveBeenLastCalledWith('我想聊天', '我想聊天')
  })

  it('空白文本不回调（防抖不被打断）', () => {
    installFake()
    const onText = vi.fn()
    createSpeechRecognition({ aggregate: 'concat', onText })
    FakeRecorder.last!.onresult!(resultEvent([{ text: '   ', isFinal: false }]))
    expect(onText).not.toHaveBeenCalled()
  })
})

describe('stop / onend / onerror', () => {
  it('stop() 触发底层 stop；silent=true 时 onend 置空（主动停止不触发重启）', () => {
    installFake()
    const onEnd = vi.fn()
    const handle = createSpeechRecognition({ onEnd })!
    const rec = FakeRecorder.last!
    handle.stop()
    expect(rec.stop).toHaveBeenCalledTimes(1)
    expect(rec.onend).not.toBeNull()
    handle.stop(true)
    expect(rec.onend).toBeNull()
  })

  it('onend/onerror 转发（silent stop 后 onend 为 null 不转发）', () => {
    installFake()
    const onEnd = vi.fn()
    const onError = vi.fn()
    const handle = createSpeechRecognition({ onEnd, onError })!
    const rec = FakeRecorder.last!
    handle.stop(true)
    rec.onend?.() // 不触发（onend 已置空）
    expect(onEnd).not.toHaveBeenCalled()
    rec.onerror?.({ error: 'no-speech' })
    expect(onError).toHaveBeenCalledWith('no-speech')
  })
})
