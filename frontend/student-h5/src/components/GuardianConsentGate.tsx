/**
 * 监护人同意验证码闭环页（P0-1 审计修复，AUTH-040，PIPL §31）
 *
 * 后端契约（AuthController）：
 * - POST /auth/guardian-consent/request  发送短信验证码到监护人手机
 * - POST /auth/guardian-consent/confirm  校验验证码并写入同意记录
 *
 * 触发时机：创建会话被 CONSENT_REQUIRED(20003) 拦截时，App 切换至本页；
 * 确认成功后回到情绪选择页重新进入对话。
 */
import { useState } from 'react'
import { requestGuardianConsent, confirmGuardianConsent } from '../api'

export default function GuardianConsentGate({ onSuccess }: { onSuccess: () => void }) {
  const [step, setStep] = useState<'phone' | 'code'>('phone')
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleSend = async () => {
    setError('')
    if (!/^1\d{10}$/.test(phone.trim())) {
      setError('请输入正确的 11 位手机号')
      return
    }
    setLoading(true)
    try {
      await requestGuardianConsent(phone.trim())
      setStep('code')
    } catch (e) {
      setError(e instanceof Error ? e.message : '验证码发送失败，请稍后再试')
    } finally {
      setLoading(false)
    }
  }

  const handleConfirm = async () => {
    setError('')
    if (!code.trim()) {
      setError('请输入短信里的验证码')
      return
    }
    setLoading(true)
    try {
      await confirmGuardianConsent(phone.trim(), code.trim())
      onSuccess()
    } catch (e) {
      setError(e instanceof Error ? e.message : '确认失败，请重试')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-b from-sky-100 to-blue-50 px-6">
      <div className="w-full max-w-sm rounded-3xl bg-white p-8 shadow-xl text-center">
        <span className="text-5xl">👨‍👩‍👧</span>
        <h2 className="mt-4 text-xl font-bold text-gray-800">需要家长同意一下哦</h2>
        <p className="mt-2 text-sm leading-relaxed text-gray-500">
          因为你还不满 14 岁，开始聊天前需要请家长确认。
          把下面这条短信验证码发给你的爸爸或妈妈就好啦～
        </p>

        {step === 'phone' ? (
          <div className="mt-6 text-left">
            <label className="text-sm font-medium text-gray-600">家长的手机号</label>
            <input
              className="mt-2 w-full rounded-xl border border-gray-200 px-4 py-3 text-base outline-none focus:border-blue-400"
              placeholder="输入家长手机号"
              inputMode="numeric"
              maxLength={11}
              value={phone}
              onChange={(e) => { setPhone(e.target.value); setError('') }}
            />
            <button
              className="mt-4 w-full rounded-full bg-blue-500 py-3 font-medium text-white active:scale-[0.98] transition-all disabled:bg-gray-300"
              onClick={handleSend}
              disabled={loading}
            >
              {loading ? '正在发送...' : '发送验证码'}
            </button>
          </div>
        ) : (
          <div className="mt-6 text-left">
            <p className="text-sm text-gray-500">验证码已发送到 {phone}</p>
            <label className="mt-3 block text-sm font-medium text-gray-600">短信验证码</label>
            <input
              className="mt-2 w-full rounded-xl border border-gray-200 px-4 py-3 text-base outline-none focus:border-blue-400"
              placeholder="输入验证码"
              inputMode="numeric"
              maxLength={6}
              value={code}
              onChange={(e) => { setCode(e.target.value); setError('') }}
            />
            <button
              className="mt-4 w-full rounded-full bg-blue-500 py-3 font-medium text-white active:scale-[0.98] transition-all disabled:bg-gray-300"
              onClick={handleConfirm}
              disabled={loading}
            >
              {loading ? '正在确认...' : '确认'}
            </button>
            <button
              className="mt-2 w-full rounded-full border border-gray-200 py-2.5 text-sm text-gray-500 active:scale-[0.98] transition-all"
              onClick={() => { setStep('phone'); setError('') }}
            >
              换个手机号 / 重新发送
            </button>
          </div>
        )}

        {error && <p className="mt-4 text-sm text-red-500">{error}</p>}
      </div>
    </div>
  )
}
