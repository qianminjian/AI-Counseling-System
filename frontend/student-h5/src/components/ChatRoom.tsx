import { useState, useRef, useEffect, useCallback } from 'react'
import ChatRoomHeader from './ChatRoomHeader'
import VoiceConsentDialog, { useVoiceConsent } from './VoiceConsentDialog'
import VoiceCallConsentDialog, { useVoiceCallConsent } from './VoiceCallConsentDialog'
import SatisfactionDialog from './SatisfactionDialog'
import SettingsPanel from './SettingsPanel'
import ConfirmDialog from './ConfirmDialog'
import ToolboxPanel from './ToolboxPanel'
import SosPanel from './SosPanel'
import BoBoPet from './BoBoPet'
import ModelDownloadProgress from './ModelDownloadProgress' // F-8：ChatRoom 也显示模型加载进度（与登录页一致）
import DraggableVoiceButton from './DraggableVoiceButton'
import MessageBubble from './MessageBubble'
// FA-14：语音状态 → 文案/指示器单一映射（mainHint/subHint/VoiceStatusChip）
import { mainHint, subHint, VoiceStatusChip, isWakeNotReady } from './VoiceStatusHint'
import { useTheme } from '../theme/ThemeProvider'
import { useVoicePersona } from '../hooks/useVoicePersona'
import { useTtsPlayer } from '../hooks/useTtsPlayer'
import { useVoiceCallMode } from '../hooks/useVoiceCallMode'
import { preloadWakeModel } from '../hooks/useWakeWord'
import { useWakeEnabled } from '../hooks/useWakeEnabled'
import { useSilenceNudge } from '../hooks/useSilenceNudge'
import { useVoiceInputPipeline } from '../hooks/useVoiceInputPipeline'
import { useChatSession } from '../hooks/useChatSession'
import { useChatRoomPanels } from '../hooks/useChatRoomPanels'
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
  // 面板/弹窗/提示条状态收敛（FA-06：useChatRoomPanels 统一管理，见 hooks/useChatRoomPanels.ts）
  const {
    settingsOpen, setSettingsOpen,
    toolboxOpen, setToolboxOpen,
    sosOpen, setSosOpen,
    confirmSwitch, setConfirmSwitch,
    showSatisfaction, setShowSatisfaction,
    voiceNotice, showNotice,
    speakingMsgIdx, setSpeakingMsgIdx,
  } = useChatRoomPanels()
  const [cancelArmed, setCancelArmed] = useState(false) // 按住说话：上滑进入取消态（手势状态，留在组件内）
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
  // AUD-017：定时器清理收敛 useChatRoomPanels（FA-06，新提示重置旧定时器）
  useEffect(() => {
    if (tts.engine === 'none') {
      showNotice('当前浏览器不支持语音播放，可阅读文字内容 📖', 6000)
    }
  }, [tts.engine, showNotice])

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

  // pipeline 语音提示文案 → 顶部提示条（FA-06：定时清空收敛 showNotice）
  useEffect(() => {
    if (pipelineError) {
      showNotice(pipelineError, 4000)
    }
  }, [pipelineError, showNotice])

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
      showNotice('已取消', 2000)
    } else {
      pipeline.stop() // 过短判定在 pipeline 内（<1000ms 自动取消 + 提示）
    }
    setCancelArmed(false)
  }, [recording, cancelArmed, pipeline.cancel, pipeline.stop, showNotice])

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
    showNotice('按住麦克风开始说话 🎤', 3000)
  }, [grantConsent, warmUpMic, showNotice])

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
      {/* F-8：模型加载进度（与登录页一致），进对话加载时显示百分比，避免"加载中"无感 */}
      <ModelDownloadProgress />
      {/* ===== Header（FA-06：拆出 ChatRoomHeader 子组件，纯展示 + 回调） ===== */}
      <ChatRoomHeader
        muted={tts.muted}
        onToggleMute={tts.toggleMute}
        onOpenSos={() => setSosOpen(true)}
        onOpenToolbox={() => setToolboxOpen(true)}
        onOpenSettings={() => setSettingsOpen(true)}
        onSwitchUser={onSwitchUser ? () => setConfirmSwitch(true) : undefined}
        onEnd={handleEnd}
      />

      {/* ===== 主体：手机单栏 / Pad 双栏 ===== */}
      <div className="flex-1 flex overflow-hidden">

        {/* Pad 左栏：波波（伙伴 + 语音输入合一，design/27 §5.1） */}
        <aside className="hidden lg:flex flex-col items-center justify-center w-[340px] xl:w-[400px]
          border-r border-gray-100/50 p-8"
          style={{ background: 'linear-gradient(to bottom, var(--primary-light), var(--bg-end))' }}>
          {/* 波波（按住说话） */}
          <div className="mb-10">{boBoPet(170)}</div>
          {/* F-29（2026-08-10 用户要求）：唤醒未就绪时图标下醒目提示（琥珀胶囊+呼吸点），
              明确"等会儿再叫我"——避免引擎仍在下载/初始化时用户过早呼叫无响应 */}
          {isWakeNotReady(voiceCall) ? (
            <div className="flex items-center gap-2 px-5 py-2.5 rounded-full bg-amber-50 border border-amber-200 shadow-sm">
              <span className="relative flex h-2.5 w-2.5">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-75"></span>
                <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-amber-500"></span>
              </span>
              <p className="text-base font-medium text-amber-600">
                正在准备语音引擎…等会儿再叫我哦
              </p>
            </div>
          ) : (
            <>
              <p className="text-lg" style={{ color: 'var(--primary)' }}>
                {recording ? '我在认真听你说...'
                  : analyzing ? '我在感受你的情绪...'
                  : streaming ? '让我想想...'
                  : tts.playing ? '我在说给你听...'
                  : mainHint(voiceCall)}
              </p>
              <p className="mt-3 text-sm text-gray-400">
                {recording ? '松开手指发送，上滑取消'
                  : subHint(voiceCall)}
              </p>
            </>
          )}
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
                <VoiceStatusChip voiceCall={voiceCall} />
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
        // BUG-S-04-01：麦克风授权状态传设置面板（拒绝授权时文案区分「未授权」）
        wakeAuthorized={wakeConsent.hasConsent()}
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
