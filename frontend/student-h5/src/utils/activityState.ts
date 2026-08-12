/**
 * 会话活动状态单一派生（S-015，doing/93）
 *
 * ChatRoom 语音多路汇聚面：streaming/tts.playing/recording/analyzing 互斥判定
 * 此前被 5 个消费者各自复刻（busy/idle/micWanted/守卫条件），新增活动源需多处同步。
 * 本模块收敛为单一纯函数 + 互斥表（可直接单测）。
 */

export interface ActivityInput {
  streaming: boolean
  ttsPlaying: boolean
  ttsMuted: boolean
  recording: boolean
  analyzing: boolean
  wakeEnabled: boolean
  hasConsent: boolean
}

export interface ActivityState {
  /** AI 忙碌（流式/朗读/录音/识别任一活跃）——语音唤醒机互斥输入 */
  busy: boolean
  /** 孩子基础空闲（无活动 + 未静音；唤醒模式互斥由消费层组合 voiceCall.mode）——冷场检测互斥输入 */
  idleBase: boolean
  /** 需要麦克风（Android 音频路由：播放中释放、唤醒时保留） */
  micWanted: boolean
}

/** 会话活动状态派生（S-015：忙/闲/麦克风单一事实源，消费方只读结果） */
export function deriveActivityState(input: ActivityInput): ActivityState {
  const busy = input.streaming || input.ttsPlaying || input.recording || input.analyzing
  const idleBase =
    !input.streaming && !input.recording && !input.analyzing && !input.ttsPlaying &&
    !input.ttsMuted
  const micWanted = input.hasConsent && input.wakeEnabled
  return { busy, idleBase, micWanted }
}
