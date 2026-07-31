/**
 * 波波话语气泡（design/27 §4.4）
 * - speaking：显示 TTS 正在朗读的那一句（逐句滚动）
 * - thinking：显示 ••• 思考泡泡
 * - listening：显示实时转写（cancelArmed 时变红提示“松开手指，取消发送”）
 * - 动效：弹出（scale 0.8→1 + 淡入）+ 说话时轻微律动
 */

export default function SpeechBubble({ mode, text, cancelArmed = false, align = 'center' }) {
  // mode: 'speaking' | 'thinking' | 'listening' | null
  // align: 'center'（Pad 左栏，居中）| 'right'（手机悬浮，右对齐向左展开，避免溢出屏幕右缘）
  if (!mode) return null
  const rightAligned = align === 'right'

  return (
    <div
      className={`absolute bottom-full mb-2 z-20 pointer-events-none select-none
        ${rightAligned ? 'right-0' : 'left-1/2 -translate-x-1/2'}`}
      style={{ width: 'max-content', maxWidth: '220px' }}
    >
      {/* 气泡主体 */}
      <div
        className={`relative rounded-2xl shadow-lg border px-3.5 py-2.5
          origin-bottom animate-[bubble-in_0.25s_ease-out]
          ${mode === 'listening' && cancelArmed
            ? 'bg-red-500 border-red-400'
            : 'bg-white border-gray-100'}
          ${mode === 'speaking' ? 'animate-[bubble-bob_1.6s_ease-in-out_infinite]' : ''}`}
      >
        {mode === 'thinking' ? (
          <div className="flex items-center justify-center gap-1 py-0.5">
            <span className="w-1.5 h-1.5 rounded-full bg-gray-400 animate-bounce" style={{ animationDelay: '0ms' }} />
            <span className="w-1.5 h-1.5 rounded-full bg-gray-400 animate-bounce" style={{ animationDelay: '150ms' }} />
            <span className="w-1.5 h-1.5 rounded-full bg-gray-400 animate-bounce" style={{ animationDelay: '300ms' }} />
          </div>
        ) : mode === 'listening' && cancelArmed ? (
          <p className="text-[13px] leading-relaxed text-white text-center font-medium whitespace-nowrap">
            松开手指，取消发送
          </p>
        ) : (
          <p className={`text-[13px] leading-relaxed text-center break-words ${mode === 'listening' ? 'text-gray-600' : 'text-gray-700'}`}>
            {mode === 'listening' ? (text || '正在聆听…') : (text || <span className="inline-flex gap-0.5 items-center justify-center py-0.5"><span className="w-1.5 h-1.5 rounded-full bg-gray-400 animate-bounce" style={{ animationDelay: '0ms' }} /><span className="w-1.5 h-1.5 rounded-full bg-gray-400 animate-bounce" style={{ animationDelay: '150ms' }} /><span className="w-1.5 h-1.5 rounded-full bg-gray-400 animate-bounce" style={{ animationDelay: '300ms' }} /></span>)}
          </p>
        )}
        {/* 指向头部的小尾巴 */}
        <div className={`absolute -bottom-1.5 w-3 h-3 rotate-45 border-b border-r
          ${rightAligned ? 'right-5' : 'left-1/2 -translate-x-1/2'}
          ${mode === 'listening' && cancelArmed ? 'bg-red-500 border-red-400' : 'bg-white border-gray-100'}`} />
      </div>

      {/* 气泡动画 keyframes（内联注入，避免依赖全局 CSS 配置） */}
      <style>{`
        @keyframes bubble-in {
          from { opacity: 0; transform: scale(0.8) translateY(4px); }
          to   { opacity: 1; transform: scale(1) translateY(0); }
        }
        @keyframes bubble-bob {
          0%, 100% { transform: translateY(0); }
          50%      { transform: translateY(-2px); }
        }
      `}</style>
    </div>
  )
}
