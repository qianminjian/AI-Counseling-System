/**
 * 波波表情状态机（TTSFX-004，design/37 §4.1）
 *
 * 纯 reducer：事件驱动、可打断、同刻仅一个状态。
 * 安全红线：S0/S1 风险（riskLevel ≥ 2，即橙/红）锁定 hug 姿态，
 * 不随后续消息切换，直到 risk-cleared（教师处置/学生自报安全）。
 *
 * 与 BoBoPet 既有交互态（idle/listening/thinking/speaking）的关系：
 * 交互态管"姿态容器"，表情层管"脸部+情绪动效"，两层正交叠加。
 */
import { normalizeReplyEmotion, type ReplyEmotion } from '../../../shared/src/replyEmotion'

export type BoboExpression =
  | 'idle'     // 呼吸感微动（唯一允许的常驻动画）
  | 'happy'    // 开心弹跳 + 眼睛弯弯
  | 'gentle'   // 轻轻点头 + 缓慢眨眼（gentle/calm）
  | 'cheer'    // 举手加油（encourage）
  | 'hug'      // 靠近 + 抱抱姿态（soothe/serious + S0/S1 锁定）
  | 'listen'   // 侧耳倾听（学生输入中）
  | 'think'    // 歪头冒泡泡（AI 思考中）
  | 'sleep'    // 打盹（离线）

export const EMOTION_TO_EXPRESSION: Record<ReplyEmotion, BoboExpression> = {
  happy: 'happy',
  gentle: 'gentle',
  calm: 'gentle',
  encourage: 'cheer',
  soothe: 'hug',
  serious: 'hug',
}

export type BoboExpressionState = {
  expression: BoboExpression
  /** S0/S1 锁定标记：true 时除 risk-cleared 外的事件一律忽略 */
  locked: boolean
}

export type BoboExpressionEvent =
  | { type: 'reply-emotion'; emotion: unknown }
  | { type: 'typing' }
  | { type: 'thinking' }
  | { type: 'offline' }
  | { type: 'idle' }
  /** riskLevel：1=YELLOW（安抚不锁定）、2=橙、3=红（S0/S1 锁定 hug） */
  | { type: 'risk'; riskLevel: number }
  | { type: 'risk-cleared' }

export function initialExpressionState(): BoboExpressionState {
  return { expression: 'idle', locked: false }
}

export function boboExpressionReducer(
  state: BoboExpressionState,
  event: BoboExpressionEvent,
): BoboExpressionState {
  // 解锁事件任何时刻都生效（教师处置完成 / 危机解除）
  if (event?.type === 'risk-cleared') {
    return { expression: 'idle', locked: false }
  }

  // S0/S1 锁定期间：除解锁外一律忽略（安全红线，不随后续消息切换）
  if (state.locked) {
    return state
  }

  switch (event?.type) {
    case 'risk': {
      const lock = event.riskLevel >= 2
      return { expression: 'hug', locked: lock }
    }
    case 'reply-emotion': {
      const emotion = normalizeReplyEmotion(event.emotion)
      return {
        expression: emotion ? EMOTION_TO_EXPRESSION[emotion] : 'idle',
        locked: false,
      }
    }
    case 'typing':
      return { expression: 'listen', locked: false }
    case 'thinking':
      return { expression: 'think', locked: false }
    case 'offline':
      return { expression: 'sleep', locked: false }
    case 'idle':
      return { expression: 'idle', locked: false }
    default:
      // 失败安全：未知事件保持原状态
      return state
  }
}
