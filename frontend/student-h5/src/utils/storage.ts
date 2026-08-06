/**
 * localStorage 失败安全读写工具（P0-2，2026-07-28）
 *
 * 背景（深度审计 P0-2）：EmotionSelect 直读 localStorage 无 try/catch，
 * 隐私模式/禁用存储下抛 SecurityError → 未捕获 → 白屏。
 * 与 useWakeEnabled.ts 的失败安全模式一致（该项目样板）。
 */

/** 读取 localStorage，存储不可用/异常时返回 fallback（不抛出） */
export function readLocalStorageSafe<T>(key: string, fallback: T): T {
  try {
    const value = localStorage.getItem(key)
    return value === null ? fallback : (value as unknown as T)
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
