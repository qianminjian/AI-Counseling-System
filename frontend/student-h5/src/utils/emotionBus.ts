/**
 * emotionBus —— 情绪信号单一事件源（TTSFX-004，design/37 §三.1/§五）
 *
 * 设计契约（design/37 §三.1 三方同源）：
 *   AI 回复的 emotion 标签（6 类，词表见 shared/src/replyEmotion 单一事实源）
 *   由本单例统一分发，消费方：
 *     1. BoBoPet 表情状态机（表情/姿态同步）
 *     2. TTS 播放器（emotion → instruct，已由后端 tts-service 承接，前端仅透传埋点）
 *     3. 主题层微动效（背景/气泡色调微调，气泡 emotion 与总线同值，FA-09）
 *   三方禁止各取各的信号源，防止 TTS 情绪与波波表情劈叉。
 *
 * 失败隔离：单个订阅者抛异常不影响其余订阅者（消费方均为装饰层，不可拖累主流程）。
 */

import { REPLY_EMOTIONS, normalizeReplyEmotion, type ReplyEmotion } from '../../../shared/src/replyEmotion'

export { REPLY_EMOTIONS, type ReplyEmotion } from '../../../shared/src/replyEmotion'

export type EmotionListener = (emotion: ReplyEmotion | null) => void

/** 归一化（FA-09：实现与词表统一指向 shared 单源） */
export const normalizeEmotion = normalizeReplyEmotion

type EmotionBus = {
  /** 订阅情绪事件；返回取消订阅函数。不补发历史值（避免组件挂载误触）。 */
  subscribe(listener: EmotionListener): () => void
  /** 发布情绪（自动归一化；未知标签发布 null） */
  publish(raw: unknown): void
  /** 最新一次归一化后的情绪（无事件为 null） */
  current(): ReplyEmotion | null
  /** 测试/会话切换时清空状态 */
  reset(): void
}

function createEmotionBus(): EmotionBus {
  let listeners = new Set<EmotionListener>()
  let latest: ReplyEmotion | null = null

  return {
    subscribe(listener) {
      listeners.add(listener)
      return () => { listeners.delete(listener) }
    },
    publish(raw) {
      latest = normalizeEmotion(raw)
      for (const listener of [...listeners]) {
        try {
          listener(latest)
        } catch {
          /* 失败隔离：装饰层订阅者异常不阻塞其他消费方 */
        }
      }
    },
    current() {
      return latest
    },
    reset() {
      listeners = new Set()
      latest = null
    },
  }
}

/** 应用级单例（全局唯一信号源） */
export const emotionBus = createEmotionBus()
