/**
 * DC-012 ChatRoom 规则抽离（SPEC §26）：波波状态机纯函数
 *
 * 优先级：recording > streaming > playing > standby(待唤醒) > active(会话窗聆听) > idle
 * （对齐原 ChatRoom L299-304 语义，design/27 §4.3 + design/28 §1.1）。
 */
export type BoboState = 'listening' | 'thinking' | 'speaking' | 'waitingWake' | 'idle'

export function computeBoboState(f: {
  recording: boolean
  streaming: boolean
  playing: boolean
  wakeMode: 'standby' | 'active' | 'off'
}): BoboState {
  if (f.recording) return 'listening'
  if (f.streaming) return 'thinking'
  if (f.playing) return 'speaking'
  if (f.wakeMode === 'standby') return 'waitingWake'
  if (f.wakeMode === 'active') return 'listening'
  return 'idle'
}
