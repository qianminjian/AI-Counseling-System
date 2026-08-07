/**
 * DC-005 共享认证传输模块：Token 刷新
 * （SPEC §19：refreshTokens(storage, baseUrl?)）
 *
 * 语义对齐三端原 tryRefresh：无 rt → false；成功 → 双 token 写入 + true；
 * 业务拒绝/网络异常 → false（不清 token，登出决策交调用方）。
 */
import type { TokenStorage } from './tokenStorage'

export async function refreshTokens(
  storage: TokenStorage,
  baseUrl = '',
  fetchImpl: typeof fetch = fetch
): Promise<boolean> {
  const rt = storage.getRefreshToken()
  if (!rt) return false
  try {
    const res = await fetchImpl(`${baseUrl}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: rt }),
    })
    const json = await res.json()
    if (json.success && json.data?.token) {
      storage.setToken(json.data.token)
      storage.setRefreshToken(json.data.refreshToken)
      return true
    }
  } catch { /* ignore */ }
  return false
}
