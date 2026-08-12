import { getEmotionTypo } from '../theme/emotionTypography'
import { emotionEmoji } from '../../../shared/src/emotionMeta'

/** 消息数据结构 */
export interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
  // FE-003：允许 null（发送失败占位气泡运行时写入 emotion: null，测试断言该值；null 与 undefined 对消费方均为 falsy）
  emotion?: string | null
  level?: number
}

/** 消息气泡组件（含 TTS 播放按钮） */
export default function MessageBubble({ msg, isLast, streaming, onReplay, isSpeaking }: {
  msg: ChatMessage
  isLast: boolean
  streaming: boolean
  onReplay: (text: string) => void
  isSpeaking: boolean
}) {
  if (msg.role === 'system') {
    return (
      <div className="flex justify-start lg:justify-center">
        <div className={`w-full lg:w-auto text-center py-2 px-3 rounded-lg text-sm lg:text-base
          ${(msg.level ?? 0) >= 3 ? 'bg-red-50 text-red-600' : 'bg-amber-50 text-amber-600'}`}>
          {msg.content}
        </div>
      </div>
    )
  }

  const isAi = msg.role === 'assistant'
  // 情感化排印：AI 回复按孩子情绪调整字号/字重/底色/入场动效
  // （语音情绪优先、会话情绪兜底、未知回退 neutral）；用户消息保持原有样式
  const typo = isAi ? getEmotionTypo(msg.emotion) : null

  return (
    <div className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
      <div
        className={`group relative max-w-[85%] lg:max-w-[70%] px-4 py-3 lg:px-5 lg:py-4 rounded-2xl lg:rounded-3xl
        whitespace-pre-wrap transition-colors
        ${msg.role === 'user'
          ? 'text-sm lg:text-lg leading-relaxed bg-[var(--primary)] text-white rounded-br-md'
          : `ai-msg-text text-gray-700 border border-gray-100 shadow-sm rounded-bl-md ${typo.anim}`
        } ${isSpeaking ? 'ring-2 ring-[var(--primary)] ring-opacity-40' : ''}`}
        style={isAi ? {
          '--typo-scale': typo.scale,
          '--typo-weight': typo.weight,
          background: typo.tint,
          borderLeft: `4px solid ${typo.accent}`,
        } as React.CSSProperties : undefined}
      >
        {msg.role === 'user' && msg.emotion && msg.emotion !== 'unknown' && (
          <span className="inline-block mr-1 text-xs lg:text-sm opacity-80">
            {emotionEmoji(msg.emotion) || '🎵'}
          </span>
        )}
        {msg.content || (streaming && isLast ? '...' : '')}

        {/* AI 消息的 TTS 播放按钮 */}
        {isAi && msg.content && !streaming && (
          <button
            onClick={() => onReplay(msg.content)}
            className={`absolute -right-3 -bottom-2 w-7 h-7 lg:w-9 lg:h-9 rounded-full flex items-center justify-center
              bg-white shadow-md border border-gray-100 transition-all active:scale-90
              ${isSpeaking ? 'text-[var(--primary)]' : 'text-gray-400 hover:text-[var(--primary)]'}`}
            title="播放语音"
            // AUD-050：aria-label 供读屏器识别（title 仅悬浮提示，读屏不保证朗读）
            aria-label={isSpeaking ? '正在播放语音' : '播放语音'}
          >
            {isSpeaking ? (
              <span className="flex items-end gap-[2px] h-3.5">
                <span className="w-[3px] bg-current rounded-full animate-pulse" style={{ height: '60%' }} />
                <span className="w-[3px] bg-current rounded-full animate-pulse" style={{ height: '100%', animationDelay: '0.15s' }} />
                <span className="w-[3px] bg-current rounded-full animate-pulse" style={{ height: '40%', animationDelay: '0.3s' }} />
              </span>
            ) : (
              <svg className="w-3.5 h-3.5 lg:w-4 lg:h-4" fill="currentColor" viewBox="0 0 24 24">
                <path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/>
              </svg>
            )}
          </button>
        )}
      </div>
    </div>
  )
}
