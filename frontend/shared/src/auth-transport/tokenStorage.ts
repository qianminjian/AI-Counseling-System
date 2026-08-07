/**
 * DC-005 共享认证传输模块：sessionStorage Token 存取
 * （SPEC §19：createSessionStorageTokens(prefix)）
 *
 * 统一三端双 token + 用户信息键形态：`${prefix}token` / `${prefix}refresh` / `${prefix}user`
 * - student：mindsafe_student_*（token/refresh/user）
 * - teacher：mindsafe_*（token/refresh；user 键不存在，clear 无害）
 * - parent：parent_* 键名为 parent_refresh_token（历史差异），由 parent 适配层包装
 */
export interface TokenStorage {
  getToken(): string | null
  setToken(token: string): void
  getRefreshToken(): string | null
  setRefreshToken(token: string): void
  clear(): void
}

export function createSessionStorageTokens(prefix: string): TokenStorage {
  const tokenKey = `${prefix}token`
  const refreshKey = `${prefix}refresh`
  const userKey = `${prefix}user`
  return {
    getToken: () => sessionStorage.getItem(tokenKey),
    setToken: (token: string) => {
      sessionStorage.setItem(tokenKey, token)
    },
    getRefreshToken: () => sessionStorage.getItem(refreshKey),
    setRefreshToken: (token: string) => {
      sessionStorage.setItem(refreshKey, token)
    },
    // 与原三端 clearToken/clearAuth 语义一致：清双 token + 用户信息
    clear: () => {
      sessionStorage.removeItem(tokenKey)
      sessionStorage.removeItem(refreshKey)
      sessionStorage.removeItem(userKey)
    },
  }
}
