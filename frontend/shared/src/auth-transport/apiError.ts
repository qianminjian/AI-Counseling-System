/**
 * DC-005 共享认证传输模块：统一业务错误模型
 * （SPEC §19：ApiError(code) + toApiError）
 *
 * 后端 BizException → HTTP 200 + body.code 约定；调用方可按 code 分支处理
 * （如 CONSENT_REQUIRED 20003 → 监护人同意门禁）。
 */
export class ApiError extends Error {
  code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

/** 后端无码业务错误 → 缺省 code 0 / message 请求失败 */
export function toApiError(json: { code?: number; message?: string }): ApiError {
  return new ApiError(json.code ?? 0, json.message || '请求失败')
}
