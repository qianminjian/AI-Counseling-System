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

/** H5 实现：sessionStorage（会话级，关闭浏览器自动清除） */
export const sessionStorageImpl: PlatformStorage = {
  get: (key) => sessionStorage.getItem(key),
  set: (key, value) => {
    sessionStorage.setItem(key, value)
  },
  remove: (key) => {
    sessionStorage.removeItem(key)
  },
}
