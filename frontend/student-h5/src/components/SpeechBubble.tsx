/**
 * 波波吐泡泡话语气泡（design/27 §4.4，童趣化改版）
 * - speaking：TTS 正在朗读的句子装在"水泡"里，每句切换时从波波嘴边重新吹出
 * - thinking：三颗主题色小水泡依次上浮
 * - listening：实时转写（cancelArmed 时变红提示"松开手指，取消发送"，警示态不做童趣化）
 * - 视觉：白→主题浅色径向渐变水泡 + 左上高光斑 + 描边 + 递减小圆泡尾巴；
 *   文字用全局童趣圆体（GenSenRounded）17px 粗体、主题深色，随三主题联动
 * - 动效：吹泡入场（回弹）+ 说话时水中漂浮律动 + 两侧装饰小泡上浮
 */

export default function SpeechBubble({ mode, text, cancelArmed = false, align = 'center' }) {
  // mode: 'speaking' | 'thinking' | 'listening' | null
  // align: 'center'（Pad 左栏居中）| 'right'（球在右半屏，向左展开）| 'left'（球在左半屏，向右展开）
  if (!mode) return null
  const danger = mode === 'listening' && cancelArmed

  const wrapAlign = align === 'right' ? 'right-0'
    : align === 'left' ? 'left-0'
    : 'left-1/2 -translate-x-1/2'
  const tailAlign = align === 'right' ? 'right-5'
    : align === 'left' ? 'left-5'
    : 'left-1/2 -translate-x-2'
  const blowOrigin = align === 'right' ? 'origin-bottom-right'
    : align === 'left' ? 'origin-bottom-left'
    : 'origin-bottom'

  // 水泡皮肤：警示态纯红，其余为主题色水泡（渐变 + 描边随主题联动）
  const skin = danger
    ? { background: '#EF4444', borderColor: '#F87171' }
    : {
        background: 'radial-gradient(circle at 30% 22%, #FFFFFF 0%, var(--primary-light) 90%)',
        borderColor: 'var(--primary)',
      }

  return (
    <div
      className={`absolute bottom-full mb-3 z-20 pointer-events-none select-none ${wrapAlign}`}
      style={{ width: 'max-content', maxWidth: '260px' }}
    >
      {/* 水泡主体：key 换句即重新"吹出" */}
      <div
        key={mode === 'speaking' ? (text || 'speaking') : mode}
        className={`relative rounded-[26px] border-2 px-4 py-2.5 shadow-lg ${blowOrigin}
          ${mode === 'speaking'
            ? 'animate-[bubble-blow_0.35s_cubic-bezier(0.34,1.56,0.64,1),bubble-drift_2.2s_ease-in-out_0.35s_infinite]'
            : 'animate-[bubble-blow_0.35s_cubic-bezier(0.34,1.56,0.64,1)]'}`}
        style={skin}
      >
        {/* 水泡高光斑 */}
        {!danger && (
          <span className="absolute left-2.5 top-1.5 w-5 h-2.5 rounded-full bg-white/85 blur-[1px] rotate-[-18deg]" />
        )}

        {mode === 'thinking' ? (
          <div className="flex items-end justify-center gap-1.5 py-1">
            {[0, 1, 2].map(i => (
              <span
                key={i}
                className="rounded-full border-2 bg-white/80 animate-[think-rise_1.2s_ease-in-out_infinite]"
                style={{
                  width: 8 + i * 2, height: 8 + i * 2,
                  borderColor: 'var(--primary)',
                  animationDelay: `${i * 160}ms`,
                }}
              />
            ))}
          </div>
        ) : danger ? (
          <p className="text-[15px] leading-relaxed text-white text-center font-medium whitespace-nowrap">
            松开手指，取消发送
          </p>
        ) : mode === 'listening' ? (
          <p className="relative text-[15px] leading-relaxed text-center break-words font-medium"
            style={{ color: 'var(--primary-dark)' }}>
            {text || '正在聆听…'}
          </p>
        ) : (
          <p className="relative text-[17px] leading-[1.55] text-center break-words font-bold"
            style={{ color: 'var(--primary-dark)' }}>
            {text || (
              <span className="inline-flex gap-1 items-center justify-center py-0.5">
                {[0, 1, 2].map(i => (
                  <span key={i} className="w-2 h-2 rounded-full animate-bounce"
                    style={{ background: 'var(--primary)', animationDelay: `${i * 150}ms` }} />
                ))}
              </span>
            )}
          </p>
        )}
      </div>

      {/* 递减小圆泡尾巴（从水泡垂向波波嘴部）——"吐泡泡"的视觉锚点 */}
      <div className={`absolute top-full ${tailAlign} flex flex-col items-center gap-0.5 -mt-0.5`}>
        <span className="w-2.5 h-2.5 rounded-full border-2"
          style={danger ? { background: '#EF4444', borderColor: '#F87171' }
            : { background: 'var(--primary-light)', borderColor: 'var(--primary)' }} />
        <span className="w-1.5 h-1.5 rounded-full border"
          style={danger ? { background: '#EF4444', borderColor: '#F87171' }
            : { background: 'var(--primary-light)', borderColor: 'var(--primary)' }} />
      </div>

      {/* 说话时两侧装饰小泡（上浮渐隐循环，与波波 speaking 冒泡动画呼应） */}
      {mode === 'speaking' && !danger && (
        <>
          <span className="absolute -left-2 bottom-1 w-2 h-2 rounded-full border bg-white/70 animate-[bubble-rise_2.4s_ease-out_infinite]"
            style={{ borderColor: 'var(--primary)' }} />
          <span className="absolute -right-1.5 bottom-3 w-1.5 h-1.5 rounded-full border bg-white/70 animate-[bubble-rise_2.4s_ease-out_0.9s_infinite]"
            style={{ borderColor: 'var(--primary)' }} />
          <span className="absolute -right-3 bottom-0 w-2.5 h-2.5 rounded-full border bg-white/70 animate-[bubble-rise_2.4s_ease-out_1.6s_infinite]"
            style={{ borderColor: 'var(--primary)' }} />
        </>
      )}

      {/* 气泡动画 keyframes（内联注入，避免依赖全局 CSS 配置） */}
      <style>{`
        @keyframes bubble-blow {
          0%   { opacity: 0; transform: scale(0.3) translateY(10px); }
          70%  { opacity: 1; transform: scale(1.06) translateY(-2px); }
          100% { opacity: 1; transform: scale(1) translateY(0); }
        }
        @keyframes bubble-drift {
          0%, 100% { transform: translateY(0) rotate(-0.6deg); }
          50%      { transform: translateY(-3px) rotate(0.6deg); }
        }
        @keyframes bubble-rise {
          0%   { opacity: 0; transform: translateY(6px) scale(0.6); }
          30%  { opacity: 0.9; }
          100% { opacity: 0; transform: translateY(-18px) scale(1.15); }
        }
        @keyframes think-rise {
          0%, 100% { transform: translateY(0); opacity: 0.5; }
          50%      { transform: translateY(-4px); opacity: 1; }
        }
      `}</style>
    </div>
  )
}
