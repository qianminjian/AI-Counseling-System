/**
 * 心理工具箱 API 层（F-2，design/36 §五 API 契约）
 *
 * 后端契约（ToolboxController）：
 * - GET  /toolbox            按年级过滤的工具清单
 * - GET  /toolbox/sos        SOS 场景目标态工具
 * - POST /toolbox/mood-check 练习前后心情记录（toolId/preMood/postMood）
 *
 * reportSosEvent 为 fire-and-forget：SOS 打开上报任何失败（含断网）绝不抛出，
 * 不得阻塞 SOS 界面（design/36 §3.4）。
 */
import { api } from '../api'

export interface ToolboxTool {
  toolId: string
  title: string
  emoji: string
  durationSec: number
  minGrade: number
  preMoodCheck: boolean
  postMoodCheck: boolean
  rewardBadge: string | null
  category: string
}

export interface MoodCheckResult {
  effect: string
  needsAttention: boolean
}

/** 获取当前学生可用工具清单（后端按年级过滤） */
export function fetchToolboxTools(): Promise<ToolboxTool[]> {
  return api('/toolbox')
}

/** 获取 SOS 场景目标态工具（断网时界面仍可静态打开，接口失败由调用方兜底） */
export function fetchSosTools(): Promise<ToolboxTool[]> {
  return api('/toolbox/sos')
}

/** 记录练习前后心情（后端判定效果，恶化时 needsAttention=true） */
export function recordMoodCheck(toolId: string, preMood: number, postMood: number): Promise<MoodCheckResult> {
  return api('/toolbox/mood-check', {
    method: 'POST',
    body: JSON.stringify({ toolId, preMood, postMood }),
  })
}

/**
 * SOS 打开事件上报（fire-and-forget）。
 * 端点可能尚未部署（后端余量），任何失败静默吞掉——SOS 界面可用性优先于埋点。
 */
export async function reportSosEvent(): Promise<void> {
  try {
    await api('/sos/events', { method: 'POST', body: JSON.stringify({}) })
  } catch {
    // 静默：上报失败绝不阻塞 SOS 界面
  }
}
