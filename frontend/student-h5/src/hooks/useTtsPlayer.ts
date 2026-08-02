/**
 * TTS 语音播放 Hook
 * - 逐句合成 + 队列播放
 * - 单一持久 Audio 元素（规避浏览器自动播放拦截）
 * - 后端 TTS 不可用时降级为浏览器 speechSynthesis
 * - 无可用语音引擎时友好提示（修复安卓 Pad "找不到google语音引擎" 问题）
 * - 支持暂停/重播/停止
 */
import { useState, useRef, useCallback, useEffect } from 'react'
import { getGlobalAudioElement, getGlobalAudioContext, unlockAudio } from '../utils/audioUnlock'
import { authFetch } from '../api'

/** 去除 emoji 和特殊符号（TTS 不需要朗读） */
function stripEmoji(text) {
  return text
    // Unicode 属性转义：覆盖所有图形化 emoji（含新版）
    .replace(/\p{Extended_Pictographic}/gu, '')
    .replace(/\p{Emoji_Presentation}/gu, '')
    .replace(/[\u{FE00}-\u{FE0F}]/gu, '')    // 变体选择器
    .replace(/[\u{200D}]/gu, '')             // 零宽连接
    .replace(/[\u{20E3}]/gu, '')             // 组合用包围键帽
    .replace(/[\u{E0020}-\u{E007F}]/gu, '')  // 标签
    .replace(/[\u{1F3FB}-\u{1F3FF}]/gu, '')  // 肤色修饰
    .replace(/[#*0-9]\uFE0F?\u20E3/g, '')    // 键帽序列
    .replace(/\s{2,}/g, ' ')                 // 多余空格
    .trim()
}

/** 将文本按标点切分为句子（PERF-004：降低阈值，首句更快出声） */
function splitSentences(text) {
  if (!text) return []
  const cleaned = stripEmoji(text)
  if (!cleaned) return []
  // 按句号、问号、感叹号、换行切分
  const parts = cleaned.split(/(?<=[。！？\n])/).filter(s => s.trim())
  // 超长句二次切分（>15字在逗号/顿号/分号处断开，加速首句出声）
  const result = []
  for (const part of parts) {
    if (part.length > 15) {
      const subParts = part.split(/(?<=[，、；])/).filter(s => s.trim())
      result.push(...subParts)
    } else {
      result.push(part)
    }
  }
  return result
}

/** 合并过短的句子，避免“短句瞬间播完、下一句还没合成好”造成的句间间隔 */
function mergeShortSentences(sentences, minLen = 8) {
  const merged = []
  let buffer = ''
  for (const s of sentences) {
    buffer += s
    if (buffer.length >= minLen) {
      merged.push(buffer)
      buffer = ''
    }
  }
  if (buffer) {
    if (merged.length > 0) merged[merged.length - 1] += buffer
    else merged.push(buffer)
  }
  return merged
}


/** 浏览器降级时的人设音色参数（speechSynthesis 无法选音色，用 pitch/rate 区分人设） */
const PERSONA_VOICE_PROFILES = {
  xiaoxing: { pitch: 1.1, rateScale: 1.0 },   // 小星：温暖大姐姐
  bobo: { pitch: 1.05, rateScale: 0.95 },     // 波波老师：温柔女老师
  qiqiu: { pitch: 1.4, rateScale: 1.1 },      // 气球：活泼俘皮，音调高、语速快
  yueliang: { pitch: 1.0, rateScale: 0.9 },   // 月亮：温柔轻语，语速慢
  xiaotaiyang: { pitch: 0.7, rateScale: 1.0 },// 小太阳：阳光大哥哥，低音调模拟男声
  dashu: { pitch: 0.6, rateScale: 0.95 },     // 大树：暖心大叔，低沉稳重
  doudou: { pitch: 1.5, rateScale: 1.1 },     // 豆豆：顽皮男孩，音调高语速快
}

/** 用浏览器 speechSynthesis 朗读（后端 TTS 不可用时的降级，按人设调整音高语速） */
function browserSpeak(text: string, { rate = 1.0, persona = 'xiaoxing', onEnd }: { rate?: number; persona?: string; onEnd?: () => void } = {}) {
  if (!('speechSynthesis' in window)) { onEnd?.(); return false }
  try {
    window.speechSynthesis.cancel()
    const utter = new SpeechSynthesisUtterance(text)
    const profile = PERSONA_VOICE_PROFILES[persona] || PERSONA_VOICE_PROFILES.xiaoxing
    utter.lang = 'zh-CN'
    utter.rate = Math.max(0.5, Math.min(2, rate * profile.rateScale))
    utter.pitch = profile.pitch
    // 优先选中文语音
    const voices = window.speechSynthesis.getVoices()
    const zhVoice = voices.find(v => v.lang.startsWith('zh'))
    if (zhVoice) utter.voice = zhVoice
    utter.onend = () => onEnd?.()
    utter.onerror = () => onEnd?.()
    window.speechSynthesis.speak(utter)
    return true
  } catch {
    onEnd?.()
    return false
  }
}

export function useTtsPlayer({ persona = 'xiaoxing', emotion = 'neutral', speed = 1.0, dialect = null }: { persona?: string; emotion?: string; speed?: number; dialect?: string | null } = {}) {
  const [playing, setPlaying] = useState(false)
  const [currentSentenceIdx, setCurrentSentenceIdx] = useState(-1)
  // 当前正在播放的句子数组（供波波话语气泡逐句展示，见 design/27 §4.4）
  const [sentences, setSentences] = useState([])
  const [muted, setMuted] = useState(false)
  // 语音引擎状态：'backend' | 'browser' | 'none'
  const [engine, setEngine] = useState('backend')
  // 单一持久 Audio 元素（在用户手势中创建，规避自动播放拦截）
  const audioRef = useRef(null)
  const audioCtxRef = useRef(null)
  const abortRef = useRef(false)
  const backendFailCount = useRef(0)
  const lastFailTimeRef = useRef(0) // 上次失败时间戳（用于时间恢复）

  // persona/dialect 变化时重置失败计数（故障可能是特定组合导致的）
  useEffect(() => {
    backendFailCount.current = 0
    setEngine('backend')
  }, [persona, dialect])

  // 初始化时检测浏览器 TTS 可用性（voiceschanged 事件确保异步加载完成）
  useEffect(() => {
    if ('speechSynthesis' in window) {
      const handler = () => window.speechSynthesis.getVoices()
      window.speechSynthesis.addEventListener?.('voiceschanged', handler)
      return () => window.speechSynthesis.removeEventListener?.('voiceschanged', handler)
    }
  }, [])

  /** 获取/创建持久 Audio 元素（复用全局已解锁实例，确保自动播放权限） */
  const getAudio = useCallback(() => {
    if (!audioRef.current) {
      audioRef.current = getGlobalAudioElement()
    }
    return audioRef.current
  }, [])

  /** 在用户手势中调用，解锁浏览器音频自动播放限制（增强多浏览器兼容） */
  const unlock = useCallback(() => {
    // 委托全局解锁模块（幂等，EmotionSelect 点击时已调用过则跳过）
    unlockAudio()
    // 确保 ref 指向全局实例
    if (!audioRef.current) {
      audioRef.current = getGlobalAudioElement()
    }
    if (!audioCtxRef.current) {
      audioCtxRef.current = getGlobalAudioContext()
    }
  }, [])

  /** 合成单句音频（后端 TTS，连续失败 2 次后降级为浏览器 TTS，30s 后自动恢复重试） */
  const synthesizeSentence = useCallback(async (text) => {
    // 后端已连续失败多次：检查是否已过 30s 恢复窗口
    if (backendFailCount.current >= 2) {
      const elapsed = Date.now() - lastFailTimeRef.current
      if (elapsed < 30000) {
        return null // 30s 内不重试，用浏览器降级
      }
      // 30s 后允许一次重试
      backendFailCount.current = 0
    }
    try {
      const res = await authFetch('/api/v1/tts/synthesize', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          text, persona, emotion, speed,
          ...(dialect ? { dialect } : {}),
        }),
      })
      if (!res.ok || res.status === 204) {
        backendFailCount.current++
        lastFailTimeRef.current = Date.now()
        return null
      }
      backendFailCount.current = 0 // 成功则重置
      setEngine('backend')
      return await res.blob()
    } catch {
      backendFailCount.current++
      lastFailTimeRef.current = Date.now()
      return null
    }
  }, [persona, emotion, speed, dialect])

  /** 用持久 Audio 元素播放一个 blob */
  const playBlob = useCallback((blob) => {
    return new Promise((resolve) => {
      const audio = getAudio()
      const url = URL.createObjectURL(blob)

      const cleanup = () => {
        URL.revokeObjectURL(url)
        audio.onended = null
        audio.onerror = null
      }

      audio.onended = () => { cleanup(); resolve(undefined) }
      audio.onerror = () => {
        console.warn('[TTS] 音频解码失败（MIME 不匹配？）', blob.type, blob.size)
        cleanup(); resolve(undefined)
      }
      audio.src = url
      audio.play().catch((err) => {
        console.warn('[TTS] play() 被拒绝:', err.name, err.message)
        cleanup(); resolve(undefined)
      })
    })
  }, [getAudio])

  /** 播放完整 AI 回复（短句合并 + 全句并行合成，消除句间停顿） */
  const speak = useCallback(async (text) => {
    if (muted) return

    stop()

    const sentences = mergeShortSentences(splitSentences(text))
    if (sentences.length === 0) return

    abortRef.current = false
    setSentences(sentences)
    setPlaying(true)

    // 所有句子同时并行合成：播放第 i 句时，第 i+1..n 句早已在后台合成
    const audioPromises = sentences.map(s => synthesizeSentence(s))

    let usedBrowserFallback = false
    for (let i = 0; i < audioPromises.length; i++) {
      if (abortRef.current) break
      setCurrentSentenceIdx(i)
      const audioBlob = await audioPromises[i]
      if (abortRef.current) break
      if (audioBlob) {
        await playBlob(audioBlob)
      } else {
        // 后端 TTS 不可用 → 浏览器 speechSynthesis 降级
        usedBrowserFallback = true
        setEngine('browser')
        await new Promise<void>((resolve) => {
          const ok = browserSpeak(sentences[i], { rate: speed, persona, onEnd: resolve })
          if (!ok) {
            // 浏览器 TTS 也不可用（安卓无 Google 语音引擎）
            setEngine('none')
            resolve()
          }
        })
      }
    }

    // 播放完毕
    setPlaying(false)
    setCurrentSentenceIdx(-1)
    setSentences([])
    // 如果整段都用了浏览器降级且引擎不可用，恢复 backend 标记（下次重试）
    if (usedBrowserFallback && backendFailCount.current < 3) {
      setEngine('backend')
    }
  }, [muted, synthesizeSentence, playBlob, speed])

  /** 播放单条消息（点击气泡重播）—— 与自动播放相同的分句 + 并行合成逻辑，避免长文本单次合成音质下降 */
  const speakSentence = useCallback(async (text) => {
    if (muted) return
    stop()

    const sentences = mergeShortSentences(splitSentences(text))
    if (sentences.length === 0) return

    abortRef.current = false
    setSentences(sentences)
    setPlaying(true)

    // 并行合成所有句子，播放第 i 句时后续句已在后台合成
    const audioPromises = sentences.map(s => synthesizeSentence(s))

    for (let i = 0; i < audioPromises.length; i++) {
      if (abortRef.current) break
      setCurrentSentenceIdx(i)
      const audioBlob = await audioPromises[i]
      if (abortRef.current) break
      if (audioBlob) {
        await playBlob(audioBlob)
      } else {
        // 后端 TTS 不可用 → 浏览器 speechSynthesis 降级
        setEngine('browser')
        await new Promise<void>((resolve) => {
          const ok = browserSpeak(sentences[i], { rate: speed, persona, onEnd: resolve })
          if (!ok) {
            setEngine('none')
            resolve()
          }
        })
      }
    }

    setPlaying(false)
    setCurrentSentenceIdx(-1)
    setSentences([])
  }, [muted, synthesizeSentence, playBlob, speed])

  /** 停止播放 */
  const stop = useCallback(() => {
    abortRef.current = true
    if (audioRef.current) {
      audioRef.current.pause()
    }
    // 停止浏览器 TTS
    if ('speechSynthesis' in window) {
      try { window.speechSynthesis.cancel() } catch { /* ignore */ }
    }
    setPlaying(false)
    setCurrentSentenceIdx(-1)
    setSentences([])
    // 重置流式 TTS 状态
    streamBufferRef.current = ''
    streamQueueRef.current = []
    streamIdxRef.current = 0
  }, [])

  /* ===== 流式 TTS：AI 回复流式到达时，首句完成即开始合成播放，消除等待全文的滞后 ===== */
  const streamBufferRef = useRef('')       // 未切句的 token 缓冲
  const streamQueueRef = useRef<string[]>([])  // 已切分待播放的句子队列
  const streamIdxRef = useRef(0)           // 当前播放到第几句
  const streamPlayChainRef = useRef<Promise<void>>(Promise.resolve()) // 串行播放链

  /** 开始流式 TTS 会话（在流式回复开始时调用） */
  const startStreaming = useCallback(() => {
    if (muted) return
    stop()
    abortRef.current = false
    streamBufferRef.current = ''
    streamQueueRef.current = []
    streamIdxRef.current = 0
    streamPlayChainRef.current = Promise.resolve()
    setPlaying(true)
    setSentences([])
    setCurrentSentenceIdx(-1)
  }, [muted, stop])

  /** 流式嗂入 token：累积到句末即切分并加入播放队列（后台合成 + 顺序播放） */
  const feedToken = useCallback((token: string) => {
    if (muted || abortRef.current) return
    streamBufferRef.current += token
    // 检测句末标点（。！？\n）或较长缓冲中的逗号（PERF-004：加速首句出声）
    const buf = streamBufferRef.current
    const lastEnd = Math.max(buf.lastIndexOf('。'), buf.lastIndexOf('！'), buf.lastIndexOf('？'), buf.lastIndexOf('\n'))
    // 逗号切分：缓冲超过 10 字时在最后一个逗号处切（避免短句碎片）
    const lastComma = buf.lastIndexOf('，')
    const cutPos = lastEnd >= 0 ? lastEnd : (lastComma >= 10 ? lastComma : -1)
    if (cutPos < 0) return // 尚未累积完一句
    // 切出完整句子
    const completePart = buf.slice(0, cutPos + 1)
    streamBufferRef.current = buf.slice(cutPos + 1)
    const newSentences = mergeShortSentences(splitSentences(completePart))
    if (newSentences.length === 0) return
    // 加入队列 + 更新 UI
    streamQueueRef.current.push(...newSentences)
    setSentences([...streamQueueRef.current])
    // 为每个新句子启动合成+播放（串行链保证顺序）
    for (const s of newSentences) {
      const idx = streamIdxRef.current++
      streamPlayChainRef.current = streamPlayChainRef.current.then(async () => {
        if (abortRef.current) return
        setCurrentSentenceIdx(idx)
        const blob = await synthesizeSentence(s)
        if (abortRef.current) return
        if (blob) {
          await playBlob(blob)
        } else {
          setEngine('browser')
          await new Promise<void>((resolve) => {
            const ok = browserSpeak(s, { rate: speed, persona, onEnd: resolve })
            if (!ok) { setEngine('none'); resolve() }
          })
        }
      })
    }
  }, [muted, synthesizeSentence, playBlob, speed, persona])

  /** 结束流式 TTS：冲刷剩余缓冲，等待播放完毕 */
  const endStreaming = useCallback(async () => {
    if (muted) return
    // 冲刷剩余文本（无句末标点的尾巴）
    const remaining = streamBufferRef.current.trim()
    streamBufferRef.current = ''
    if (remaining && !abortRef.current) {
      const tailSentences = mergeShortSentences(splitSentences(remaining))
      if (tailSentences.length > 0) {
        streamQueueRef.current.push(...tailSentences)
        setSentences([...streamQueueRef.current])
        for (const s of tailSentences) {
          const idx = streamIdxRef.current++
          streamPlayChainRef.current = streamPlayChainRef.current.then(async () => {
            if (abortRef.current) return
            setCurrentSentenceIdx(idx)
            const blob = await synthesizeSentence(s)
            if (abortRef.current) return
            if (blob) {
              await playBlob(blob)
            } else {
              setEngine('browser')
              await new Promise<void>((resolve) => {
                const ok = browserSpeak(s, { rate: speed, persona, onEnd: resolve })
                if (!ok) { setEngine('none'); resolve() }
              })
            }
          })
        }
      }
    }
    // 等待播放链完成
    await streamPlayChainRef.current.catch(() => {})
    if (!abortRef.current) {
      setPlaying(false)
      setCurrentSentenceIdx(-1)
      setSentences([])
    }
  }, [muted, synthesizeSentence, playBlob, speed, persona])

  /** 切换静音 */
  const toggleMute = useCallback(() => {
    setMuted(prev => {
      if (!prev) stop() // 静音时停止播放
      return !prev
    })
  }, [stop])

  // 当前正在朗读的那一句（波波话语气泡用，逐句滚动）
  const currentSentenceText =
    currentSentenceIdx >= 0 && currentSentenceIdx < sentences.length
      ? sentences[currentSentenceIdx]
      : ''

  return {
    playing,
    muted,
    engine, // 'backend' | 'browser' | 'none'
    currentSentenceIdx,
    currentSentenceText,
    speak,
    speakSentence,
    startStreaming,
    feedToken,
    endStreaming,
    stop,
    toggleMute,
    setMuted,
    unlock,
  }
}
