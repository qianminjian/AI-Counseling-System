/**
 * doing/73 T1（AC-3）：平台适配层——PlatformStorage 接口与 H5 实现
 * H5 实现 = sessionStorage 包装（AUD-007 会话级语义保持，与迁移前 utils/auth 一致）
 * P1 小程序端：换 Taro.getStorageSync 实现，接口不变
 */
export interface PlatformStorage {
  get(key: string): string | null
  set(key: string, value: string): void
  remove(key: string): void
}

/** H5 实现：sessionStorage（会话级，关闭浏览器自动清除）——F-12：读写失败安全（隐私模式等场景不抛 SecurityError） */
export const sessionStorageImpl: PlatformStorage = {
  get: (key) => {
    try {
      return sessionStorage.getItem(key)
    } catch {
      return null
    }
  },
  set: (key, value) => {
    try {
      sessionStorage.setItem(key, value)
    } catch {
      // 会话存储不可用 → 静默跳过（登录态保持内存会话）
    }
  },
  remove: (key) => {
    try {
      sessionStorage.removeItem(key)
    } catch {
      // 同上，静默
    }
  },
}
