/**
 * DC-005 共享认证传输模块：带 JWT 认证的 fetch
 * （SPEC §19：createAuthFetch(storage, baseUrl?)）
 *
 * 语义对齐三端原 authFetch：
 * - 自动携带 Bearer token（含 Headers 实例合并，multipart 场景）
 * - 401 → refreshTokens 成功 → 重放原请求一次
 * - 刷新失败 → 返回原始 401 Response，登出决策交给调用方（不静默清 token）
 * - 非 401 直接透传
 */
import { refreshTokens } from './refresh'
import type { TokenStorage } from './tokenStorage'

export function createAuthFetch(storage: TokenStorage, baseUrl = ''): (url: string, init?: RequestInit) => Promise<Response> {
  return async (url: string, init?: RequestInit): Promise<Response> => {
    const doFetch = () => {
      const token = storage.getToken()
      return fetch(`${baseUrl}${url}`, {
        ...init,
        headers: {
          ...(init?.headers instanceof Headers
            ? Object.fromEntries((init.headers as Headers).entries())
            : init?.headers || {}),
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
      })
    }
    let res = await doFetch()
    if (res.status === 401) {
      if (await refreshTokens(storage, baseUrl)) {
        res = await doFetch()
      }
    }
    return res
  }
}
