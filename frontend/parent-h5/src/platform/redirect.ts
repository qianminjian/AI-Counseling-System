/**
 * doing/73 T1（AC-3）：平台适配层——PlatformRedirect 类型与 H5 实现
 * H5 实现 = location.assign（整页跳转，与迁移前 location.href 赋值等价）
 * P1 小程序端：换 Taro.reLaunch 实现，接口不变
 */
export type PlatformRedirect = (to: string) => void

/** H5 实现：整页跳转（空串 → 整页刷新，兼容 handleSessionExpired 缺省 loginPath 语义） */
export const locationRedirect: PlatformRedirect = (to) => {
  if (to) {
    window.location.assign(to)
  } else {
    window.location.reload()
  }
}
