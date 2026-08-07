/**
 * DC-007 声纹注册流程收敛（SPEC §21）
 *
 * 收敛 LoginPage / SettingsPanel 两处逐字复制的双模式注册编排：
 * - remote：remoteVoiceprintEnroll → markRemoteVoiceprintEnrolled（服务端存储 + 租户暂存）
 * - local：enrollVoiceprint → issueVoiceCredential（失败 console.warn 不阻断，现状语义）→ saveVoiceCredential
 *
 * 纯 async 编排，无 React 依赖——可独立单测。
 */
import { remoteVoiceprintEnroll, issueVoiceCredential } from '../api'
import { enrollVoiceprint, saveVoiceCredential, markRemoteVoiceprintEnrolled } from './voiceprintStore'

export interface EnrollParams {
  embeddings: number[][]
  userId: string
  pseudonym: string
}

export interface EnrollResult {
  mode: 'local' | 'remote'
  /** 本次录入的模板数（embedding 段数） */
  enrolled: number
}

export async function enrollVoiceprintFlow(params: EnrollParams, mode: 'local' | 'remote'): Promise<EnrollResult> {
  if (mode === 'remote') {
    // remote 模式：embedding 传服务端存储
    const { tenantId } = await remoteVoiceprintEnroll(params.embeddings)
    // AUD-001：暂存服务端签发的租户，声纹登录 verify 时需回传租户维度
    markRemoteVoiceprintEnrolled(tenantId)
    return { mode: 'remote', enrolled: params.embeddings.length }
  }

  // local 模式：存 IndexedDB + 签发设备凭证
  await enrollVoiceprint(params.userId, params.pseudonym, params.embeddings)
  try {
    const cred = await issueVoiceCredential()
    await saveVoiceCredential(params.userId, cred)
  } catch (e) {
    // 凭证签发失败不影响声纹主流程（下次可在设置中重录补发）
    console.warn('[声纹注册] 设备凭证签发失败（不影响本次注册）:', e)
  }
  return { mode: 'local', enrolled: params.embeddings.length }
}
