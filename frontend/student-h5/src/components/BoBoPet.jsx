/**
 * 波波宠物（design/27 §四/§五）
 * - 纯 SVG 逐部件可动画海豚角色，随主题换色
 * - 四态状态机：idle 漂浮眨眼 / listening 蜷成发光圆球 / thinking 思考 / speaking 说话冒气泡
 * - 波波即语音输入圆球：interactive 模式下按住说话（复用 ChatRoom 录音 handlers）
 * - 触感反馈：Android vibrate，iOS 降级为视觉挤压
 */
import SpeechBubble from './SpeechBubble'

/** 触感反馈（iOS Safari 不支持 vibrate，自动降级为纯视觉） */
function vibrate(pattern) {
  try { navigator.vibrate?.(pattern) } catch { /* ignore */ }
}

export default function BoBoPet({
  state = 'idle',            // 'idle' | 'listening' | 'thinking' | 'speaking'
  colors = { body: '#38BDF8', belly: '#E0F2FE', fin: '#0284C7' },
  sentenceText = '',         // speaking 时气泡展示的句子
  liveTranscript = '',       // listening 时气泡展示的实时转写
  size = 72,                 // 容器边长 px
  interactive = false,       // 是否作为语音输入入口（按住说话）
  cancelArmed = false,       // 上滑取消态（圆球变红）
  disabled = false,
  bubbleAlign = 'center',    // 气泡对齐：'center'（Pad）| 'right'（手机悬浮，向左展开防溢出）
  onPointerDown,
  onPointerMove,
  onPointerUp,
  onPointerCancel,
  className = '',
}) {
  const bubbleMode = state === 'listening' ? 'listening'
    : state === 'speaking' ? 'speaking'
    : state === 'thinking' ? 'thinking'
    : null
  const bubbleText = state === 'listening' ? liveTranscript : sentenceText
  const listening = state === 'listening'

  const handleDown = (e) => {
    if (!interactive || disabled) return
    vibrate(10)
    onPointerDown?.(e)
  }
  const handleUp = (e) => {
    if (!interactive || disabled) return
    vibrate(10)
    onPointerUp?.(e)
  }
  const handleCancel = (e) => {
    if (!interactive) return
    vibrate([20, 30, 20])
    onPointerCancel?.(e)
  }

  return (
    <div
      className={`relative select-none ${interactive ? 'cursor-pointer' : ''} ${className}`}
      style={{ width: size, height: size, touchAction: interactive ? 'none' : undefined }}
      onPointerDown={interactive ? handleDown : undefined}
      onPointerMove={interactive ? onPointerMove : undefined}
      onPointerUp={interactive ? handleUp : undefined}
      onPointerCancel={interactive ? handleCancel : undefined}
      onLostPointerCapture={interactive ? handleCancel : undefined}
      role={interactive ? 'button' : undefined}
      aria-label={interactive ? '按住波波说话' : '波波'}
    >
      {/* 话语气泡 / 思考泡泡 / 实时转写（头顶） */}
      <SpeechBubble mode={bubbleMode} text={bubbleText} cancelArmed={cancelArmed} align={bubbleAlign} />

      {/* ===== 海豚形态（非 listening 状态） ===== */}
      <div
        className={`absolute inset-0 flex items-center justify-center transition-all duration-300
          ${listening ? 'opacity-0 scale-50 pointer-events-none' : 'opacity-100 scale-100'}
          ${state === 'idle' ? 'animate-[bobo-float_2.6s_ease-in-out_infinite]' : ''}
          ${state === 'thinking' ? 'animate-[bobo-sway_2s_ease-in-out_infinite]' : ''}
          ${state === 'speaking' ? 'animate-[bobo-groove_1.4s_ease-in-out_infinite]' : ''}`}
      >
        <svg viewBox="0 0 200 160" className="w-full h-full overflow-visible">
          {/* 尾鳍（两瓣，可摆动） */}
          <g
            className="animate-[bobo-tail_1.3s_ease-in-out_infinite]"
            style={{ transformBox: 'fill-box', transformOrigin: '90% 50%' }}
          >
            <path d="M 38 76 C 26 62, 12 54, 4 58 C 8 70, 18 80, 32 84 Z" fill={colors.fin} />
            <path d="M 38 86 C 26 98, 14 106, 7 102 C 10 92, 22 84, 34 82 Z" fill={colors.fin} />
          </g>

          {/* 背鳍 */}
          <path d="M 102 35 C 104 18, 120 8, 132 11 C 125 23, 121 31, 119 37 Z" fill={colors.fin} />

          {/* 身体（水滴形，头右尾左） */}
          <path
            d="M 178 72 C 178 48, 148 30, 112 33 C 78 36, 48 52, 34 72 C 29 79, 30 86, 37 90 C 55 104, 90 113, 124 110 C 158 107, 178 96, 178 72 Z"
            fill={colors.body}
          />

          {/* 肚皮（浅色） */}
          <path d="M 48 92 C 70 105, 115 111, 158 99 C 145 108, 105 113, 75 107 C 62 104, 53 98, 48 92 Z" fill={colors.belly} />

          {/* 呼吸孔 */}
          <ellipse cx="158" cy="37" rx="4" ry="2" fill={colors.fin} opacity="0.5" />

          {/* 胸鳍（可扇动） */}
          <path
            d="M 118 82 C 112 96, 100 105, 92 102 C 98 92, 108 84, 116 79 Z"
            fill={colors.fin}
            className={state === 'listening' ? '' : 'animate-[bobo-fin_2s_ease-in-out_infinite]'}
            style={{ transformBox: 'fill-box', transformOrigin: '80% 20%' }}
          />

          {/* 眼睛（可眨眼；thinking 时瞳孔看天） */}
          <g
            className="animate-[bobo-blink_4.5s_ease-in-out_infinite]"
            style={{ transformBox: 'fill-box', transformOrigin: 'center' }}
          >
            <circle cx="148" cy="60" r="13" fill="#FFFFFF" />
            <circle
              cx="151"
              cy={state === 'thinking' ? 56 : 62}
              r="6.5"
              fill="#0F172A"
              style={{ transition: 'cy 0.3s' }}
            />
            <circle cx="153" cy="58" r="2.5" fill="#FFFFFF" />
          </g>

          {/* 腮红 */}
          <ellipse cx="157" cy="80" rx="9" ry="5.5" fill="#FDA4AF" opacity="0.75" />

          {/* 嘴巴：说话时开合椭圆，否则微笑曲线 */}
          {state === 'speaking' ? (
            <ellipse
              cx="167"
              cy="81"
              rx="6"
              ry="4"
              fill="#0369A1"
              className="animate-[bobo-mouth_0.5s_ease-in-out_infinite]"
              style={{ transformBox: 'fill-box', transformOrigin: 'center' }}
            />
          ) : (
            <path d="M 176 76 C 172 82, 164 84, 157 82" stroke="#0369A1" strokeWidth="4" fill="none" strokeLinecap="round" />
          )}
        </svg>
      </div>

      {/* ===== 圆球形态（listening：蜷成的发光圆球 + 声波纹） ===== */}
      {listening && (
        <div className="absolute inset-0 flex items-center justify-center">
          {/* 声波纹（涟漪扩散） */}
          <span className="absolute rounded-full animate-[bobo-ripple_1.5s_ease-out_infinite]"
            style={{ width: size * 0.72, height: size * 0.72, border: `2px solid ${cancelArmed ? '#F87171' : colors.body}` }} />
          <span className="absolute rounded-full animate-[bobo-ripple_1.5s_ease-out_infinite]"
            style={{ width: size * 0.72, height: size * 0.72, border: `2px solid ${cancelArmed ? '#F87171' : colors.body}`, animationDelay: '0.5s' }} />
          {/* 球体 */}
          <div
            className={`rounded-full animate-[bobo-pulse_1.1s_ease-in-out_infinite] transition-colors duration-200`}
            style={{
              width: size * 0.68,
              height: size * 0.68,
              background: cancelArmed
                ? 'radial-gradient(circle at 35% 30%, #FCA5A5, #EF4444)'
                : `radial-gradient(circle at 35% 30%, ${colors.belly}, ${colors.body})`,
              boxShadow: cancelArmed
                ? '0 0 24px rgba(239,68,68,0.55)'
                : `0 0 24px ${colors.body}88`,
            }}
          />
        </div>
      )}

      {/* 待机呼吸光晕（暗示可触碰） */}
      {interactive && state === 'idle' && !disabled && (
        <span
          className="absolute inset-0 rounded-full animate-[bobo-halo_2.4s_ease-in-out_infinite] pointer-events-none"
          style={{ boxShadow: `0 0 0 6px ${colors.body}22` }}
        />
      )}

      {/* 动画 keyframes */}
      <style>{`
        @keyframes bobo-float { 0%,100% { transform: translateY(0); } 50% { transform: translateY(-5px); } }
        @keyframes bobo-sway  { 0%,100% { transform: rotate(-2deg); } 50% { transform: rotate(2deg); } }
        @keyframes bobo-groove{ 0%,100% { transform: translateY(0) rotate(-1.5deg); } 50% { transform: translateY(-3px) rotate(1.5deg); } }
        @keyframes bobo-tail  { 0%,100% { transform: rotate(-9deg); } 50% { transform: rotate(9deg); } }
        @keyframes bobo-fin   { 0%,100% { transform: rotate(0deg); } 50% { transform: rotate(-12deg); } }
        @keyframes bobo-blink { 0%,90%,100% { transform: scaleY(1); } 93% { transform: scaleY(0.08); } 96% { transform: scaleY(1); } }
        @keyframes bobo-mouth { 0%,100% { transform: scaleY(0.35); } 50% { transform: scaleY(1); } }
        @keyframes bobo-ripple{ 0% { transform: scale(0.85); opacity: 0.85; } 100% { transform: scale(1.7); opacity: 0; } }
        @keyframes bobo-pulse { 0%,100% { transform: scale(1); } 50% { transform: scale(1.07); } }
        @keyframes bobo-halo  { 0%,100% { opacity: 0.5; transform: scale(1); } 50% { opacity: 1; transform: scale(1.06); } }
      `}</style>
    </div>
  )
}
