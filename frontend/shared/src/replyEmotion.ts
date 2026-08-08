/**
 * AI 回复情绪白名单（design/37 §三.1 三方同源 · 单一事实源）
 *
 * AI 回复产出结构化情绪标签（6 类），同一标签同时驱动三个消费方：
 *   tts-service（emotion → instruct）/ BoBoPet 表情状态机 / 气泡·背景微动效。
 * 本文件是 REPLY_EMOTIONS 的唯一注册点：emotionBus 与各消费方只引用不定义，
 * 禁止前端各自根据文本猜情绪（三方不一致比没有情绪更伤体验）。
 */

export const REPLY_EMOTIONS = ['happy', 'gentle', 'encourage', 'calm', 'serious', 'soothe'] as const

export type ReplyEmotion = (typeof REPLY_EMOTIONS)[number]

/** 归一化：小写 + 白名单校验；未知标签 → null（消费方收到 null 应回落 idle/neutral） */
export function normalizeReplyEmotion(raw: unknown): ReplyEmotion | null {
  if (typeof raw !== 'string') return null
  const lower = raw.trim().toLowerCase()
  return (REPLY_EMOTIONS as readonly string[]).includes(lower) ? (lower as ReplyEmotion) : null
}
