/**
 * doing/73 T3（AC-5）：跨端导航封装（Taro 路由 API）
 * URL 形态 = customRoutes 映射后的公共路径（'/'、'/report'、'/consent'、'/privacy'），
 * Taro 内部经 route-map 映射到真实页面路径
 * P1 小程序端：redirectTo/navigateTo 为 Taro 原生 API，本文件零改动
 */
import Taro from '@tarojs/taro'

/** 重定向跳转（替换当前页，等价原 useNavigate(to, { replace: true })） */
export function redirectTo(url: string): void {
  Taro.redirectTo({ url })
}

/** 前进导航（等价原 useNavigate(to) 的 push 语义） */
export function navigateTo(url: string): void {
  Taro.navigateTo({ url })
}
