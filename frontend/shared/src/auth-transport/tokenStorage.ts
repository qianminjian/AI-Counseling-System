/**
 * DC-005 共享认证传输模块：sessionStorage Token 存取
 * （SPEC §19：createSessionStorageTokens(prefix)）
 *
 * 统一三端双 token + 用户信息键形态：`${prefix}token` / `${prefix}refresh` / `${prefix}user`
 * - student：mindsafe_student_*（token/refresh/user）
 * - teacher：mindsafe_*（token/refresh；user 键不存在，clear 无害）
 * - parent：parent_*（历史差异 parent_refresh_token 已收敛为 parent_refresh，适配层在
 *   parent-h5/src/utils/auth.ts 包装 '' 空串语义；doing/94 R-003 注释修正）
 */
export interface TokenStorage {
  getToken(): string | null
  setToken(token: string): void
  getRefreshToken(): string | null
  setRefreshToken(token: string): void
  clear(): void
}

/** 平台存储底层（doing/73 T1：parent 适配层注入 sessionStorageImpl；P1 小程序端注入 Taro storage） */
export interface StorageLike {
  get(key: string): string | null
  set(key: string, value: string): void
  remove(key: string): void
}

function createTokens(prefix: string, storage: StorageLike): TokenStorage {
  const tokenKey = `${prefix}token`
  const refreshKey = `${prefix}refresh`
  const userKey = `${prefix}user`
  return {
    getToken: () => storage.get(tokenKey),
    setToken: (token: string) => {
      storage.set(tokenKey, token)
    },
    getRefreshToken: () => storage.get(refreshKey),
    setRefreshToken: (token: string) => {
      storage.set(refreshKey, token)
    },
    // 与原三端 clearToken/clearAuth 语义一致：清双 token + 用户信息
    clear: () => {
      storage.remove(tokenKey)
      storage.remove(refreshKey)
      storage.remove(userKey)
    },
  }
}

export function createSessionStorageTokens(prefix: string): TokenStorage {
  return createTokens(prefix, {
    get: (key) => sessionStorage.getItem(key),
    set: (key, value) => {
      sessionStorage.setItem(key, value)
    },
    remove: (key) => {
      sessionStorage.removeItem(key)
    },
  })
}

/** doing/73 T1（AC-4）：基于注入 storage 的 TokenStorage 工厂（三端调用零改动，默认参数保持现状） */
export function createPlatformTokens(prefix: string, storage: StorageLike): TokenStorage {
  return createTokens(prefix, storage)
}
