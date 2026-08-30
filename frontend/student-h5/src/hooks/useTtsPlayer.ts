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
import { browserSpeak, stopBrowserSpeak } from '../utils/browserSpeak'
import { fetchTtsSynthesize } from '../api'
import { readMutedPreference, writeMutedPreference } from '../utils/storage'

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
  const result: string[] = []
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
  const merged: string[] = []
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


// F5（doing/78 §12）：browserSpeak / 人设 profile 已收敛至 utils/browserSpeak（三处实现合一），此处仅消费

/**
 * P1-2（audit-report-07 P1-2）：预合成并发窗口（路数）。
 * 播放第 i 句时保证 i+1..i+CONCURRENCY_WINDOW-1 已发起合成，在途峰值 = 当前句 + 3 预取 = 4 路。
 * 长回复（10-20 句）不再瞬间并发 N 个后端 TTS 请求（成本/峰值削峰，计费/限流可在此单点调整）。
 */
const CONCURRENCY_WINDOW = 4
/** 预取领先量：播放第 i 句时预先发起 i+1..i+PRECOMPUTE_AHEAD 的合成 */
const PRECOMPUTE_AHEAD = CONCURRENCY_WINDOW - 1

export function useTtsPlayer({ persona = 'xiaoxing', emotion = 'neutral', speed = 1.0, dialect = null }: { persona?: string; emotion?: string; speed?: number; dialect?: string | null } = {}) {
  const [playing, setPlaying] = useState(false)
  const [currentSentenceIdx, setCurrentSentenceIdx] = useState(-1)
  // 当前正在播放的句子数组（供波波话语气泡逐句展示，见 design/27 §4.4）
  const [sentences, setSentences] = useState<string[]>([])
  // FA-05：静音偏好持久化（与 EmotionSelect 设置面板共享同一 localStorage 状态，跨页面生效）
  const [muted, setMutedState] = useState(readMutedPreference)
  // 语音引擎状态：'backend' | 'browser' | 'none'
  const [engine, setEngine] = useState('backend')
  // 单一持久 Audio 元素（在用户手势中创建，规避自动播放拦截）
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const audioCtxRef = useRef<AudioContext | null>(null)
  const abortRef = useRef(false)
  const backendFailCount = useRef(0)
  const lastFailTimeRef = useRef(0) // 上次失败时间戳（用于时间恢复）
  /**
   * P1-1（audit-report-07 P1-1）：单一播放队列状态机（流式/非流式共用编排，
   * 合并原 streamQueueRef/streamIdxRef/streamPlayChainRef 与 sentences 队列状态）。
   * - items：已入队待播句子（流式逐批追加 / 非流式一次入队）
   * - blobs：blobs[i] = Promise<Blob|null>（已发起合成；未发起为 undefined）
   * - nextIdx：下一个待播放索引　chain/chainActive：串行播放链及其运行标志
   */
  /**
   * 播放队列状态机（P1-2 并发窗口）：items=待播句子，blobs=句级合成 Promise（未发起=undefined），
   * chain=串行播放链（在途），chainActive=链运行标志。
   * 显式类型标注：严格模式（strictNullChecks）下 useRef 字面量会推断 items: never[]/chain: null。
   */
  interface TtsQueueState {
    items: Array<{ text: string }>
    blobs: Array<Promise<Blob | null> | undefined>
    nextIdx: number
    chain: Promise<boolean> | null
    chainActive: boolean
    usedBrowserFallback: boolean
  }
  const playQueueRef = useRef<TtsQueueState>({
    items: [],
    blobs: [],
    nextIdx: 0,
    chain: null,
    chainActive: false,
    usedBrowserFallback: false,
  })
  // 未切句的 token 缓冲（仅流式入口使用：feedToken 累积）
  const streamBufferRef = useRef('')

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
      // F-2 端点收敛：具名 authFetch 接缝（ARCH-005），payload 透传
      const res = await fetchTtsSynthesize({
        text, persona, emotion, speed,
        ...(dialect ? { dialect } : {}),
      })
      // P2-2（audit-report-07 P2-2）：204 是后端合法空结果（TtsController.synthesize：
      // S0 风险静默 noContent() / 合成引擎空输出 noContent()），视为"无音频"而非失败——
      // 不计入 backendFailCount，避免污染 30s 降级恢复窗口统计
      if (res.status === 204) {
        return null
      }
      if (!res.ok) {
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
      // FE-003：getAudio 内部确保创建后返回，逻辑上非空（断言不改变运行行为）
      const audio = getAudio()!
      const url = URL.createObjectURL(blob)

      const cleanup = () => {
        URL.revokeObjectURL(url)
        audio.onended = null
        audio.onerror = null
      }

      audio.onended = () => { cleanup(); resolve(undefined) }
      audio.onerror = () => {
        console.warn('[TTS] 音频解码失败（MIME 不匹配？）', blob.type, blob.size)
        // OBS-TTS-01：无声失败必须用户可感知——音频加载/解码失败视为播放引擎不可用，
        // 置 none 触发 ChatRoom 顶部提示（下一次合成成功自然恢复 'backend'）
        setEngine('none')
        cleanup(); resolve(undefined)
      }
      audio.src = url
      audio.play().catch((err) => {
        console.warn('[TTS] play() 被拒绝:', err.name, err.message)
        cleanup(); resolve(undefined)
      })
    })
  }, [getAudio])

  /**
   * 单句播放链（FA-11：四处「synthesize→playBlob→浏览器降级→engine=none」回退链收敛单点）
   * @param text 句子文本
   * @param idx 句子序号（UI 逐句展示）
   * @param precomputedBlob 已并行预合成的音频（speak/speakSentence 传）；未传则内部合成
   * @returns true = 本次走了浏览器降级（供调用方做引擎恢复判断）
   */
  const playSentence = useCallback(async (text: string, idx: number, precomputedBlob?: Blob | null): Promise<boolean> => {
    if (abortRef.current) return false
    setCurrentSentenceIdx(idx)
    const blob = precomputedBlob !== undefined ? precomputedBlob : await synthesizeSentence(text)
    if (abortRef.current) return false
    if (blob) {
      await playBlob(blob)
      return false
    }
    // 后端 TTS 不可用 → 浏览器 speechSynthesis 降级
    setEngine('browser')
    await new Promise<void>((resolve) => {
      const ok = browserSpeak(text, { rate: speed, persona, onEnd: resolve })
      if (!ok) {
        // 浏览器 TTS 也不可用（安卓无 Google 语音引擎）
        setEngine('none')
        resolve()
      }
    })
    return true
  }, [synthesizeSentence, playBlob, speed, persona])

  /** 重置播放队列状态机（幂等；stop/playSentences/startStreaming 共用） */
  const resetPlayQueue = useCallback(() => {
    const q = playQueueRef.current
    q.items = []
    q.blobs = []
    q.nextIdx = 0
    q.chain = null
    q.chainActive = false
    q.usedBrowserFallback = false
  }, [])

  /** 确保第 idx 句已发起合成（未发起则现场发起；幂等，重复调用复用同一 Promise） */
  const ensureSynthesized = useCallback((idx: number) => {
    const q = playQueueRef.current
    if (q.blobs[idx] !== undefined) return q.blobs[idx]
    const text = q.items[idx]?.text
    if (text === undefined) return null
    q.blobs[idx] = synthesizeSentence(text)
    return q.blobs[idx]
  }, [synthesizeSentence])

  /**
   * P1-2 并发窗口（滑动预取）：播放第 i 句时发起 i+1..i+PRECOMPUTE_AHEAD 的合成，
   * 保证"播放第 i 句时 i+1..i+3 已就绪"即可消除句间停顿；在途峰值恒为 4 路。
   * 流式逐批到达时链在活动期间会继续消费新入队句子（while 条件动态读取 items.length）。
   */
  const pump = useCallback(() => {
    const q = playQueueRef.current
    if (q.chainActive) return q.chain
    q.chainActive = true
    q.chain = (async () => {
      try {
        while (q.nextIdx < q.items.length && !abortRef.current) {
          const idx = q.nextIdx
          for (let a = 1; a <= PRECOMPUTE_AHEAD; a++) {
            if (q.items[idx + a]) ensureSynthesized(idx + a)
          }
          const blob = await ensureSynthesized(idx)
          if (abortRef.current) break
          if (await playSentence(q.items[idx].text, idx, blob)) {
            q.usedBrowserFallback = true
          }
          q.nextIdx++
        }
      } finally {
        q.chainActive = false
      }
      return q.usedBrowserFallback
    })()
    return q.chain
  }, [ensureSynthesized, playSentence])

  /** 统一入队（S-016 语义保留）：追加句子 → 窗口内发起合成 → 启动/继续播放链 */
  const enqueueSentences = useCallback((newSentences: string[]) => {
    if (newSentences.length === 0) return
    const q = playQueueRef.current
    const startIdx = q.items.length
    for (const text of newSentences) {
      q.items.push({ text })
    }
    // UI 逐句展示（波波话语气泡，design/27 §4.4）
    setSentences(q.items.map((it) => it.text))
    // P1-2：仅在并发窗口内立即发起合成（流式逐批到达也立即预取，BUG-TTS-02 回归点）
    for (let i = startIdx; i < q.items.length; i++) {
      if (i < q.nextIdx + CONCURRENCY_WINDOW) ensureSynthesized(i)
    }
    pump()
  }, [ensureSynthesized, pump])

  /**
   * P1-1：非流式入口（speak/speakSentence 共用）——切句 → 单轨入队 → 串行播放。
   * trackFallback 仅整段播放需要：追踪浏览器降级以恢复 backend 引擎标记。
   */
  const playSentences = useCallback(async (sentences: string[], trackFallback: boolean): Promise<boolean> => {
    abortRef.current = false
    const q = playQueueRef.current
    resetPlayQueue()
    streamBufferRef.current = ''
    setPlaying(true)
    setCurrentSentenceIdx(-1)
    enqueueSentences(sentences)
    const chain = q.chain
    await chain?.catch(() => {})
    // 链自然结束（未被 stop 接管）时清理 UI 状态
    if (!abortRef.current) {
      setPlaying(false)
      setCurrentSentenceIdx(-1)
      setSentences([])
    }
    return trackFallback ? q.usedBrowserFallback : false
  }, [resetPlayQueue, enqueueSentences])

  const speak = useCallback(async (text) => {
    if (muted) return
    stop()

    const sentences = mergeShortSentences(splitSentences(text))
    if (sentences.length === 0) return

    // S-016：共享播放编排（追踪浏览器降级，恢复 backend 标记）
    const usedBrowserFallback = await playSentences(sentences, true)
    // F-13：恢复阈值与切换阈值（>= 2 进降级窗口）对齐——< 2 表示未触发降级窗口，可恢复
    if (usedBrowserFallback && backendFailCount.current < 2) {
      setEngine('backend')
    }
  }, [muted, playSentences])

  /** 播放单条消息（点击气泡重播）—— 与自动播放相同的分句 + 并行合成逻辑，避免长文本单次合成音质下降 */
  const speakSentence = useCallback(async (text) => {
    if (muted) return
    stop()

    const sentences = mergeShortSentences(splitSentences(text))
    if (sentences.length === 0) return

    // S-016：共享播放编排（气泡重播不追踪降级标记）
    await playSentences(sentences, false)
  }, [muted, playSentences])

  /** 停止播放（重置单轨队列状态机，含流式缓冲） */
  const stop = useCallback(() => {
    abortRef.current = true
    if (audioRef.current) {
      audioRef.current.pause()
    }
    // 停止浏览器 TTS
    stopBrowserSpeak()
    resetPlayQueue()
    streamBufferRef.current = ''
    setPlaying(false)
    setCurrentSentenceIdx(-1)
    setSentences([])
  }, [resetPlayQueue])

  /* ===== 流式 TTS：AI 回复流式到达时，首句完成即开始合成播放，消除等待全文的滞后 ===== */
  // P1-1：原 streamQueueRef/streamIdxRef/streamPlayChainRef 已合并入 playQueueRef 单轨状态机

  /** 开始流式 TTS 会话（在流式回复开始时调用） */
  const startStreaming = useCallback(() => {
    if (muted) return
    stop()
    abortRef.current = false
    streamBufferRef.current = ''
    resetPlayQueue()
    setPlaying(true)
    setSentences([])
    setCurrentSentenceIdx(-1)
  }, [muted, stop, resetPlayQueue])

  /**
   * S-016（doing/93）：流式句子入队辅助——预合成 + 串入播放链 + 更新 UI
   * （feedToken 句末切句与 endStreaming 尾部冲刷共用，消除重复）
   * P1-1：实现收敛到 enqueueSentences 单轨（流式/非流式共用 playQueue 状态机）；
   * P1-2：合成受并发窗口限制（BUG-TTS-02"到达即预取"语义保留——窗口内立即发起）。
   */
  const enqueueStreamedSentences = useCallback((newSentences: string[]) => {
    enqueueSentences(newSentences)
  }, [enqueueSentences])

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
    // S-016：统一入队辅助（预合成 + 播放链）
    enqueueStreamedSentences(newSentences)
  }, [muted, enqueueStreamedSentences])

  /** 结束流式 TTS：冲刷剩余缓冲，等待播放完毕 */
  const endStreaming = useCallback(async () => {
    if (muted) return
    // 冲刷剩余文本（无句末标点的尾巴）
    const remaining = streamBufferRef.current.trim()
    streamBufferRef.current = ''
    if (remaining && !abortRef.current) {
      const tailSentences = mergeShortSentences(splitSentences(remaining))
      if (tailSentences.length > 0) {
        // S-016：统一入队辅助（尾部冲刷与句末切句共用）
        enqueueStreamedSentences(tailSentences)
      }
    }
    // 等待播放链完成（P1-1：与流式入队共用 playQueueRef.chain 单链）
    const q = playQueueRef.current
    await q.chain?.catch(() => {})
    if (!abortRef.current) {
      setPlaying(false)
      setCurrentSentenceIdx(-1)
      setSentences([])
    }
  }, [muted, enqueueStreamedSentences])

  /** 切换静音（FA-05：同时持久化偏好，供 EmotionSelect 设置面板跨页读取） */
  const toggleMute = useCallback(() => {
    setMuted(prev => {
      if (!prev) stop() // 静音时停止播放
      return !prev
    })
  }, [stop])

  // FA-05：setMuted 包装——状态变更同步写入偏好（兼容函数式更新，外部仅测试传字面量）
  const setMuted = useCallback((next: boolean | ((prev: boolean) => boolean)) => {
    setMutedState(prev => {
      const v = typeof next === 'function' ? (next as (p: boolean) => boolean)(prev) : next
      writeMutedPreference(v)
      return v
    })
  }, [])

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
