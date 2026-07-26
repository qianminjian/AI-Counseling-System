import { useState } from 'react'
import { trialRegister, setToken, setRefreshToken, setUser } from '../api'


/**
 * 试用注册页（邀请码 + 昵称 + 年龄 + 角色）
 * D1=成人体验者 / D2=邀请码 / D3=邀请码+昵称+年龄
 */
export default function TrialRegister({ consentVersion, onRegistered }) {
  const [form, setForm] = useState({
    inviteCode: '',
    pseudonym: '',
    gender: '',
    age: '',
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const update = (key, value) => {
    setForm((f) => ({ ...f, [key]: value }))
    setError('')
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    if (!form.inviteCode.trim() || !form.pseudonym.trim() || !form.age || !form.gender) {
      setError('请填写所有必填项')
      return
    }
    const age = parseInt(form.age, 10)
    if (isNaN(age) || age < 6 || age > 120) {
      setError('请输入有效年龄（6-120）')
      return
    }
    if (form.pseudonym.trim().length < 2 || form.pseudonym.trim().length > 12) {
      setError('昵称长度 2-12 字')
      return
    }

    setLoading(true)
    setError('')
    try {
      const data = await trialRegister({
        inviteCode: form.inviteCode.trim(),
        pseudonym: form.pseudonym.trim(),
        age,
        role: 'student',
        gender: form.gender,
        consentVersion,
      })
      // 存储 token 和用户信息
      setToken(data.token)
      if (data.refreshToken) setRefreshToken(data.refreshToken)
      setUser({
        userId: data.userId,
        userType: data.userType,
        pseudonym: data.pseudonym,
        gender: form.gender,
      })
      onRegistered(data)
    } catch (err) {
      setError(err.message || '注册失败，请检查邀请码')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex flex-col items-center justify-center p-6"
      style={{ background: 'linear-gradient(to bottom, #f0f7ff, #e8f4f8)' }}>
      <div className="w-full max-w-sm">
        {/* 标题 */}
        <div className="text-center mb-8">
          <div className="text-5xl mb-3">🌟</div>
          <h1 className="text-2xl font-bold text-gray-800">欢迎体验 MindSafe</h1>
          <p className="text-sm text-gray-500 mt-2">AI 情绪陪伴助手 · 试用版</p>
        </div>

        {/* 注册表单 */}
        <form onSubmit={handleSubmit} className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6 space-y-5">
          {/* 邀请码 */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              邀请码 <span className="text-red-400">*</span>
            </label>
            <input
              value={form.inviteCode}
              onChange={(e) => update('inviteCode', e.target.value)}
              placeholder="请输入邀请码"
              className="w-full px-4 py-3 rounded-xl border border-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-200 text-sm"
            />
          </div>

          {/* 昵称 */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              昵称 <span className="text-red-400">*</span>
              <span className="text-gray-400 font-normal ml-1">（2-12 字，不收集真实姓名）</span>
            </label>
            <input
              value={form.pseudonym}
              onChange={(e) => update('pseudonym', e.target.value)}
              placeholder="给自己取个名字吧"
              maxLength={12}
              className="w-full px-4 py-3 rounded-xl border border-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-200 text-sm"
            />
          </div>

          {/* 性别 */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              性别 <span className="text-red-400">*</span>
            </label>
            <div className="grid grid-cols-2 gap-3">
              <button
                type="button"
                onClick={() => update('gender', 'male')}
                className={`flex items-center justify-center gap-2 py-3.5 rounded-xl border-2 transition-all text-sm font-medium ${
                  form.gender === 'male'
                    ? 'border-blue-400 bg-blue-50 text-blue-700'
                    : 'border-gray-100 text-gray-500 hover:border-gray-200'
                }`}
              >
                <span className="text-xl">👦</span> 男生
              </button>
              <button
                type="button"
                onClick={() => update('gender', 'female')}
                className={`flex items-center justify-center gap-2 py-3.5 rounded-xl border-2 transition-all text-sm font-medium ${
                  form.gender === 'female'
                    ? 'border-pink-400 bg-pink-50 text-pink-700'
                    : 'border-gray-100 text-gray-500 hover:border-gray-200'
                }`}
              >
                <span className="text-xl">👧</span> 女生
              </button>
            </div>
          </div>

          {/* 年龄 */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              年龄 <span className="text-red-400">*</span>
            </label>
            <input
              type="number"
              value={form.age}
              onChange={(e) => update('age', e.target.value)}
              placeholder="您的年龄"
              min={6}
              max={120}
              className="w-full px-4 py-3 rounded-xl border border-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-200 text-sm"
            />
            {form.age && parseInt(form.age) < 14 && (
              <p className="mt-1.5 text-xs text-amber-600 bg-amber-50 px-3 py-2 rounded-lg">
                ⚠️ 不满 14 周岁建议在家长陪同下使用
              </p>
            )}
          </div>

          {/* 邀请码说明 */}
          <p className="text-xs text-gray-400 bg-gray-50 px-4 py-2.5 rounded-xl">
            💡 邀请码由学校心理老师发放，如体验测试可使用 <strong>DEMO2026</strong>
          </p>

          {/* 错误提示 */}
          {error && (
            <p className="text-sm text-red-500 bg-red-50 px-4 py-2.5 rounded-xl">{error}</p>
          )}

          {/* 提交 */}
          <button
            type="submit"
            disabled={loading}
            className={`w-full py-4 rounded-full text-white font-medium text-lg transition-all ${
              loading
                ? 'bg-gray-300 cursor-wait'
                : 'bg-blue-500 hover:bg-blue-600 active:scale-[0.98] shadow-lg'
            }`}
          >
            {loading ? '正在进入...' : '开始体验 🚀'}
          </button>
        </form>

        <p className="text-center text-xs text-gray-400 mt-4">
          试用版 · 邀请码由项目组发放
        </p>
      </div>
    </div>
  )
}
