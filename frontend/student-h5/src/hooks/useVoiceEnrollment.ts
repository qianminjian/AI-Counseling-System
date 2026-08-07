/**
 * DC-007 声纹注册 hook（SPEC §21）
 *
 * { enroll, enrolling, error }：
 * - enroll(params, mode) → 编排 enrollVoiceprintFlow，失败上抛并置统一错误文案
 * - enrolling：进行中标记；error：统一文案「声音数据保存失败，请检查网络后重试」
 *
 * 调用处（LoginPage/SettingsPanel）保留各自错误面板 UI 与 setHasVoiceprint 成功分支。
 */
import { useCallback, useState } from 'react'
import { enrollVoiceprintFlow, type EnrollParams, type EnrollResult } from '../utils/voiceprintEnrollment'

export function useVoiceEnrollment() {
  const [enrolling, setEnrolling] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const enroll = useCallback(async (params: EnrollParams, mode: 'local' | 'remote'): Promise<EnrollResult> => {
    setEnrolling(true)
    setError(null)
    try {
      return await enrollVoiceprintFlow(params, mode)
    } catch (e) {
      setError('声音数据保存失败，请检查网络后重试')
      throw e
    } finally {
      setEnrolling(false)
    }
  }, [])

  return { enroll, enrolling, error }
}
