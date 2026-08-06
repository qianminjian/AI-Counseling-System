import { useState } from 'react'
import { ConsentKeys } from '../api'

// F-9 同意键单点：引用 ConsentKeys 枚举（ARCH-005），不再本地定义字符串
const CONSENT_KEY = ConsentKeys.VOICE

/**
 * 语音功能授权弹窗（合规：《未成年人网络保护条例》《个人信息保护法》）
 * - 首次使用语音功能前必须获得确认
 * - 明确告知：音频仅用于实时分析，不存储、不上传第三方
 * - 授权状态存储在 localStorage
 */
export function useVoiceConsent() {
  const [showDialog, setShowDialog] = useState(false)

  const hasConsent = () => localStorage.getItem(CONSENT_KEY) === 'granted'

  const requestConsent = () => {
    if (hasConsent()) return true
    setShowDialog(true)
    return false
  }

  const grantConsent = () => {
    localStorage.setItem(CONSENT_KEY, 'granted')
    setShowDialog(false)
  }

  const denyConsent = () => {
    setShowDialog(false)
  }

  return { showDialog, hasConsent, requestConsent, grantConsent, denyConsent }
}

export default function VoiceConsentDialog({ onGrant, onDeny }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="bg-white rounded-2xl p-6 max-w-sm w-full shadow-xl">
        <div className="text-center mb-4">
          <span className="text-4xl">🎙️</span>
          <h3 className="text-lg font-semibold text-gray-800 mt-2">语音功能说明</h3>
        </div>

        <div className="text-sm text-gray-600 space-y-3 mb-6">
          <p>使用语音功能前，请了解以下信息：</p>
          <ul className="space-y-2 pl-1">
            <li className="flex items-start gap-2">
              <span className="text-green-500 mt-0.5">✓</span>
              <span>你的录音<strong>仅用于实时转文字和情绪分析</strong></span>
            </li>
            <li className="flex items-start gap-2">
              <span className="text-green-500 mt-0.5">✓</span>
              <span>录音<strong>不会被保存</strong>，分析完立即删除</span>
            </li>
            <li className="flex items-start gap-2">
              <span className="text-green-500 mt-0.5">✓</span>
              <span>所有分析在<strong>学校本地服务器</strong>完成，不上传到外部</span>
            </li>
            <li className="flex items-start gap-2">
              <span className="text-green-500 mt-0.5">✓</span>
              <span>只有文字内容和情绪标签会发送给 AI 小伙伴</span>
            </li>
          </ul>
          <p className="text-xs text-gray-400 mt-3">
            依据《未成年人网络保护条例》和《个人信息保护法》，
            使用语音功能需获得本人及监护人知情同意。
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
            我知道了，同意使用
          </button>
        </div>
      </div>
    </div>
  )
}
