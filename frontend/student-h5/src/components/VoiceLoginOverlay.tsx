/**
 * 声纹登录引导对话覆盖层
 *
 * 登录页点击"声音进入"后全屏展示：
 * 1. 波波 TTS 提问（字幕动画）
 * 2. 麦克风采集孩子回答（音量动画）
 * 3. 提取 embedding → 本地比对 → 设备凭证换后端 token → 成功/失败
 *
 * 状态机：idle → speaking（TTS播放）→ listening（采集）→ processing（推理）→ success/fail
 * 失败细分（failKind）：mic（麦克风不可用）/ mismatch（声纹不匹配，可重试 2 次）/ credential（设备凭证缺失或过期）
 */
import { useState, useEffect, useRef, useCallback } from 'react'
import { VP_GUIDE_SCRIPTS, VP_SAMPLE_RATE, VP_SEGMENT_DURATION, VP_SILENCE_THRESHOLD } from '../config/voiceprint'
import { getConfigValue } from '../config/remote'
import { useVoiceprint } from '../hooks/useVoiceprint'
import { unlockAudio, getGlobalAudioElement } from '../utils/audioUnlock'
import { createPcmCapture, type PcmCaptureHandle } from '../utils/createPcmCapture'
import { getVoiceprint, getRemoteVoiceprintTenantId } from '../utils/voiceprintStore'
import { voiceLogin, remoteVoiceprintVerify, getVoiceprintConfig, setToken, setRefreshToken, setUser, fetchLoginPrompt } from '../api'
import { readLocalStorageSafe } from '../utils/storage'

/** 主题 → 音色映射（登录页未登录，读 localStorage 主题） */
const THEME_PERSONA_MAP: Record<string, string> = {
  ocean: 'xiaoxing',    // 海洋蓝 → 小星（温暖大姐姐）
  garden: 'qiqiu',      // 花园粉 → 方言（活泼小伙伴）
  rainbow: 'yueliang',  // 彩虹紫 → 月亮（温柔讲故事）
}
function getLoginPersona(): string {
  // AUD-065：裸 localStorage 改安全封装（隐私模式/存储禁用下不抛 SecurityError）
  const themeId = readLocalStorageSafe<string>('mindsafe_theme_v1', 'ocean')
  return THEME_PERSONA_MAP[themeId] || 'xiaoxing'
}

/**
 * @param {object} props
 * @param {'verify'|'enroll'} props.mode - 验证模式 / 注册采集模式
 * @param {(result: {matched: boolean, userId?: string, pseudonym?: string}) => void} props.onComplete
 * @param {() => void} props.onCancel - 取消/返回
 */
export default function VoiceLoginOverlay({ mode = 'verify', onComplete, onCancel }) {
  const scripts = getConfigValue(`guideScripts.${mode}`, VP_GUIDE_SCRIPTS[mode] || VP_GUIDE_SCRIPTS.verify)
  const [phase, setPhase] = useState('intro') // intro | speaking | listening | processing | success | fail
  const [stepIndex, setStepIndex] = useState(0)
  const [volume, setVolume] = useState(0)
  const [statusText, setStatusText] = useState('')
  const [failCount, setFailCount] = useState(0)
  const [failKind, setFailKind] = useState('') // mic | mismatch | credential
  const [vpMode, setVpMode] = useState<'local' | 'remote'>('local')
  const vpModeRef = useRef<'local' | 'remote'>('local') // 同步 ref，避免 runFlow 闭包过期
  const [privacyNote, setPrivacyNote] = useState('声音信息只保存在这台设备上，不会上传到任何服务器')

  const { extractEmbedding, verify, loading, modelErrorRef } = useVoiceprint()
  const collectedEmbeddings = useRef([])
  const audioCtxRef = useRef<AudioContext | null>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const captureRef = useRef<PcmCaptureHandle | null>(null)
  const chunksRef = useRef<Float32Array[]>([])
  const listeningRef = useRef(false)
  const cancelledRef = useRef(false)

  // 清理资源
  const cleanup = useCallback(() => {
    cancelledRef.current = true
    listeningRef.current = false
    captureRef.current?.cleanup()
    captureRef.current = null
    streamRef.current?.getTracks().forEach((t) => t.stop())
    audioCtxRef.current?.close().catch(() => {})
  }, [])

  useEffect(() => () => cleanup(), [cleanup])

  /** TTS 播放引导语（后端 CosyVoice 优先，降级 speechSynthesis） */
  const speakPrompt = useCallback((text) => {
    return new Promise<void>(async (resolve) => {
      let resolved = false
      const done = () => { if (!resolved) { resolved = true; resolve() } }
      // 超时保护
      const timer = setTimeout(done, 10000)

      // 1. 尝试后端 TTS（CosyVoice 音色）
      try {
        const persona = getLoginPersona()
        // F-2 端点收敛：具名 authFetch 接缝（ARCH-005），401 自动刷新+重放
        const res = await fetchLoginPrompt(text, persona)
        if (res.ok) {
          const blob = await res.blob()
          if (blob.size > 0) {
            const audio = getGlobalAudioElement()
            const url = URL.createObjectURL(blob)
            audio.src = url
            audio.onended = () => { URL.revokeObjectURL(url); clearTimeout(timer); done() }
            audio.onerror = () => { URL.revokeObjectURL(url); clearTimeout(timer); done() }
            await audio.play()
            return
          }
        }
      } catch { /* 后端 TTS 不可用，降级 */ }

      // 2. 降级：浏览器 speechSynthesis
      if ('speechSynthesis' in window) {
        try {
          window.speechSynthesis.cancel()
          const utter = new SpeechSynthesisUtterance(text)
          utter.lang = 'zh-CN'
          utter.rate = 0.9
          const voices = window.speechSynthesis.getVoices()
          const zhVoice = voices.find((v) => v.lang.startsWith('zh'))
          if (zhVoice) utter.voice = zhVoice
          utter.onend = () => { clearTimeout(timer); done() }
          utter.onerror = () => { clearTimeout(timer); done() }
          window.speechSynthesis.speak(utter)
          return
        } catch { /* ignore */ }
      }

      // 3. 无 TTS：显示字幕 2 秒后继续
      setTimeout(() => { clearTimeout(timer); done() }, 2000)
    })
  }, [])

  /** 初始化麦克风 + PCM 采集（AudioWorklet 优先，ScriptProcessor 降级） */
  const initMic = useCallback(async () => {
    if (audioCtxRef.current) return true
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      })
      streamRef.current = stream
      const ctx = new (window.AudioContext || window.webkitAudioContext)()
      if (ctx.state === 'suspended') await ctx.resume().catch(() => {})
      audioCtxRef.current = ctx

      const handle = await createPcmCapture(ctx, stream, (pcm: Float32Array) => {
        if (!listeningRef.current) return
        chunksRef.current.push(pcm)
        // 计算音量（UI 动画）
        let sum = 0
        for (let i = 0; i < pcm.length; i++) sum += pcm[i] * pcm[i]
        setVolume(Math.min(1, Math.sqrt(sum / pcm.length) * 10))
      })
      captureRef.current = handle
      return true
    } catch (err) {
      console.warn('[VoiceLogin] 麦克风初始化失败:', (err as Error)?.message)
      setFailKind('mic')
      setStatusText('麦克风不可用，请用秘密数字登录')
      setPhase('fail')
      return false
    }
  }, [])

  /** 采集一段音频（duration 秒）→ 返回 Float32Array */
  const captureSegment = useCallback((duration) => {
    return new Promise((resolve) => {
      chunksRef.current = []
      listeningRef.current = true
      setVolume(0)

      const timer = setTimeout(() => {
        listeningRef.current = false
        setVolume(0)
        // 合并 chunks
        const totalLen = chunksRef.current.reduce((s, c) => s + c.length, 0)
        const merged = new Float32Array(totalLen)
        let offset = 0
        for (const c of chunksRef.current) {
          merged.set(c, offset)
          offset += c.length
        }
        resolve(merged)
      }, duration * 1000)

      // 保存 timer 以便取消
      return () => clearTimeout(timer)
    })
  }, [])

  /** 主流程：逐轮引导对话 */
  const runFlow = useCallback(async () => {
    // 解锁音频（用户已交互——点击了声纹入口或唤醒词触发）
    unlockAudio()

    const micOk = await initMic()
    if (!micOk) return

    collectedEmbeddings.current = []
    let modelRetryCount = 0 // 模型加载失败重试计数（避免死循环）

    for (let i = 0; i < scripts.length; i++) {
      if (cancelledRef.current) return
      setStepIndex(i)

      // 1. 播放引导语
      setPhase('speaking')
      setStatusText(scripts[i].prompt)
      await speakPrompt(scripts[i].prompt)
      if (cancelledRef.current) return

      // 2. 采集回答
      setPhase('listening')
      setStatusText(scripts[i].hint || '正在听你说...')
      const audio = await captureSegment(scripts[i].duration)
      if (cancelledRef.current) return

      // 3. 提取 embedding
      setPhase('processing')
      setStatusText('正在识别...')
      const sampleRate = audioCtxRef.current?.sampleRate || VP_SAMPLE_RATE
      const embedding = await extractEmbedding(audio, sampleRate)

      if (embedding) {
        collectedEmbeddings.current.push(embedding)
        modelRetryCount = 0 // 成功则重置
      } else if (modelErrorRef.current) {
        // 模型加载失败：轮询重试（AUD-025：绑定真实加载耗时，替代固定假等 3s——
        // 每次轮询真实发起一次模型加载/推理，弱网下下载多久就等多久，最长 60s；
        // 模型已就绪但音频静音时交外层“没有听清”分支）
        modelRetryCount++
        if (modelRetryCount > 3) {
          setFailKind('mic')
          setPhase('fail')
          setStatusText('语音引擎加载失败，请先用秘密数字登录，稍后再试')
          return
        }
        setStatusText('语音引擎准备中，请稍候...')
        const retryDeadline = Date.now() + 60000
        let retried: number[] | null = null
        while (Date.now() < retryDeadline) {
          if (cancelledRef.current) return
          await new Promise((r) => setTimeout(r, 800))
          retried = await extractEmbedding(audio, sampleRate)
          if (retried) break
          if (!modelErrorRef.current) break // 模型已就绪 → 本次为静音/音频问题，交外层处理
        }
        if (retried) {
          collectedEmbeddings.current.push(retried)
          modelRetryCount = 0 // 成功则重置
        } else if (!modelErrorRef.current) {
          // 静音或提取失败：提示重试（不计入失败次数）
          setStatusText('没有听清，再说一次吧~')
          await new Promise((r) => setTimeout(r, 1500))
          i-- // 重试当前轮
          continue
        } else {
          setFailKind('mic')
          setPhase('fail')
          setStatusText('语音引擎加载失败，请先用秘密数字登录，稍后再试')
          return
        }
      } else {
        // 静音或提取失败：提示重试（不计入失败次数）
        setStatusText('没有听清，再说一次吧~')
        await new Promise((r) => setTimeout(r, 1500))
        i-- // 重试当前轮
        continue
      }
    }

    if (cancelledRef.current) return

    // 4. 比对/完成
    setPhase('processing')
    setStatusText('正在确认身份...')

    if (mode === 'verify') {
      if (vpModeRef.current === 'remote') {
        // remote 模式：embedding 传服务端比对，服务端直接签发 token
        // AUD-001：verify 必须携带租户维度（比对范围收窄至本租户）；
        // 租户来自录入时后端签发，缺失说明旧版本录入或存储被清 → 引导重录
        const tenantId = getRemoteVoiceprintTenantId()
        if (!tenantId) {
          setFailKind('credential')
          setPhase('fail')
          setStatusText('这台设备的登录钥匙还没办好，先用秘密数字进入，再到「设置」里重录一次声音吧')
          return
        }
        try {
          const data = await remoteVoiceprintVerify(collectedEmbeddings.current, tenantId)
          if (data.matched) {
            setToken(data.token)
            if (data.refreshToken) setRefreshToken(data.refreshToken)
            setUser({ userId: data.userId, userType: data.userType, pseudonym: data.displayName })
            setPhase('success')
            setStatusText(`是${data.displayName}呀！欢迎回来！`)
            setTimeout(() => onComplete({ matched: true, userId: data.userId, pseudonym: data.displayName }), 1500)
          } else {
            const newCount = failCount + 1
            setFailCount(newCount)
            setFailKind('mismatch')
            setPhase('fail')
            setStatusText(newCount < 2 ? '嗯？听起来不太像你，要不再试一次？' : '还是没认出来，用秘密数字登录吧')
          }
        } catch (err) {
          console.error('[VoiceLogin] remote verify 失败:', err)
          setFailKind('credential')
          setPhase('fail')
          setStatusText('网络不太好，先用秘密数字登录吧')
        }
      } else {
        // local 模式：本地 IndexedDB 比对 → 设备凭证换 token
        const result = await verify(collectedEmbeddings.current)
        if (result.matched) {
          const vp = await getVoiceprint(result.userId) as any
          const cred = vp?.voiceCredential
          if (!cred) {
            setFailKind('credential')
            setPhase('fail')
            setStatusText('这台设备的登录钥匙还没办好，先用秘密数字进入，再到「设置」里重录一次声音吧')
            return
          }
          try {
            const data = await voiceLogin(cred)
            setToken(data.token)
            if (data.refreshToken) setRefreshToken(data.refreshToken)
            setUser({ userId: data.userId, userType: data.userType, pseudonym: data.displayName })
            setPhase('success')
            setStatusText(`是${result.pseudonym}呀！欢迎回来！`)
            setTimeout(() => onComplete(result), 1500)
          } catch {
            setFailKind('credential')
            setPhase('fail')
            setStatusText('登录钥匙过期啦，先用秘密数字进入，再到「设置」里重录一次声音吧')
          }
        } else {
          const newCount = failCount + 1
          setFailCount(newCount)
          setFailKind('mismatch')
          setPhase('fail')
          setStatusText(newCount < 2 ? '嗯？听起来不太像你，要不再试一次？' : '还是没认出来，用秘密数字登录吧')
        }
      }
    } else {
      // 注册模式：返回采集的 embeddings（由调用方根据模式决定存储方式）
      setPhase('success')
      setStatusText('声音录入成功！')
      setTimeout(() => onComplete({ matched: true, embeddings: collectedEmbeddings.current }), 1500)
    }
  }, [scripts, mode, initMic, speakPrompt, captureSegment, extractEmbedding, verify, failCount, onComplete])

  // 启动时获取声纹模式配置（runFlow 通过 vpModeRef 读取，无竞态）
  useEffect(() => {
    getVoiceprintConfig().then((cfg) => {
      vpModeRef.current = cfg.mode
      setVpMode(cfg.mode)
      setPrivacyNote(cfg.privacyNote)
    }).catch(() => { /* 默认 local */ })
  }, [])

  // 启动流程
  useEffect(() => {
    runFlow()
    return () => { cancelledRef.current = true }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // 始终指向最新 runFlow（重试时避免闭包过期）
  const runFlowRef = useRef(runFlow)
  useEffect(() => { runFlowRef.current = runFlow })

  /** 声纹不匹配时重试（麦克风保持打开，直接重跑引导流程） */
  const handleRetry = () => {
    setFailKind('')
    setStepIndex(0)
    setPhase('intro')
    runFlowRef.current()
  }

  // ===== UI =====

  const phaseEmoji = {
    intro: '🐬',
    speaking: '🐬',
    listening: '🎤',
    processing: '⏳',
    success: '🎉',
    fail: '🤔',
  }

  return (
    <div className="fixed inset-0 z-50 flex flex-col items-center justify-center p-6"
      style={{ background: 'linear-gradient(to bottom, #E0F7FA, #B2EBF2)' }}>

      {/* 取消按钮 */}
      <button
        onClick={() => { cleanup(); onCancel() }}
        className="absolute top-6 right-6 w-10 h-10 rounded-full bg-white/60 flex items-center justify-center text-gray-500 hover:bg-white transition-colors"
      >
        ✕
      </button>

      {/* 波波动画 */}
      <div className={`text-7xl mb-6 transition-transform ${phase === 'listening' ? 'animate-bounce' : 'animate-pulse'}`}>
        {phaseEmoji[phase] || '🐬'}
      </div>

      {/* 进度指示 */}
      <div className="flex gap-2 mb-6">
        {scripts.map((_, i) => (
          <div key={i} className={`w-3 h-3 rounded-full transition-colors ${
            i < stepIndex ? 'bg-green-400' : i === stepIndex ? 'bg-blue-500' : 'bg-gray-300'
          }`} />
        ))}
      </div>

      {/* 对话字幕 */}
      <div className="w-full max-w-sm bg-white rounded-2xl shadow-lg p-6 text-center mb-6">
        <p className="text-lg font-medium text-gray-800 leading-relaxed">{statusText}</p>
      </div>

      {/* 音量动画（采集时） */}
      {phase === 'listening' && (
        <div className="flex items-end gap-1 h-16 mb-6">
          {Array.from({ length: 12 }).map((_, i) => {
            const h = Math.max(8, volume * 60 * (0.5 + Math.random() * 0.5))
            return (
              <div key={i} className="w-2 rounded-full bg-blue-400 transition-all duration-100"
                style={{ height: `${h}px` }} />
            )
          })}
        </div>
      )}

      {/* 加载动画（推理时） */}
      {phase === 'processing' && (
        <div className="mb-6">
          <div className="w-10 h-10 border-4 border-blue-200 border-t-blue-500 rounded-full animate-spin" />
        </div>
      )}

      {/* 失败时显示重试/降级入口（声纹不匹配可重试 2 次；凭证/麦克风问题直接引导 PIN） */}
      {phase === 'fail' && (
        <div className="mt-4 flex flex-col items-center gap-3">
          {failKind === 'mismatch' && failCount < 2 && (
            <button
              onClick={handleRetry}
              className="px-8 py-3.5 rounded-full bg-blue-500 text-white font-medium hover:bg-blue-600 active:scale-[0.98] transition-all shadow-lg"
            >
              🎤 再试一次
            </button>
          )}
          <button
            onClick={() => { cleanup(); onCancel() }}
            className={failKind === 'mismatch' && failCount < 2
              ? 'px-6 py-2.5 rounded-full bg-white/70 text-gray-500 text-sm font-medium hover:bg-white active:scale-[0.97] transition-all'
              : 'px-8 py-3.5 rounded-full bg-blue-500 text-white font-medium hover:bg-blue-600 active:scale-[0.98] transition-all shadow-lg'}
          >
            用秘密数字登录
          </button>
        </div>
      )}

      {/* 注册模式：显式跳过按钮（儿童友好，比右上角✕更明显） */}
      {mode === 'enroll' && phase !== 'success' && (
        <button
          onClick={() => { cleanup(); onCancel() }}
          className="mt-6 px-6 py-2.5 rounded-full bg-white/70 text-gray-500 text-sm font-medium hover:bg-white active:scale-[0.97] transition-all"
        >
          先不录了，以后再说
        </button>
      )}

      {/* 底部隐私提示（根据模式动态显示） */}
      <p className="absolute bottom-8 text-xs text-gray-400 text-center px-6">
        {privacyNote}
      </p>
    </div>
  )
}
