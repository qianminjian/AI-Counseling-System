/**
 * parent-h5 平台请求工厂单例（doing/94 R-003：工厂收敛单一事实源）
 *
 * 此前 createPlatformRequest 实例四散自建（services/index.ts parent_、
 * device.ts parent_+toc_、toc.ts toc_）——同一身份前缀两份实例、认证存储各持一份，
 * 新增服务文件极易再复制一份。现在：
 * - parentRequest：家长身份（Bearer parent_ token）
 * - tocRequest：家庭账号身份（Bearer toc_ token）
 * - tocTokens：toc_ TokenStorage 单例（toc.ts 会话落盘与其共享，键名契约一致）
 * 各服务文件只消费本模块导出，不再自建工厂。
 */
import { createPlatformRequest } from '../platform/request'
import { createPlatformTokens } from '../../../shared/src/auth-transport/tokenStorage'
import { sessionStorageImpl } from '../platform/storage'

/** toc_ TokenStorage 单例（toc 会话落盘与请求工厂共享同一实例） */
export const tocTokens = createPlatformTokens('toc_', sessionStorageImpl)

/** 家长身份请求工厂（Bearer parent_ token） */
export const parentRequest = createPlatformRequest({
  storage: createPlatformTokens('parent_', sessionStorageImpl),
})

/** 家庭账号身份请求工厂（Bearer toc_ token） */
export const tocRequest = createPlatformRequest({
  storage: tocTokens,
})
