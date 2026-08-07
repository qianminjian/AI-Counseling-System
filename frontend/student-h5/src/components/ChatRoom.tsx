import { useState, useRef, useEffect, useCallback } from 'react'
import VoiceConsentDialog, { useVoiceConsent } from './VoiceConsentDialog'
import VoiceCallConsentDialog, { useVoiceCallConsent } from './VoiceCallConsentDialog'
import SatisfactionDialog from './SatisfactionDialog'
import SettingsPanel from './SettingsPanel'
import ConfirmDialog from './ConfirmDialog'
import ToolboxPanel from './ToolboxPanel'
import SosPanel from './SosPanel'
import BoBoPet from './BoBoPet'
import BoBoAvatar from './BoBoAvatar'
import DraggableVoiceButton from './DraggableVoiceButton'
import MessageBubble from './MessageBubble'
import { useTheme } from '../theme/ThemeProvider'
import { useVoicePersona } from '../hooks/useVoicePersona'
import { useTtsPlayer } from '../hooks/useTtsPlayer'
import { useVoiceCallMode } from '../hooks/useVoiceCallMode'
import { preloadWakeModel } from '../hooks/useWakeWord'
import { useWakeEnabled } from '../hooks/useWakeEnabled'
import { useSilenceNudge } from '../hooks/useSilenceNudge'
import { useVoiceInputPipeline } from '../hooks/useVoiceInputPipeline'
import { useChatSession } from '../hooks/useChatSession'
// DC-012：规则抽离（SPEC §26）——唤醒授权联动 / 安卓音频路由 / 波波状态机
import { useWakeConsentFlow } from '../hooks/useWakeConsentFlow'
import { useAndroidAudioRouting } from '../hooks/useAndroidAudioRouting'
import { computeBoboState } from '../utils/chatRoomRules'
import { getUser } from '../api'
import { useBoboExpression } from '../hooks/useBoboExpression'
import { useMotionPreference } from '../hooks/useMotionPreference'

/** 会话信息（由 EmotionSelect 传入） */
export interface SessionInfo {
  sessionId: string
  greeting: string
  emotionTag: string
}

export default function ChatRoom({ session, onEnd, onSwitchUser }: { session: SessionInfo; onEnd: () => void; onSwitchUser?: () => void }) {
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [toolboxOpen, setToolboxOpen] = useState(false) // 百宝箱（F-2，design/36）
  const [sosOpen, setSosOpen] = useState(false) // SOS 面板（design/36 §3.4 全局常驻）
  const [speakingMsgIdx, setSpeakingMsgIdx] = useState(-1)
  const [voiceNotice, setVoiceNotice] = useState('')
  const [cancelArmed, setCancelArmed] = useState(false) // 按住说话：上滑进入取消态
  const [confirmSwitch, setConfirmSwitch] = useState(false) // 切换同学确认弹窗
  const bottomRef = useRef(null)
  const greetingSpokenRef = useRef(false)
  const pointerStartYRef = useRef(0) // 按下时 Y 坐标（检测上滑取消）

  // 主题 + 音色
  const { theme } = useTheme()

  // 波波表情状态机（TTSFX-004，design/37 §4.1）：emotionBus 同源信号 + 交互事件注入
  const boboExpression = useBoboExpression()
  // 动效偏好（design/37 §4.3/§4.4）：动画/触觉开关 + 帧率降级守卫
  const motion = useMotionPreference()
  const { personaId, changePersona, activeDialect, selectedDialect, changeDialect, supportedDialects, hasNativeVoice } = useVoicePersona()

  // 语音授权（合规）
  const { showDialog: showConsent, hasConsent, requestConsent, grantConsent, denyConsent } = useVoiceConsent()

  // 语音唤醒（design/28 §1.1）：单独授权 + 开关持久化（A4：收敛为 useWakeEnabled 单一来源）
  const { enabled: wakeEnabled, setEnabled: setWakeEnabled } = useWakeEnabled()
  const wakeConsent = useVoiceCallConsent()

  // TTS 播放器（语速根据性别微调：男生稍快、女生稍慢）
  const userGender = getUser()?.gender
  const tts = useTtsPlayer({
    persona: personaId,
    emotion: 'neutral',
    speed: userGender === 'male' ? 1.05 : userGender === 'female' ? 0.95 : 1.0,
    dialect: activeDialect,
  })

  // 会话状态（UX-006：消息/发送/SSE 编排收敛 useChatSession，design/17 §chat/hooks）
  const { messages, setMessages, input, setInput, streaming, sendMessage, sendMessageRef, closeSession } = useChatSession({
    sessionId: session.sessionId,
    greeting: session.greeting,
    emotionTag: session.emotionTag,
    tts,
    wakeEnabled,
    bobo: boboExpression,
    // recordInteraction 定义于下方 useSilenceNudge → ref 代理防 TDZ 与闭包过期
    onInteraction: () => recordInteractionRef.current?.(),
    // 发送前：打断朗读标记 + 释放麦克风（安卓路由保护，design/27 §5.1）
    onBeforeSend: () => {
      setSpeakingMsgIdx(-1)
      releaseStream()
    },
    onClosed: onEnd,
  })

  // 进入聊天室自动朗读打招呼语
  // （此时仍处于"开始聊天"点击的用户激活窗口内，unlock 可成功预热音频元素）
  useEffect(() => {
    if (greetingSpokenRef.current) return // StrictMode 防重复
    greetingSpokenRef.current = true
    if (session.greeting && !tts.muted) {
      tts.unlock()
      tts.speak(session.greeting)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // TTS 引擎不可用提示（安卓 Pad 无 Google 语音引擎时显示友好提示，而非系统报错）
  useEffect(() => {
    if (tts.engine === 'none') {
      setVoiceNotice('当前浏览器不支持语音播放，可阅读文字内容 📖')
      // AUD-017：定时器挂 cleanup，卸载/引擎变化时清除避免 setState-after-unmount
      const t = setTimeout(() => setVoiceNotice(''), 6000)
      return () => clearTimeout(t)
    }
  }, [tts.engine])

  // DC-012：唤醒授权联动抽离（SPEC §26）——首次进入未授权自动弹窗（合规 design/28 §1.4）；已授权预加载模型
  useWakeConsentFlow({
    enabled: wakeEnabled,
    hasConsent: () => wakeConsent.hasConsent(),
    requestConsent: () => wakeConsent.requestConsent(),
    onPreload: preloadWakeModel,
  })



  /** 语音输入流水线（ARCH-006 doing/66 §3.1：录音→分析→自动发送整链收敛，ChatRoom 只做装配） */
  const pipeline = useVoiceInputPipeline({
    onTranscription: (text, emotion) => {
      // 自动发送：失败时回填输入框防丢字（与改造前 handleRecordingComplete 一致）
      sendMessageRef.current?.(text, emotion).then((sent) => { if (!sent) setInput(text) })
    },
  })
  const { isRecording: recording, isAnalyzing: analyzing, supported, liveTranscript, error: pipelineError, warmUp: warmUpMic, releaseStream } = pipeline

  // pipeline 语音提示文案 → 顶部提示条（自动定时清空）
  useEffect(() => {
    if (pipelineError) {
      setVoiceNotice(pipelineError)
      const t = setTimeout(() => setVoiceNotice(''), 4000)
      return () => clearTimeout(t)
    }
  }, [pipelineError])

  /* ===== 语音唤醒状态机（design/28 §1.1）：off / standby（待唤醒）/ active（会话窗）
     监听严格限定在本次对话内：仅 ChatRoom 挂载期间由 enabled 控制，卸载即释放麦克风 ===== */
  const voiceCall = useVoiceCallMode({
    enabled: wakeEnabled && wakeConsent.hasConsent(),
    tts,
    busy: streaming || tts.playing || recording || analyzing,
    onFinalTranscript: (text) => {
      // 唤醒后孩子说话 → 走与按住说话相同的自动发送流程
      // 失败时回填输入框防丢字（与 handleRecordingComplete 保持一致）
      sendMessageRef.current?.(text, null).then((sent) => {
        if (!sent) setInput(text)
      })
    },
  })

  /* ===== 冷场引导（design/28 §2.3）：孩子长时间沉默时，后端决策模型决定“留白还是暖场”
     唤醒模式开启时不做冷场检测——沉默由会话窗冷却关窗处理（design/28 三功能协同） ===== */
  const { recordInteraction, resetSilenceBase } = useSilenceNudge({
    sessionId: session.sessionId,
    // AI 忙碌（流式/录音/识别/朗读）或静音时不做冷场检测；唤醒模式（standby/active）时互斥
    idle: !streaming && !recording && !analyzing && !tts.playing && !tts.muted && voiceCall.mode === 'off',
    onNudge: (text) => {
      // 暖场回复：追加 AI 消息气泡 + TTS 朗读（复用现有体验，跟随所选音色）
      setMessages((prev) => [...prev, { role: 'assistant', content: text, emotion: session.emotionTag }])
      if (!tts.muted) tts.speak(text)
    },
  })

  // 供 useChatSession.onInteraction 调用（ref 代理，避免渲染期交叉依赖）
  const recordInteractionRef = useRef(recordInteraction)
  useEffect(() => {
    recordInteractionRef.current = recordInteraction
  })

  // AI 活动结束（回复流/朗读完毕）→ 从此刻起算沉默
  // 唤醒开关切换时也重置（用户操作设置面板不算“沉默”，避免关闭唤醒后立即触发 nudge）
  useEffect(() => {
    resetSilenceBase()
  }, [streaming, tts.playing, wakeEnabled, resetSilenceBase])

  // DC-012：安卓音频路由保护抽离（SPEC §26）——播放中释放麦克风；结束 600ms 预热（userInteracted 联动在 hook 内部）
  useAndroidAudioRouting({
    playing: tts.playing,
    micWanted: hasConsent() && wakeEnabled,
    releaseStream,
    warmUpMic,
  })



  /* ===== 按住说话（微信同款）：按下录音、松开发送、上滑取消 ===== */

  /** 按下：开始录音（含授权检查；未授权则弹授权框，暂不录音） */
  const handleVoicePointerDown = useCallback((e) => {
    if (streaming || analyzing || recording) return
    if (!requestConsent()) return
    tts.stop() // 打断播放：用户按住麦克风要说话时 AI 应立即停读（也避免录音期间 TTS 被路由到听筒）
    e.currentTarget.setPointerCapture(e.pointerId)
    pointerStartYRef.current = e.clientY
    setCancelArmed(false)
    pipeline.start() // 并行启动录音 + 浏览器识别（降级转写）
  }, [streaming, analyzing, recording, requestConsent, pipeline.start, tts.stop])

  /** 移动：上滑超过阈值进入取消态（松手即取消） */
  const handleVoicePointerMove = useCallback((e) => {
    if (!recording) return
    setCancelArmed(pointerStartYRef.current - e.clientY > 60)
  }, [recording])

  /** 松开：三分支——上滑取消 / 说话过短 / 正常发送（识别后自动发出） */
  const handleVoicePointerUp = useCallback(() => {
    if (!recording) return
    if (cancelArmed) {
      pipeline.cancel()
      setVoiceNotice('已取消')
      setTimeout(() => setVoiceNotice(''), 2000)
    } else {
      pipeline.stop() // 过短判定在 pipeline 内（<1000ms 自动取消 + 提示）
    }
    setCancelArmed(false)
  }, [recording, cancelArmed, pipeline.cancel, pipeline.stop])

  /** 系统打断 / 指针捕获丢失：安全取消本次录音，避免卡在录音态 */
  const handleVoicePointerCancel = useCallback(() => {
    if (!recording) return
    pipeline.cancel()
    setCancelArmed(false)
  }, [recording, pipeline.cancel])

  /** 授权通过：预热麦克风（下次按住秒开）；按住说话模式下不自动录音，需用户再次按住 */
  const handleConsentGrant = useCallback(async () => {
    grantConsent()
    await warmUpMic() // 先拿到麦克风流，首次录音也能秒开，避免漏录开头
    setVoiceNotice('按住麦克风开始说话 🎤')
    setTimeout(() => setVoiceNotice(''), 3000)
  }, [grantConsent, warmUpMic])

  /** 语音唤醒开关：开启时若未单独授权，先弹授权弹窗（授权通过后才真正开启） */
  const handleToggleWake = useCallback(() => {
    if (wakeEnabled) {
      setWakeEnabled(false)
      // 关闭唤醒时释放预热的麦克风流（消除浏览器录音指示器）
      releaseStream()
    } else if (wakeConsent.requestConsent()) {
      setWakeEnabled(true)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wakeEnabled])

  /** 唤醒授权通过：记录授权 + 开启开关（进入待唤醒态） */
  const handleWakeConsentGrant = useCallback(() => {
    wakeConsent.grantConsent()
    setWakeEnabled(true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  /** 重播单条消息 */
  const handleReplay = useCallback((text, idx) => {
    setSpeakingMsgIdx(idx)
    releaseStream() // 释放麦克风，避免安卓把重播音频路由到听筒（合成窗口内完成扬声器切回）
    tts.speakSentence(text).then(() => setSpeakingMsgIdx(-1))
  }, [tts, releaseStream])

  const [showSatisfaction, setShowSatisfaction] = useState(false)

  const handleEnd = () => {
    tts.stop()
    setShowSatisfaction(true)
  }

  /* ===== 波波状态机（design/27 §4.3 + design/28 §1.1）：
     recording > streaming > tts.playing > 待唤醒(standby) > 会话窗聆听(active) > idle
     DC-012：规则抽离到 computeBoboState 纯函数（SPEC §26） ===== */
  const boboState = computeBoboState({
    recording,
    streaming,
    playing: tts.playing,
    wakeMode: voiceCall.mode,
  })

  /* ===== 波波宠物（手机悬浮输入栏右上角 / Pad 左栏共用）— 按住说话 ===== */
  // 唤醒 active 模式下不展示残留转写，气泡显示聆听提示
  const effectiveTranscript = voiceCall.mode === 'active' ? '' : liveTranscript
  const boBoPet = (size, bubbleAlign = 'center') => (
    <BoBoPet
      state={boboState}
      expression={boboExpression.expression}
      motionOff={!motion.animationEnabled}
      colors={theme.bobo}
      sentenceText=""
      liveTranscript={effectiveTranscript}
      size={size}
      interactive={supported}
      cancelArmed={cancelArmed}
      disabled={streaming || analyzing}
      bubbleAlign={bubbleAlign}
      onPointerDown={handleVoicePointerDown}
      onPointerMove={handleVoicePointerMove}
      onPointerUp={handleVoicePointerUp}
      onPointerCancel={handleVoicePointerCancel}
    />
  )

  return (
    <div className="h-screen flex flex-col" style={{ background: 'linear-gradient(to bottom, var(--bg-start), var(--bg-end))' }}>
      {/* ===== Header ===== */}
      <header className="flex items-center justify-between px-4 lg:px-8 py-3 lg:py-4 bg-white/80 backdrop-blur border-b border-gray-100 shadow-sm">
        <div className="flex items-center gap-2 lg:gap-3">
          <BoBoAvatar size={24} colors={theme.bobo} />
          <span className="font-medium text-gray-800 lg:text-xl">{theme.companionName}</span>
        </div>
        <div className="flex items-center gap-2">
          {/* TTS 静音快捷按钮 */}
          <button
            onClick={tts.toggleMute}
            className={`p-2 lg:p-2.5 rounded-full transition-colors ${
              tts.muted ? 'text-gray-300' : 'text-[var(--primary)]'
            }`}
            title={tts.muted ? '开启语音' : '关闭语音'}
          >
            {tts.muted ? (
              <svg className="w-5 h-5 lg:w-6 lg:h-6" fill="currentColor" viewBox="0 0 24 24">
                <path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"/>
              </svg>
            ) : (
              <svg className="w-5 h-5 lg:w-6 lg:h-6" fill="currentColor" viewBox="0 0 24 24">
                <path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/>
              </svg>
            )}
          </button>
          {/* SOS 常驻入口（design/36 §3.4：非埋藏在菜单里，暖色不制造焦虑） */}
          <button
            onClick={() => setSosOpen(true)}
            className="px-3 py-1.5 lg:px-3.5 lg:py-2 rounded-full bg-rose-50 border border-rose-200 text-rose-500 hover:bg-rose-100 active:scale-95 transition-all"
            title="SOS 帮助"
          >
            <span className="text-sm lg:text-base font-semibold">🆘</span>
          </button>
          {/* 百宝箱入口（design/36 §3.1） */}
          <button
            onClick={() => setToolboxOpen(true)}
            className="p-2 lg:p-2.5 rounded-full text-gray-400 hover:text-[var(--primary)] transition-colors"
            title="百宝箱"
          >
            <span className="text-lg lg:text-xl">🧰</span>
          </button>
          {/* 设置按钮 */}
          <button
            onClick={() => setSettingsOpen(true)}
            className="p-2 lg:p-2.5 rounded-full text-gray-400 hover:text-[var(--primary)] transition-colors"
            title="设置"
          >
            <svg className="w-5 h-5 lg:w-6 lg:h-6" fill="currentColor" viewBox="0 0 24 24">
              <path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/>
            </svg>
          </button>
          {/* 切换同学（与设置并排，共享 Pad 场景） */}
          {onSwitchUser && (
            <button
              onClick={() => setConfirmSwitch(true)}
              className="flex items-center gap-1 px-3 py-1.5 lg:px-3.5 lg:py-2 rounded-full bg-orange-50 border border-orange-200 text-orange-600 hover:bg-orange-100 active:scale-95 transition-all"
              title="切换同学"
            >
              <span className="text-sm lg:text-base">🔄</span>
              <span className="text-xs lg:text-sm font-semibold">换人</span>
            </button>
          )}
          {/* 结束对话 */}
          <button
            onClick={handleEnd}
            className="text-sm lg:text-base px-4 py-2 lg:px-6 lg:py-3 rounded-full border border-gray-200
              text-gray-500 hover:text-red-500 hover:border-red-200 transition-colors"
          >
            结束
          </button>
        </div>
      </header>

      {/* ===== 主体：手机单栏 / Pad 双栏 ===== */}
      <div className="flex-1 flex overflow-hidden">

        {/* Pad 左栏：波波（伙伴 + 语音输入合一，design/27 §5.1） */}
        <aside className="hidden lg:flex flex-col items-center justify-center w-[340px] xl:w-[400px]
          border-r border-gray-100/50 p-8"
          style={{ background: 'linear-gradient(to bottom, var(--primary-light), var(--bg-end))' }}>
          {/* 波波（按住说话） */}
          <div className="mb-10">{boBoPet(170)}</div>
          <p className="text-lg" style={{ color: 'var(--primary)' }}>
            {recording ? '我在认真听你说...'
              : analyzing ? '我在感受你的情绪...'
              : streaming ? '让我想想...'
              : tts.playing ? '我在说给你听...'
              : voiceCall.wakeStatus === 'detected' ? '听到了！🎉'
              : voiceCall.wakeStatus === 'loading' ? '语音引擎加载中...'
              : voiceCall.wakeStatus === 'error' ? '语音引擎未就绪'
              : voiceCall.mode === 'standby' ? '叫我“哈喽波波”'
              : voiceCall.mode === 'active' ? '我在听，直接说吧'
              : '想说什么就说什么吧'}
          </p>
          <p className="mt-3 text-sm text-gray-400">
            {recording ? '松开手指发送，上滑取消'
              : voiceCall.wakeStatus === 'detected' ? '正在准备听你说话...'
              : voiceCall.mode === 'standby'
                ? (voiceCall.wakeStatus === 'loading' ? '正在加载语音引擎...' :
                   voiceCall.wakeStatus === 'listening' ? '我在这里安静地等你叫我' :
                   voiceCall.wakeStatus === 'error' ? '语音引擎加载失败，请关闭再开启' :
                   '我在这里安静地等你叫我')
              : voiceCall.mode === 'active' ? '不用按，直接说就行'
              : '按住波波，跟它说说话'}
          </p>
        </aside>

        {/* 右栏（手机为全宽）：对话区 */}
        <div className="flex-1 flex flex-col min-w-0">
          {/* 消息列表 */}
          <main className="flex-1 overflow-y-auto p-4 lg:p-8 space-y-4 lg:space-y-5">
            {messages.map((msg, i) => (
              <MessageBubble
                key={i}
                msg={msg}
                isLast={i === messages.length - 1}
                streaming={streaming}
                onReplay={(text) => handleReplay(text, i)}
                isSpeaking={speakingMsgIdx === i || (tts.playing && i === messages.length - 1 && msg.role === 'assistant')}
              />
            ))}
            <div ref={bottomRef} />
          </main>

          {/* 输入区 */}
          <footer className="p-4 lg:px-8 lg:py-5 bg-white/80 backdrop-blur border-t border-gray-100">
            {/* 手机端语音唤醒状态指示器（Pad 在左栏已有，手机无左栏故需单独展示） */}
            {wakeEnabled && wakeConsent.hasConsent() && voiceCall.mode !== 'off' && !recording && !analyzing && (
              <div className="flex lg:hidden items-center justify-center gap-2 mb-3 text-xs">
                {voiceCall.wakeStatus === 'loading' && (
                  <span className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-blue-50 text-blue-500">
                    <span className="relative flex h-2 w-2">
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75"></span>
                      <span className="relative inline-flex rounded-full h-2 w-2 bg-blue-500"></span>
                    </span>
                    语音引擎加载中...
                  </span>
                )}
                {voiceCall.wakeStatus === 'listening' && voiceCall.mode === 'standby' && (
                  <span className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-green-50 text-green-600">
                    <span className="relative flex h-2 w-2">
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
                      <span className="relative inline-flex rounded-full h-2 w-2 bg-green-500"></span>
                    </span>
                    说“哈喽波波”唤醒我
                  </span>
                )}
                {voiceCall.wakeStatus === 'detected' && (
                  <span className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-amber-50 text-amber-600">
                    🎉 听到了！正在准备听你说话...
                  </span>
                )}
                {voiceCall.mode === 'active' && voiceCall.wakeStatus !== 'detected' && (
                  <span className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-green-50 text-green-600">
                    <span className="relative flex h-2 w-2">
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
                      <span className="relative inline-flex rounded-full h-2 w-2 bg-green-500"></span>
                    </span>
                    我在听，直接说吧
                  </span>
                )}
                {voiceCall.wakeStatus === 'error' && (
                  <span className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-red-50 text-red-500">
                    语音引擎加载失败，可在设置中重试
                  </span>
                )}
              </div>
            )}
            {/* 语音降级提示 */}
            {voiceNotice && (
              <div className="flex items-center justify-center gap-2 mb-3 px-4 py-2 rounded-xl bg-amber-50 text-amber-700 text-sm">
                <span>💡</span>
                <span>{voiceNotice}</span>
              </div>
            )}

            {/* 手机端识别状态（录音态由全屏遮罩接管） */}
            {analyzing && (
              <div className="flex lg:hidden items-center justify-center gap-2 mb-3 text-sm" style={{ color: 'var(--primary)' }}>
                <span className="relative flex h-3 w-3">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full opacity-75" style={{ background: 'var(--primary)' }}></span>
                  <span className="relative inline-flex rounded-full h-3 w-3" style={{ background: 'var(--primary)' }}></span>
                </span>
                正在识别，马上发送...
              </div>
            )}

            <div className="relative flex gap-3 max-w-lg lg:max-w-2xl mx-auto items-center">
              <input
                value={input}
                onChange={(e) => {
                  setInput(e.target.value)
                  // 学生输入中：波波侧耳倾听（design/37 §4.1）
                  if (e.target.value) boboExpression.dispatch({ type: 'typing' })
                }}
                onKeyDown={(e) => e.key === 'Enter' && !e.shiftKey && sendMessage()}
                placeholder={recording ? '正在录音...' : analyzing ? '分析中...' : '也可以打字告诉我'}
                disabled={streaming || analyzing}
                className="flex-1 px-5 py-3.5 lg:py-4 rounded-full border border-gray-200 focus:outline-none
                  focus:ring-2 text-sm lg:text-lg disabled:bg-gray-50"
                style={{ '--tw-ring-color': 'var(--primary-light)' } as React.CSSProperties}
              />
              <button
                onClick={() => sendMessage()}
                disabled={!input.trim() || streaming || analyzing}
                className="flex-shrink-0 px-6 lg:px-10 py-3.5 lg:py-4 rounded-full text-white
                  text-sm lg:text-lg font-medium active:scale-95
                  disabled:bg-gray-300 disabled:cursor-not-allowed transition-all"
                style={{ background: input.trim() && !streaming ? 'var(--primary)' : undefined }}
              >
                发送
              </button>
            </div>
          </footer>
        </div>
      </div>

      {/* 手机端可拖拽悬浮语音按钮（design/27 §5.4） */}
      {supported && (
        <div className="lg:hidden">
          <DraggableVoiceButton
            disabled={streaming || analyzing}
            onPointerDown={handleVoicePointerDown}
            onPointerMove={handleVoicePointerMove}
            onPointerUp={handleVoicePointerUp}
            onPointerCancel={handleVoicePointerCancel}
          >
            {(side) => boBoPet(60, side)}
          </DraggableVoiceButton>
        </div>
      )}

      {/* 语音授权弹窗（合规） */}
      {showConsent && (
        <VoiceConsentDialog onGrant={handleConsentGrant} onDeny={denyConsent} />
      )}

      {/* 语音唤醒单独授权弹窗（合规，design/28 §1.4） */}
      {wakeConsent.showDialog && (
        <VoiceCallConsentDialog onGrant={handleWakeConsentGrant} onDeny={wakeConsent.denyConsent} />
      )}

      {/* 结束会话满意度评价 */}
      {showSatisfaction && (
        <SatisfactionDialog
          onSubmit={(rating, comment) => { setShowSatisfaction(false); closeSession(rating, comment) }}
          onSkip={() => { setShowSatisfaction(false); closeSession(null) }}
          onResume={() => setShowSatisfaction(false)}
        />
      )}

      {/* 设置面板 */}
      <SettingsPanel
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        muted={tts.muted}
        onToggleMute={tts.toggleMute}
        wakeSupported={voiceCall.wakeSupported}
        wakeOn={wakeEnabled}
        onToggleWake={handleToggleWake}
        personaId={personaId}
        onPersonaChange={changePersona}
        selectedDialect={selectedDialect}
        onDialectChange={changeDialect}
        supportedDialects={supportedDialects}
        hasNativeVoice={hasNativeVoice}
      />
      {/* 百宝箱与 SOS 面板（F-2，design/36） */}
      {toolboxOpen && <ToolboxPanel onBack={() => setToolboxOpen(false)} />}
      {sosOpen && <SosPanel onBack={() => setSosOpen(false)} />}
      {/* 切换同学二次确认 */}
      <ConfirmDialog
        open={confirmSwitch}
        emoji="👋"
        title="要退出让别的同学用吗？"
        message="退出后需要重新登录哦"
        confirmText="确认退出"
        danger
        onConfirm={() => { setConfirmSwitch(false); onSwitchUser?.() }}
        onCancel={() => setConfirmSwitch(false)}
      />
    </div>
  )
}
