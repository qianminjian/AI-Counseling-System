/**
 * TTS 语音播放 Hook
 * - 逐句合成 + 队列播放
 * - 支持暂停/重播/停止
 * - 当前播放句高亮回调
 */
import { useState, useRef, useCallback } from 'react'

/** 将文本按标点切分为句子 */
function splitSentences(text) {
  if (!text) return []
  // 按句号、问号、感叹号、换行切分
  const parts = text.split(/(?<=[。！？\n])/).filter(s => s.trim())
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

export function useTtsPlayer({ persona = 'xiaoxing', emotion = 'neutral', speed = 1.0 } = {}) {
  const [playing, setPlaying] = useState(false)
  const [currentSentenceIdx, setCurrentSentenceIdx] = useState(-1)
  const [muted, setMuted] = useState(false)
  const audioRef = useRef(null)
  const queueRef = useRef([])
  const playingRef = useRef(false)
  const abortRef = useRef(false)

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

  /** 播放完整 AI 回复（逐句队列） */
  const speak = useCallback(async (text) => {
    if (muted) return

    // 停止之前的播放
    stop()

    const sentences = splitSentences(text)
    if (sentences.length === 0) return

    queueRef.current = sentences
    abortRef.current = false
    playingRef.current = true
    setPlaying(true)

    for (let i = 0; i < sentences.length; i++) {
      if (abortRef.current) break

      setCurrentSentenceIdx(i)
      const audioBlob = await synthesizeSentence(sentences[i])
      if (!audioBlob || abortRef.current) continue

      // 播放音频
      await new Promise((resolve) => {
        const audio = new Audio(URL.createObjectURL(audioBlob))
        audioRef.current = audio
        audio.onended = () => {
          URL.revokeObjectURL(audio.src)
          resolve()
        }
        audio.onerror = () => resolve()
        audio.play().catch(() => resolve())
      })
    }

    // 播放完毕
    playingRef.current = false
    setPlaying(false)
    setCurrentSentenceIdx(-1)
  }, [muted, synthesizeSentence])

  /** 播放单句（点击气泡重播） */
  const speakSentence = useCallback(async (text) => {
    if (muted) return
    stop()

    const audioBlob = await synthesizeSentence(text)
    if (!audioBlob) return

    setPlaying(true)
    await new Promise((resolve) => {
      const audio = new Audio(URL.createObjectURL(audioBlob))
      audioRef.current = audio
      audio.onended = () => {
        URL.revokeObjectURL(audio.src)
        setPlaying(false)
        resolve()
      }
      audio.onerror = () => { setPlaying(false); resolve() }
      audio.play().catch(() => { setPlaying(false); resolve() })
    })
  }, [muted, synthesizeSentence])

  /** 停止播放 */
  const stop = useCallback(() => {
    abortRef.current = true
    playingRef.current = false
    if (audioRef.current) {
      audioRef.current.pause()
      audioRef.current = null
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
  }
}
