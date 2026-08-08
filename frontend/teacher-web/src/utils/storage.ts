/**
 * localStorage 失败安全读写工具（F-07：镜像 student-h5 storage.ts，闭环 AUD-065）
 *
 * 背景（深度审计 F-07 / AUD-065）：App.tsx 直读 localStorage 无 try/catch，
 * 隐私模式/禁用存储下抛 SecurityError → 未捕获 → 白屏。student 端已闭环，此处镜像。
 */

/** 读取 localStorage，存储不可用/异常时返回 fallback（不抛出） */
export function readLocalStorageSafe<T extends string = string>(key: string, fallback: T): string {
  try {
    const value = localStorage.getItem(key)
    return value === null ? fallback : value
  } catch {
    return fallback
  }
}

/** 写入 localStorage，存储不可用时静默跳过（不抛出；偏好不持久化不影响功能） */
export function writeLocalStorageSafe(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // 偏好不持久化不影响功能（会话内状态仍生效）
  }
}

/** 删除 localStorage 键，存储不可用时静默跳过（不抛出） */
export function removeLocalStorageSafe(key: string): void {
  try {
    localStorage.removeItem(key)
  } catch {
    // 删除失败不影响功能
  }
}
