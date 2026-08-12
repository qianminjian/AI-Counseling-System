/**
 * 设备级同意键单点（F-9，ARCH-005；doing/94 R-001 从 api.ts 迁出独立模块）
 *
 * 告知同意 / 语音授权 / 语音通话授权键 + 读写，各组件只引用本模块，不再各自定义字符串。
 * 设备级标记存 localStorage（跨会话保持），经 readLocalStorageSafe/writeLocalStorageSafe
 * 安全封装（隐私模式/存储禁用下不抛 SecurityError → 白屏，AUD-065）。
 */
import { readLocalStorageSafe, writeLocalStorageSafe } from './storage'

export const ConsentKeys = {
  NOTICE: 'mindsafe_consent_v1',
  VOICE: 'mindsafe_voice_consent_v1',
  VOICE_CALL: 'mindsafe_voicecall_consent_v1',
} as const

/** 旧版告知同意键（mindsafe_consent_done），一次性迁移兼容读取 */
const LEGACY_NOTICE_KEY = 'mindsafe_consent_done'

/** 设备是否已完成告知同意（跨 tab 保持；旧键存在时自动迁移到新键） */
export function isConsentDone() {
  // AUD-065：裸 localStorage 改安全封装（隐私模式/存储禁用下不抛 SecurityError → 白屏）
  if (readLocalStorageSafe(ConsentKeys.NOTICE, '') === '1') return true
  if (readLocalStorageSafe(LEGACY_NOTICE_KEY, '') === '1') {
    writeLocalStorageSafe(ConsentKeys.NOTICE, '1')
    return true
  }
  return false
}

export function markConsentDone() {
  writeLocalStorageSafe(ConsentKeys.NOTICE, '1')
}
