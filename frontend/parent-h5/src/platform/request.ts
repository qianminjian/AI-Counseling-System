/**
 * doing/73 T1（AC-3）：平台适配层——PlatformRequest 类型与 H5 实现（fetch 包装工厂）
 * 语义与迁移前 src/api/index.ts request() 完全等价：
 * - Bearer 注入 + JSON 编解码
 * - 401 → refreshTokens（注入 fetchImpl）成功 → 重放原请求一次（_retried 防环）
 * - 401 刷新失败 → onSessionExpired 统一登出决策点（缺省 = shared handleSessionExpired + locationRedirect）
 * - 成功判定为 success 契约（DOC-073 F1，doing/77 §24）：success!==true → toApiError；HTTP 非 2xx 按状态码兜底 message
 * - F-04（doing/80 批次 C）：收敛为 data 解包——信封 success 已抛错，解包安全，页面零二次解包
 * - 网络异常 → 原样 reject
 * P1 小程序端：换 Taro.request 实现（同接口），页面层零改动
 */
import { refreshTokens } from '../../../shared/src/auth-transport/refresh'
import { handleSessionExpired } from '../../../shared/src/auth-transport/sessionExpired'
import { toApiError } from '../../../shared/src/auth-transport/apiError'
import type { TokenStorage } from '../../../shared/src/auth-transport/tokenStorage'
import { locationRedirect } from './redirect'

export interface PlatformRequestOptions {
  method?: string
  headers?: Record<string, string>
  data?: unknown
  _retried?: boolean
}

export interface PlatformApiResponse<T = unknown> {
  code?: number
  message?: string
  data?: T
  success?: boolean
}

export type PlatformRequest = <T = unknown>(
  path: string,
  options?: PlatformRequestOptions
) => Promise<T>

export interface PlatformRequestDeps {
  /** 认证 token 存取（parent 用 createPlatformTokens('parent_', sessionStorageImpl)） */
  storage: TokenStorage
  /** API 前缀，缺省 '/api/v1'（与迁移前一致） */
  baseUrl?: string
  /** fetch 实现（refreshTokens 注入用），缺省全局 fetch */
  fetchImpl?: typeof fetch
  /** 401 刷新失败统一登出决策点，缺省 = handleSessionExpired(storage, '/parent/', locationRedirect) */
  onSessionExpired?: () => never
}

export function createPlatformRequest(deps: PlatformRequestDeps): PlatformRequest {
  const { storage, baseUrl = '/api/v1', fetchImpl, onSessionExpired } = deps
  const expire = onSessionExpired ??
    (() => handleSessionExpired(storage, '/parent/', locationRedirect))

  return async function request<T = unknown>(
    path: string,
    options: PlatformRequestOptions = {}
  ): Promise<T> {
    // 调用时解析 fetch 实现（非工厂创建时）：测试 stubGlobal 全局 fetch 可生效，生产语义不变
    const doFetch = fetchImpl ?? fetch
    const token = storage.getToken()
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.headers || {}),
    }

    const res = await doFetch(`${baseUrl}${path}`, {
      method: options.method || 'GET',
      headers,
      body: options.data ? JSON.stringify(options.data) : undefined,
    })

    // 401 自动刷新
    if (res.status === 401 && !options._retried) {
      const refreshed = await refreshTokens(storage, baseUrl, doFetch)
      if (refreshed) {
        return request<T>(path, { ...options, _retried: true })
      }
      // 统一 401 登出决策点（clear + 跳转登录页 + throw，never 返回）
      expire()
    }

    const body = (await res.json().catch(() => ({}))) as PlatformApiResponse
    // DOC-073 F1（doing/77 §24）：成功判定统一为 success 契约（对齐 shared apiError 语义）
    // 后端业务错误 → 4xx/5xx + 信封（success:false + code）；HTTP 非 2xx 且无信封 → 按状态码兜底 message
    if (!body.success) {
      throw toApiError({ code: body.code, message: body.message || `请求失败 (${res.status})` })
    }

    // F-04：直接解包 data（信封 success 已抛错，解包安全；与 student/teacher 端 api() 语义一致）
    return body.data as T
  }
}