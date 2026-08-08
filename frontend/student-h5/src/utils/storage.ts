/**
 * localStorage 失败安全读写工具（P0-2，2026-07-28）
 *
 * 背景（深度审计 P0-2）：EmotionSelect 直读 localStorage 无 try/catch，
 * 隐私模式/禁用存储下抛 SecurityError → 未捕获 → 白屏。
 * 与 useWakeEnabled.ts 的失败安全模式一致（该项目样板）。
 */

/** 读取 localStorage，存储不可用/异常时返回 fallback（不抛出） */
// AUD-065：返回类型固定 string（避免 fallback 字面量推断导致调用处 TS2367 误报）
export function readLocalStorageSafe<T extends string = string>(key: string, fallback: T): string {
  try {
    const value = localStorage.getItem(key)
    return value === null ? fallback : value
  } catch {
    return fallback
  }
}

/** 写入 localStorage，存储不可用时静默跳过（不抛出） */
export function writeLocalStorageSafe(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // 偏好不持久化不影响功能（会话内状态仍生效）
  }
}

/** TTS 静音偏好键（FA-05，DOC-074：EmotionSelect / useTtsPlayer 共享同一持久化状态） */
export const TTS_MUTED_KEY = 'mindsafe_tts_muted_v1'

/** 读取 TTS 静音偏好（默认未静音） */
export function readMutedPreference(): boolean {
  return readLocalStorageSafe(TTS_MUTED_KEY, '0') === '1'
}

/** 写入 TTS 静音偏好 */
export function writeMutedPreference(muted: boolean): void {
  writeLocalStorageSafe(TTS_MUTED_KEY, muted ? '1' : '0')
}
