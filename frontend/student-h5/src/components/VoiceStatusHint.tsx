/**
 * 语音状态 → 文案/指示器单一映射（FA-14）
 *
 * 此前 ChatRoom JSX 双处（Pad 主/副提示）+ 手机 chip 各映射一遍 wakeStatus/mode 中文文案，
 * 改文案需同步三处。此处收敛为单一描述函数 + VoiceStatusChip 子组件：
 *   mainHint / subHint：Pad 左栏主/副提示（父级优先级 recording/analyzing/... 留在调用方）
 *   chipHint / VoiceStatusChip：手机端唤醒状态指示器（无匹配状态返回 null 不渲染）
 */

export type VoiceCallStatus = {
  mode: string
  wakeStatus: string
}

/** Pad 左栏主提示：状态 → 一句文案（未知状态兜底"想说什么就说什么吧"） */
export function mainHint(voiceCall: VoiceCallStatus): string {
  const { wakeStatus, mode } = voiceCall
  if (wakeStatus === 'detected') return '听到了！🎉'
  if (wakeStatus === 'loading') return '语音引擎加载中...'
  if (wakeStatus === 'error') return '语音引擎未就绪'
  if (mode === 'standby') return '叫我“哈喽波波”'
  if (mode === 'active') return '我在听，直接说吧'
  return '想说什么就说什么吧'
}

/** Pad 左栏副提示：状态 → 操作引导文案 */
export function subHint(voiceCall: VoiceCallStatus): string {
  const { wakeStatus, mode } = voiceCall
  if (wakeStatus === 'detected') return '正在准备听你说话...'
  if (mode === 'standby') {
    // F-29（2026-08-10 用户要求）：未就绪时明确"等会儿再叫我"，避免用户过早呼叫
    if (wakeStatus === 'loading') return '正在加载语音引擎…等会儿再叫我哦'
    if (wakeStatus === 'listening') return '我在这里安静地等你叫我'
    if (wakeStatus === 'error') return '语音引擎加载失败，请关闭再开启'
    // F-23（2026-08-10）：idle/未知状态不得冒充 standby——原兜底同样返回
    // "我在这里安静地等你叫我"，导致引擎未就绪（缓存空首访下载期）时用户误以为可呼叫。
    return '正在准备语音引擎…等会儿再叫我哦'
  }
  if (mode === 'active') return '不用按，直接说就行'
  return '按住波波，跟它说说话'
}

/** F-29：唤醒引擎是否未就绪（idle/loading）——用于图标下醒目提示，避免用户过早呼叫 */
export function isWakeNotReady(voiceCall: VoiceCallStatus): boolean {
  const { wakeStatus } = voiceCall
  return wakeStatus === 'idle' || wakeStatus === 'loading'
}

export type VoiceChipVariant = 'loading' | 'listening' | 'detected' | 'active' | 'error'

/** 手机端唤醒状态指示器：状态 → { 文案, 样式变体 }；无匹配（off/idle）返回 null */
export function chipHint(voiceCall: VoiceCallStatus): { text: string; variant: VoiceChipVariant } | null {
  const { wakeStatus, mode } = voiceCall
  if (wakeStatus === 'loading') return { text: '语音引擎加载中…等会儿再叫我哦', variant: 'loading' }
  if (wakeStatus === 'listening' && mode === 'standby') return { text: '说“哈喽波波”唤醒我', variant: 'listening' }
  if (wakeStatus === 'detected') return { text: '🎉 听到了！正在准备听你说话...', variant: 'detected' }
  // error 优先于 active：引擎故障提示比“我在听”更重要（单函数收敛后需显式定序）
  if (wakeStatus === 'error') return { text: '语音引擎加载失败，可在设置中重试', variant: 'error' }
  if (mode === 'active' && wakeStatus !== 'detected') return { text: '我在听，直接说吧', variant: 'active' }
  return null
}

const CHIP_COLOR: Record<VoiceChipVariant, string> = {
  loading: 'bg-blue-50 text-blue-500',
  listening: 'bg-green-50 text-green-600',
  detected: 'bg-amber-50 text-amber-600',
  active: 'bg-green-50 text-green-600',
  error: 'bg-red-50 text-red-500',
}

const CHIP_DOT: Record<'loading' | 'listening' | 'active', { ping: string; solid: string }> = {
  loading: { ping: 'bg-blue-400', solid: 'bg-blue-500' },
  listening: { ping: 'bg-green-400', solid: 'bg-green-500' },
  active: { ping: 'bg-green-400', solid: 'bg-green-500' },
}

/** 手机端唤醒状态指示器组件：loading/listening/active 带呼吸圆点，detected/error 纯文案 */
export function VoiceStatusChip({ voiceCall }: { voiceCall: VoiceCallStatus }) {
  const hint = chipHint(voiceCall)
  if (!hint) return null
  const isPulse = hint.variant === 'loading' || hint.variant === 'listening' || hint.variant === 'active'
  const dot = isPulse ? CHIP_DOT[hint.variant as 'loading' | 'listening' | 'active'] : null
  return (
    <span className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full ${CHIP_COLOR[hint.variant]}`}>
      {dot && (
        <span className="relative flex h-2 w-2">
          <span className={`animate-ping absolute inline-flex h-full w-full rounded-full ${dot.ping} opacity-75`}></span>
          <span className={`relative inline-flex rounded-full h-2 w-2 ${dot.solid}`}></span>
        </span>
      )}
      {hint.text}
    </span>
  )
}
