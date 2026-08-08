/**
 * 前端运行时配置注入（CFG-002 配置统一纳管）
 *
 * 设计要点：
 * - 启动时从 GET /api/v1/system/config 拉取配置，覆盖本地默认值
 * - 接口失败 / 超时 → 静默降级到本地 config/*.ts 默认值，不阻塞启动
 * - 3 秒超时（AbortController），避免弱网环境卡住首屏
 * - 配置缓存在模块级变量中，整个 SPA 生命周期内只拉取一次
 *
 * 使用方式：
 * - main.tsx 启动时调用 initRemoteConfig()（fire-and-forget，不 await 阻塞渲染）
 * - 业务代码通过 getConfigValue('voiceprint.verifyThreshold', VP_VERIFY_THRESHOLD) 取值
 *   远程有值 → 用远程；远程无值 / 未加载 → 用 fallback（本地默认值）
 */

import { fetchSystemConfig } from '../api'

/**
 * 远程配置结构（对应后端 SystemConfigProperties）
 *
 * FA-12 契约收敛：仅声明有前端消费点的键，其余键由后端下发但前端忽略：
 * - voiceprint.enrollSegments/verifySegments：声纹轮数由 guideScripts 动态决定，无独立消费
 * - wakeWord.modelId：模型加载走本地 WAKE_MODEL_ID（worker 配置链路），远程改无意义
 * - tts.defaultPersona/personas：前端默认音色走性别匹配（男→小太阳/女→小星）与
 *   VOICE_PERSONAS 内置字典，远程配置会覆盖性别匹配造成行为变更，故不声明
 */
export interface RemoteConfig {
  voiceprint?: {
    verifyThreshold?: number
    maxTemplates?: number
  }
  wakeWord?: {
    windowSeconds?: number
    silenceRmsThreshold?: number
  }
  guideScripts?: {
    verify?: Array<{ prompt: string; hint: string; duration: number }>
    enroll?: Array<{ prompt: string; hint: string; duration: number }>
  }
}

/** 模块级缓存 */
let cachedConfig: RemoteConfig | null = null

/** 请求超时（ms） */
const FETCH_TIMEOUT_MS = 3000

/**
 * 初始化远程配置（启动时调用一次）
 * @returns true=成功加载，false=失败/超时（降级到本地默认值）
 */
export async function initRemoteConfig(): Promise<boolean> {
  try {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS)

    // F-2 端点收敛：具名 authFetch 接缝（ARCH-005），外部注入 3s 超时 signal
    const res = await fetchSystemConfig(controller.signal)

    clearTimeout(timer)

    if (!res.ok) {
      console.warn('[remote-config] HTTP %d, fallback to local defaults', res.status)
      return false
    }

    const body = await res.json()

    if (body.code !== 0 || !body.data) {
      console.warn('[remote-config] API error code=%d, fallback to local defaults', body.code)
      return false
    }

    cachedConfig = body.data as RemoteConfig
    return true
  } catch (err) {
    // 网络异常 / 超时 / JSON 解析失败 → 静默降级
    if (err instanceof DOMException && err.name === 'AbortError') {
      console.warn('[remote-config] Timeout (%dms), fallback to local defaults', FETCH_TIMEOUT_MS)
    } else {
      console.warn('[remote-config] Fetch failed, fallback to local defaults', err)
    }
    return false
  }
}

/**
 * 获取完整远程配置（未加载时返回 null）
 */
export function getRemoteConfig(): RemoteConfig | null {
  return cachedConfig
}

/**
 * 按点分路径取配置值（带 fallback）
 *
 * @param path  点分路径，如 'voiceprint.verifyThreshold'
 * @param fallback  远程值不存在时的默认值（通常传本地 config/*.ts 的常量）
 * @returns 远程值 ?? fallback
 *
 * @example
 * getConfigValue('voiceprint.verifyThreshold', VP_VERIFY_THRESHOLD)
 * getConfigValue('wakeWord.windowSeconds', 2.0)
 */
export function getConfigValue<T>(path: string, fallback: T): T {
  if (!cachedConfig) return fallback

  const segments = path.split('.')
  let current: unknown = cachedConfig

  for (const seg of segments) {
    if (current == null || typeof current !== 'object') {
      return fallback
    }
    current = (current as Record<string, unknown>)[seg]
  }

  // undefined → fallback；null / 0 / '' / false 是有效值，不 fallback
  return current === undefined ? fallback : (current as T)
}

/**
 * 重置缓存（仅供测试使用）
 */
export function _resetForTest(): void {
  cachedConfig = null
}
