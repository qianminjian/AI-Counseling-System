/**
 * TTS 语音播放 Hook
 * - 逐句合成 + 队列播放
 * - 单一持久 Audio 元素（规避浏览器自动播放拦截）
 * - 支持暂停/重播/停止
 */
import { useState, useRef, useCallback } from 'react'

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

/** 将文本按标点切分为句子 */
function splitSentences(text) {
  if (!text) return []
  const cleaned = stripEmoji(text)
  if (!cleaned) return []
  // 按句号、问号、感叹号、换行切分
  const parts = cleaned.split(/(?<=[。！？\n])/).filter(s => s.trim())
  // 超长句二次切分（>40字在逗号处断开）
  const result = []
  for (const part of parts) {
    if (part.length > 40) {
      const subParts = part.split(/(?<=[，、；])/).filter(s => s.trim())
      result.push(...subParts)
    } else {
      result.push(part)
    }
  }
  return result
}

/** 合并过短的句子，避免"短句瞬间播完、下一句还没合成好"造成的句间间隔 */
function mergeShortSentences(sentences, minLen = 10) {
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

export function useTtsPlayer({ persona = 'xiaoxing', emotion = 'neutral', speed = 1.0 } = {}) {
  const [playing, setPlaying] = useState(false)
  const [currentSentenceIdx, setCurrentSentenceIdx] = useState(-1)
  const [muted, setMuted] = useState(false)
  // 单一持久 Audio 元素（在用户手势中创建，规避自动播放拦截）
  const audioRef = useRef(null)
  const audioCtxRef = useRef(null)
  const abortRef = useRef(false)

  /** 获取/创建持久 Audio 元素（移动端需 playsinline 才能内联播放） */
  const getAudio = useCallback(() => {
    if (!audioRef.current) {
      const audio = new Audio()
      audio.preload = 'auto'
      audio.playsInline = true
      audio.setAttribute('playsinline', '')
      audio.setAttribute('webkit-playsinline', '')
      audioRef.current = audio
    }
    return audioRef.current
  }, [])

  /** 在用户手势中调用，解锁浏览器音频自动播放限制 */
  const unlock = useCallback(() => {
    // 1. 解锁 AudioContext（复用同一个，避免重复创建）
    try {
      if (!audioCtxRef.current) {
        audioCtxRef.current = new (window.AudioContext || window.webkitAudioContext)()
      }
      if (audioCtxRef.current.state === 'suspended') {
        audioCtxRef.current.resume()
      }
      const ctx = audioCtxRef.current
      const buffer = ctx.createBuffer(1, 1, 22050)
      const source = ctx.createBufferSource()
      source.buffer = buffer
      source.connect(ctx.destination)
      source.start(0)
    } catch { /* ignore */ }

    // 2. 预创建并"预热"持久 Audio 元素（关键：在用户手势中 play 一次静音）
    const audio = getAudio()
    // 用一段极短的静音 data URI 预热，让浏览器记住这个元素已被用户手势激活
    const silentWav = 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA='
    audio.src = silentWav
    audio.volume = 0
    audio.play().then(() => {
      audio.pause()
      audio.volume = 1
      audio.currentTime = 0
    }).catch(() => {
      audio.volume = 1
    })
  }, [getAudio])

  /** 合成单句音频 */
  const synthesizeSentence = useCallback(async (text) => {
    try {
      const res = await fetch('/api/v1/tts/synthesize', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, persona, emotion, speed }),
      })
      if (!res.ok || res.status === 204) return null
      return await res.blob()
    } catch {
      return null
    }
  }, [persona, emotion, speed])

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

      audio.onended = () => { cleanup(); resolve() }
      audio.onerror = () => {
        console.warn('[TTS] 音频解码失败（MIME 不匹配？）', blob.type, blob.size)
        cleanup(); resolve()
      }
      audio.src = url
      audio.play().catch((err) => {
        console.warn('[TTS] play() 被拒绝:', err.name, err.message)
        cleanup(); resolve()
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
    setPlaying(true)

    // 所有句子同时并行合成：播放第 i 句时，第 i+1..n 句早已在后台合成
    // → 无论单句合成多慢，句间都零间隔（只有首句需等待一次合成）
    const audioPromises = sentences.map(s => synthesizeSentence(s))

    for (let i = 0; i < audioPromises.length; i++) {
      if (abortRef.current) break
      setCurrentSentenceIdx(i)
      const audioBlob = await audioPromises[i]
      if (!audioBlob || abortRef.current) continue
      await playBlob(audioBlob)
    }

    // 播放完毕
    setPlaying(false)
    setCurrentSentenceIdx(-1)
  }, [muted, synthesizeSentence, playBlob])

  /** 播放单句（点击气泡重播） */
  const speakSentence = useCallback(async (text) => {
    if (muted) return
    stop()

    const cleaned = stripEmoji(text)
    const audioBlob = await synthesizeSentence(cleaned)
    if (!audioBlob) return

    setPlaying(true)
    await playBlob(audioBlob)
    setPlaying(false)
  }, [muted, synthesizeSentence, playBlob])

  /** 停止播放 */
  const stop = useCallback(() => {
    abortRef.current = true
    if (audioRef.current) {
      audioRef.current.pause()
    }
    setPlaying(false)
    setCurrentSentenceIdx(-1)
  }, [])

  /** 切换静音 */
  const toggleMute = useCallback(() => {
    setMuted(prev => {
      if (!prev) stop() // 静音时停止播放
      return !prev
    })
  }, [stop])

  return {
    playing,
    muted,
    currentSentenceIdx,
    speak,
    speakSentence,
    stop,
    toggleMute,
    setMuted,
    unlock,
  }
}
