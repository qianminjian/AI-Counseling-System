/**
 * DC-005 共享认证传输模块：统一 401 登出决策点
 * （SPEC §19：handleSessionExpired(storage, loginPath?)）
 *
 * 语义：清 token + 跳转（loginPath 传入 → location.href；缺省 → location.reload）+ 抛错。
 * 返回 never——调用方在 401 失败分支调用后代码不可达，消除三端各自 clear/reload/throw 复制。
 */
import type { TokenStorage } from './tokenStorage'

export function handleSessionExpired(storage: TokenStorage, loginPath?: string): never {
  storage.clear()
  if (loginPath) {
    window.location.href = loginPath
  } else {
    window.location.reload()
  }
  throw new Error('登录已过期，请重新登录')
}
