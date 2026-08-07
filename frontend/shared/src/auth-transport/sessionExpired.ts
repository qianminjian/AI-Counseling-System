/**
 * DC-005 共享认证传输模块：统一 401 登出决策点
 * （SPEC §19：handleSessionExpired(storage, loginPath?)）
 *
 * 语义：清 token + 跳转（loginPath 传入 → location.href；缺省 → location.reload）+ 抛错。
 * 返回 never——调用方在 401 失败分支调用后代码不可达，消除三端各自 clear/reload/throw 复制。
 */
import type { TokenStorage } from './tokenStorage'

/** 平台跳转实现（doing/73 T1：parent 注入 locationRedirect；P1 小程序端注入 Taro.reLaunch） */
export type RedirectFn = (to: string) => void

/** 缺省 H5 实现：有目标 → location.href 跳转；空串 → 整页刷新（保持迁移前双分支语义） */
const defaultRedirect: RedirectFn = (to) => {
  if (to) {
    window.location.href = to
  } else {
    window.location.reload()
  }
}

export function handleSessionExpired(
  storage: TokenStorage,
  loginPath?: string,
  redirect: RedirectFn = defaultRedirect
): never {
  storage.clear()
  redirect(loginPath ?? '')
  throw new Error('登录已过期，请重新登录')
}
