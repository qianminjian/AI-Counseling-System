import { useState } from 'react'
import { ConsentKeys } from '../api'
import { readLocalStorageSafe, writeLocalStorageSafe } from '../utils/storage'

/** 语音唤醒授权状态（与按住说话授权分离，单独告知单独同意） */
// F-9 同意键单点：引用 ConsentKeys 枚举（ARCH-005），不再本地定义字符串
const VOICE_CALL_CONSENT_KEY = ConsentKeys.VOICE_CALL
// AUD-065：授权读写经 storage.ts 安全封装（隐私模式/存储禁用不抛 SecurityError）

/**
 * 语音唤醒单独授权 Hook（design/28 §1.4 合规与授权）
 *
 * 与现有按住说话授权（VoiceConsentDialog）分离：唤醒是"被动监听"，
 * 必须单独告知、单独同意，且如实披露监听范围与数据处理方式。
 */
export function useVoiceCallConsent() {
  const [showDialog, setShowDialog] = useState(false)

  const hasConsent = () => readLocalStorageSafe(VOICE_CALL_CONSENT_KEY, '') === 'granted'

  /** 请求授权：已授权返回 true；未授权弹出说明弹窗并返回 false */
  const requestConsent = () => {
    if (hasConsent()) return true
    setShowDialog(true)
    return false
  }

  const grantConsent = () => {
    writeLocalStorageSafe(VOICE_CALL_CONSENT_KEY, 'granted')
    setShowDialog(false)
  }

  const denyConsent = () => {
    setShowDialog(false)
  }

  return { showDialog, hasConsent, requestConsent, grantConsent, denyConsent }
}

/**
 * 语音唤醒授权弹窗（如实披露，不含糊）：
 * - 监听仅限本次对话内（进入对话才开始，离开立即停止）；
 * - 唤醒检测只在设备本地处理（不上传）；
 * - 唤醒后说的话经设备语音服务转文字。
 */
export default function VoiceCallConsentDialog({ onGrant, onDeny }) {
  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/40 p-4">
      <div className="bg-white rounded-2xl p-6 max-w-sm w-full shadow-xl">
        <div className="text-center mb-4">
          <span className="text-4xl">🐬</span>
          <h3 className="text-lg font-semibold text-gray-800 mt-2">语音唤醒说明</h3>
        </div>

        <div className="text-sm text-gray-600 space-y-3 mb-6">
          <p>开启后，你可以直接叫"哈喽波波"和它说话。请了解以下信息：</p>
          <ul className="space-y-2 pl-1">
            <li className="flex items-start gap-2">
              <span className="text-green-500 mt-0.5">✓</span>
              <span>波波会用麦克风<strong>听你有没有叫它</strong>，这部分<strong>只在你的手机/电脑上处理，不会上传</strong></span>
            </li>
            <li className="flex items-start gap-2">
              <span className="text-green-500 mt-0.5">✓</span>
              <span>只在<strong>这次聊天里</strong>听：进入聊天才开始，<strong>离开聊天立即停止</strong></span>
            </li>
            <li className="flex items-start gap-2">
              <span className="text-green-500 mt-0.5">✓</span>
              <span>叫醒波波后，你说的话会用<strong>设备的语音服务转成文字</strong>发给 AI 小伙伴</span>
            </li>
            <li className="flex items-start gap-2">
              <span className="text-green-500 mt-0.5">✓</span>
              <span>随时可以在设置里<strong>关掉</strong>，关掉后立刻不再听</span>
            </li>
          </ul>
          <p className="text-xs text-gray-400 mt-3">
            依据《未成年人网络保护条例》和《个人信息保护法》，
            使用语音唤醒需获得本人及监护人知情同意。
          </p>
        </div>

        <div className="flex gap-3">
          <button
            onClick={onDeny}
            className="flex-1 py-3 rounded-xl border border-gray-200 text-sm text-gray-600 hover:bg-gray-50 transition-colors"
          >
            暂不使用
          </button>
          <button
            onClick={onGrant}
            className="flex-1 py-3 rounded-xl bg-indigo-500 text-white text-sm font-medium hover:bg-indigo-600 transition-colors"
          >
            我知道了，开启
          </button>
        </div>
      </div>
    </div>
  )
}
