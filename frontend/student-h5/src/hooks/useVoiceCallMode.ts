/**
 * 语音通话模式状态机（design/28 §1.1）
 *
 *   off ──[开关打开 + 单独授权 + 引擎就绪]──> standby（监听唤醒词"哈喽波波"）
 *   standby ──[检测到唤醒词]──> active
 *       动作：TTS 短确认"我在呢！" → 确认播完后开始捕捉孩子说话
 *   active ──[孩子说话（识别出最终结果）]──> 走现有 sendMessage 流程
 *       → AI 流式回复(thinking) → TTS 朗读(speaking) → 朗读结束后重启聆听 + 重置冷却计时
 *   active ──[冷却期内无说话，默认 25s]──> standby
 *       动作：TTS 温柔收尾"我先安静陪着你，想说话随时叫我哦" → 恢复唤醒词监听
 *   active/standby ──[关闭开关 / 离开对话 / 引擎出错]──> off（释放麦克风）
 *
 * 关键设计：
 * - 防自听回声：busy（流式/录音/识别/朗读）期间，唤醒监听与语音捕捉全部暂停；
 * - iOS 稳健性：每个聆听回合用独立 SpeechRecognition 实例（说完即止、说完重启）；
 * - 会话内监听：仅在 ChatRoom 内由 enabled 控制，卸载即释放（隐私即设计）。
 */
import { useState, useEffect, useRef, useCallback } from 'react'
import { useWakeWord } from './useWakeWord'
import { matchesWakeWord } from '../config/wakeWord'
import { createSpeechRecognition, type SpeechRecognitionHandle } from '../utils/speechRecognition'
// P1-3（audit-report-07 P1-3）：唤醒交互参数走远程配置（voiceCall.*），本地常量仅作 fallback
//（对齐 FA-12 useWakeWord resolveWakeParams 既有模式，useWakeWord.ts:68-76）
import { getConfigValue } from '../config/remote'

/** 唤醒确认短句（播完后才开始捕捉说话） */
const WAKE_CONFIRM_TEXT = '我在呢！'
/** 冷却收尾句（沉默窗满，温柔关窗） */
const COOLDOWN_CLOSE_TEXT = '我先安静陪着你，想说话随时叫我哦'
/** 会话窗沉默超时（秒）：AI 说完后孩子 25s 未接话 → 关窗回待唤醒态（远程键 voiceCall.cooldownSeconds） */
const COOLDOWN_SECONDS = 25
/** 聆听回合重启间隔（毫秒）（远程键 voiceCall.restartDelayMs） */
const RESTART_DELAY_MS = 300
/** 语音结束防抖（毫秒）：用户停止说话后等待此时长才发送最终结果，避免中间停顿截断（远程键 voiceCall.speechEndDebounceMs） */
const SPEECH_END_DEBOUNCE_MS = 1800

/**
 * P1-3：唤醒交互参数运行时解析（挂载时取一次 + 每次渲染刷新，远程配置加载后自动生效；
 * 远程无值 / 未加载 → getConfigValue fallback 到本地常量，行为与旧版一致）
 */
function resolveVoiceCallParams() {
  return {
    cooldownSeconds: getConfigValue('voiceCall.cooldownSeconds', COOLDOWN_SECONDS),
    restartDelayMs: getConfigValue('voiceCall.restartDelayMs', RESTART_DELAY_MS),
    speechEndDebounceMs: getConfigValue('voiceCall.speechEndDebounceMs', SPEECH_END_DEBOUNCE_MS),
  }
}

/**
 * @param {object} opts
 * @param {boolean} opts.enabled   唤醒模式已开启且已授权（关闭 → 立即回 off 并释放）
 * @param {object} opts.tts        useTtsPlayer 返回值（speak/unlock 等）
 * @param {boolean} opts.busy      AI 忙碌（streaming || tts.playing || recording || analyzing）
 * @param {(text: string) => void} opts.onFinalTranscript 孩子说话最终识别结果（→ sendMessage）
 * @returns {{ mode: 'off'|'standby'|'active', wakeSupported: boolean }}
 */
export function useVoiceCallMode({ enabled, tts, busy, onFinalTranscript }) {
  // DC-012：字面量联合类型收紧（computeBoboState 入参契约要求），避免 string 宽化
  const [mode, setMode] = useState<'off' | 'standby' | 'active'>('off')

  // 同步 ref（供回调/计时器读取最新值，避免闭包过期）
  const modeRef = useRef<'off' | 'standby' | 'active'>('off')
  const enabledRef = useRef(enabled)
  const busyRef = useRef(busy)
  const onFinalTranscriptRef = useRef(onFinalTranscript)
  // 注意：useTtsPlayer 每次渲染返回新对象，tts 只能走 ref（否则冷却计时器被反复重置）
  const ttsRef = useRef(tts)
  // FE-003：识别句柄 / 计时器 / 回调 ref 显式类型（可选值 + 判空使用）
  const recRef = useRef<SpeechRecognitionHandle | null>(null)
  const restartTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const startListeningRoundRef = useRef<(() => void) | null>(null)
  /** 防抖计时器：用户停止说话后延迟发送，避免中间停顿截断句子 */
  const speechEndTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  /** 累积的转写文本（continuous 模式下多个 result 事件拼接） */
  const accumulatedTextRef = useRef('')
  // 防 re-entry：Whisper 多窗串行检测可能在 React 状态传播前多次触发 onDetected
  const detectingRef = useRef(false)
  // 首轮过滤：active 后第一轮 SpeechRecognition 结果需过滤唤醒词残留
  const firstRoundRef = useRef(false)
  // P1-3：唤醒交互参数经 ref 读取（每次渲染刷新，热路径避免闭包捕获过期常量）
  const voiceCallParamsRef = useRef(resolveVoiceCallParams())
  voiceCallParamsRef.current = resolveVoiceCallParams()

  useEffect(() => { modeRef.current = mode }, [mode])
  useEffect(() => { enabledRef.current = enabled }, [enabled])
  useEffect(() => { busyRef.current = busy }, [busy])
  useEffect(() => { onFinalTranscriptRef.current = onFinalTranscript })
  useEffect(() => { ttsRef.current = tts })

  /** 停止当前语音捕捉（含待重启的下一轮） */
  const stopListening = useCallback(() => {
    if (restartTimerRef.current) {
      clearTimeout(restartTimerRef.current)
      restartTimerRef.current = null
    }
    if (speechEndTimerRef.current) {
      clearTimeout(speechEndTimerRef.current)
      speechEndTimerRef.current = null
    }
    if (recRef.current) {
      const rec = recRef.current
      recRef.current = null
      rec.stop(true) // 主动停止不触发重启逻辑（FA-10 装配层 silent）
    }
  }, [])

  /** 开启一轮语音捕捉（FA-10 共享装配层：continuous + interimResults 单点装配，防抖判断说完） */
  const startListeningRound = useCallback(() => {
    stopListening()
    accumulatedTextRef.current = ''
    recRef.current = createSpeechRecognition({
      aggregate: 'concat', // 防抖判断依赖完整文本（含 interim）
      onText: (sendText) => {
        const fullText = sendText.trim()
        if (!fullText) return
        accumulatedTextRef.current = fullText
  
        // 防抖：每次收到新结果重置计时器，用户停止说话 1.8s 后才发送
        if (speechEndTimerRef.current) clearTimeout(speechEndTimerRef.current)
        speechEndTimerRef.current = setTimeout(() => {
          speechEndTimerRef.current = null
          const text = accumulatedTextRef.current
          accumulatedTextRef.current = ''
          if (!text) return
          // 首轮过滤：用户因延迟重复说唤醒词，SpeechRecognition 可能捕获整段重复
          if (firstRoundRef.current) {
            firstRoundRef.current = false
            const segments = text.split(/[。.！!？?，,、\s]+/).filter(Boolean)
            const wakeRatio = segments.filter(s => matchesWakeWord(s)).length / Math.max(segments.length, 1)
            if (wakeRatio > 0.5) {
              return
            }
          }
          // 发送后停止当前识别实例，等 busy 状态变化后自动重启新一轮
          const rec = recRef.current
          if (rec) {
            recRef.current = null
            rec.stop(true) // silent：防 onEnd 触发重启逻辑
          }
          onFinalTranscriptRef.current?.(text)
        }, voiceCallParamsRef.current.speechEndDebounceMs)
      },
      onEnd: () => {
        recRef.current = null
        // 如果防抖计时器还在跑（用户刚说完，Chrome 因超时停了），等它自然触发
        if (speechEndTimerRef.current) return
        // 仍在会话窗内且 AI 不忙 → 开启下一轮聆听
        if (modeRef.current === 'active' && enabledRef.current && !busyRef.current) {
          restartTimerRef.current = setTimeout(() => startListeningRoundRef.current?.(), voiceCallParamsRef.current.restartDelayMs)
        }
      },
      onError: (error) => {
        // no-speech 错误忽略（用户没说话），其他错误记录
        if (error !== 'no-speech') {
          console.warn('[VoiceCall] 识别错误:', error)
        }
      },
    })
  }, [stopListening])

  useEffect(() => { startListeningRoundRef.current = startListeningRound })

  /* ===== 唤醒监听：standby 时启动引擎（加载模型 + 麦克风），busy 时暂停检测但保持就绪（防自听回声） ===== */
  const { supported: wakeSupported, wakeStatus } = useWakeWord({
    active: enabled && mode === 'standby',
    paused: busy,
    onDetected: () => {
      // 双重防护：modeRef 检查 + detectingRef 锁（防 Whisper 多窗在 React 渲染前重复触发）
      if (modeRef.current !== 'standby' || detectingRef.current) return
      detectingRef.current = true
      firstRoundRef.current = true // 标记首轮，用于过滤唤醒词残留
      setMode('active')
      // 唤醒确认：TTS"我在呢！"播完（busy 转 false）后，下方捕捉 effect 自动开始聆听
      ttsRef.current.unlock?.()
      ttsRef.current.speak(WAKE_CONFIRM_TEXT)
    },
  })

  /* ===== 语音捕捉与防自听：busy 时暂停识别；AI 说完后在会话窗内重启聆听 ===== */
  useEffect(() => {
    if (busy) {
      stopListening()
    } else if (mode === 'active' && enabled && !recRef.current) {
      // TTS 播完进入聆听，解锁 detecting 锁（允许下次 standby→active 转换）
      detectingRef.current = false
      startListeningRound()
    }
  }, [busy, mode, enabled, stopListening, startListeningRound])

  /* ===== 冷却窗：AI 说完后 25s 无说话 → 温柔收尾 → 回待唤醒态 ===== */
  useEffect(() => {
    if (mode !== 'active' || busy || !enabled) return undefined
    const timer = setTimeout(async () => {
      if (modeRef.current !== 'active') return
      stopListening()
      await ttsRef.current.speak(COOLDOWN_CLOSE_TEXT)
      // 收尾句播完，恢复唤醒词监听（需再说“哈喽波波”开启新会话窗）
      if (modeRef.current === 'active' && enabledRef.current) {
        setMode('standby')
      }
    }, voiceCallParamsRef.current.cooldownSeconds * 1000)
    return () => clearTimeout(timer)
  }, [mode, busy, enabled, stopListening])

  /* ===== 开关：开启 → off 进 standby；关闭/撤销授权 → 立即 off 并释放 ===== */
  useEffect(() => {
    if (enabled) {
      if (modeRef.current === 'off') {
        setMode('standby')
      }
    } else if (modeRef.current !== 'off') {
      stopListening()
      setMode('off')
    }
  }, [enabled, stopListening])

  /* ===== 降级：引擎不支持/未配置（wakeSupported=false）→ 回落 off，不展示待唤醒态 ===== */
  useEffect(() => {
    if (!wakeSupported && modeRef.current !== 'off') {
      stopListening()
      setMode('off')
    }
  }, [wakeSupported, stopListening])

  // 离开对话（卸载）：释放语音捕捉（唤醒引擎由 useWakeWord 自身 cleanup 释放）
  useEffect(() => () => stopListening(), [stopListening])

  return { mode, wakeSupported, wakeStatus }
}
