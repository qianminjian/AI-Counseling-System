import { useState } from 'react'
import { trialRegister, setToken, setRefreshToken, setUser } from '../api'

/** 家长绑定（POST /api/v1/parent/auth/register） */
async function parentBind(familyCode, phone, password, relation) {
  const res = await fetch('/api/v1/parent/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ familyCode, phone, password, relation }),
  })
  const json = await res.json()
  if (!json.success) throw new Error(json.message || '绑定失败')
  return json.data
}


/**
 * 家庭码成功页 + 家长手机绑定表单
 * 解决 Bug：不满14岁提示需家长陡同后，无手机号管理衔接（Pad 端死胡同）
 */
function FamilyCodePage({ familyCode, onDone }) {
  const [showBind, setShowBind] = useState(false)
  const [phone, setPhone] = useState('')
  const [password, setPassword] = useState('')
  const [relation, setRelation] = useState('爸爸')
  const [binding, setBinding] = useState(false)
  const [bindResult, setBindResult] = useState(null) // 'success' | error message

  const handleBind = async (e) => {
    e.preventDefault()
    if (!/^1[3-9]\d{9}$/.test(phone)) {
      setBindResult('请输入正确的手机号')
      return
    }
    if (password.length < 6) {
      setBindResult('密码至少 6 位')
      return
    }
    setBinding(true)
    setBindResult(null)
    try {
      await parentBind(familyCode, phone, password, relation)
      setBindResult('success')
    } catch (err) {
      setBindResult(err.message || '绑定失败')
    } finally {
      setBinding(false)
    }
  }

  return (
    <div className="min-h-screen flex flex-col items-center justify-center p-6"
      style={{ background: 'linear-gradient(to bottom, #f0fff4, #e8f8f0)' }}>
      <div className="w-full max-w-sm text-center">
        <div className="text-5xl mb-4">🎉</div>
        <h1 className="text-2xl font-bold text-gray-800 mb-2">注册成功！</h1>
        <p className="text-sm text-gray-500 mb-6">请把这个家庭码告诉爸爸妈妈</p>

        <div className="bg-white rounded-2xl shadow-sm border-2 border-dashed border-green-300 p-6 mb-6">
          <p className="text-xs text-gray-400 mb-2">我的家庭码</p>
          <p className="text-4xl font-mono font-bold tracking-[0.3em] text-green-600">{familyCode}</p>
          <p className="text-xs text-gray-400 mt-3">爸爸妈妈用这个码就能绑定你，查看你的情绪周报</p>
        </div>

        {/* 家长绑定区域 */}
        {bindResult === 'success' ? (
          <div className="bg-green-50 border border-green-200 rounded-2xl p-4 mb-6 text-green-700 text-sm">
            ✅ 家长绑定成功！爸爸妈妈可以查看你的情绪周报了
          </div>
        ) : showBind ? (
          <form onSubmit={handleBind} className="bg-white rounded-2xl shadow-sm border border-gray-100 p-5 mb-6 text-left space-y-4">
            <p className="text-sm font-medium text-gray-700 text-center">👨‍👩‍👧 家长绑定（可选）</p>

            {/* 关系 */}
            <div className="grid grid-cols-3 gap-2">
              {['爸爸', '妈妈', '其他'].map((r) => (
                <button key={r} type="button" onClick={() => setRelation(r)}
                  className={`py-2.5 rounded-xl border-2 text-sm font-medium transition-all ${
                    relation === r ? 'border-green-400 bg-green-50 text-green-700' : 'border-gray-100 text-gray-500'
                  }`}
                >{r}</button>
              ))}
            </div>

            {/* 手机号 */}
            <input
              type="tel" value={phone} onChange={(e) => { setPhone(e.target.value); setBindResult(null) }}
              placeholder="家长手机号" maxLength={11}
              className="w-full px-4 py-3 rounded-xl border border-gray-200 focus:outline-none focus:ring-2 focus:ring-green-200 text-sm"
            />

            {/* 密码 */}
            <input
              type="password" value={password} onChange={(e) => { setPassword(e.target.value); setBindResult(null) }}
              placeholder="设置密码（至少 6 位）" maxLength={20}
              className="w-full px-4 py-3 rounded-xl border border-gray-200 focus:outline-none focus:ring-2 focus:ring-green-200 text-sm"
            />

            {bindResult && bindResult !== 'success' && (
              <p className="text-xs text-red-500 bg-red-50 px-3 py-2 rounded-lg">{bindResult}</p>
            )}

            <button type="submit" disabled={binding}
              className={`w-full py-3.5 rounded-full text-white font-medium transition-all ${
                binding ? 'bg-gray-300 cursor-wait' : 'bg-green-500 hover:bg-green-600 active:scale-[0.98]'
              }`}
            >{binding ? '绑定中...' : '确认绑定'}</button>
          </form>
        ) : (
          <button
            onClick={() => setShowBind(true)}
            className="w-full py-3.5 rounded-full border-2 border-green-300 text-green-600 font-medium mb-4 hover:bg-green-50 active:scale-[0.98] transition-all"
          >
            👨‍👩‍👧 我是家长，现在绑定手机号
          </button>
        )}

        <button
          onClick={onDone}
          className="w-full py-4 rounded-full text-white font-medium text-lg bg-green-500 hover:bg-green-600 active:scale-[0.98] shadow-lg transition-all"
        >
          开始使用 🚀
        </button>
      </div>
    </div>
  )
}

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
  const [familyCode, setFamilyCode] = useState(null) // 注册成功后显示

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
        familyCode: data.familyCode,
      })
      // 显示家庭码成功页
      if (data.familyCode) {
        setFamilyCode(data.familyCode)
      } else {
        onRegistered(data)
      }
    } catch (err) {
      setError(err.message || '注册失败，请检查邀请码')
    } finally {
      setLoading(false)
    }
  }

 // 注册成功：显示家庭码 + 家长绑定入口
  if (familyCode) {
    return <FamilyCodePage familyCode={familyCode} onDone={() => onRegistered()} />
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
