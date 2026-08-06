import { useState } from 'react'
import type { FormEvent, ChangeEvent } from 'react'
import { useNavigate } from 'react-router'
import { parentRegister, parentLogin } from '../../api/index'
import type { AuthResult } from '../../api/index'
import { setToken, setRefreshToken, setUser, isAuthenticated } from '../../utils/auth'

type Mode = 'login' | 'register'

interface FormState {
  familyCode: string
  phone: string
  password: string
  relation: string
}

export default function VerifyPage() {
  const navigate = useNavigate()
  const [mode, setMode] = useState<Mode>('login')
  const [form, setForm] = useState<FormState>({
    familyCode: '',
    phone: '',
    password: '',
    relation: 'mother'
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  if (isAuthenticated()) {
    navigate('/report', { replace: true })
    return null
  }

  const update = (key: keyof FormState, value: string) => {
    setForm(f => ({ ...f, [key]: value }))
    setError('')
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setError('')

    if (!form.phone || form.phone.length !== 11) {
      setError('请输入正确的 11 位手机号')
      return
    }
    if (!form.password || form.password.length < 6) {
      setError('密码至少 6 位')
      return
    }
    if (mode === 'register' && !form.familyCode.trim()) {
      setError('请输入孩子给您的家庭码')
      return
    }

    setLoading(true)
    try {
      let res
      if (mode === 'register') {
        res = await parentRegister({
          familyCode: form.familyCode.trim(),
          phone: form.phone,
          password: form.password,
          relation: form.relation
        })
      } else {
        res = await parentLogin({
          phone: form.phone,
          password: form.password
        })
      }

      const data = (res.data ?? res) as AuthResult
      setToken(data.token)
      if (data.refreshToken) setRefreshToken(data.refreshToken)
      setUser({
        parentId: data.parentId,
        displayName: data.displayName,
        children: data.children || []
      })
      navigate('/report', { replace: true })
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败，请重试')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="container verify-page">
      <div className="logo-area">
        <span className="logo-emoji">🌈</span>
        <h1 className="page-title">MindSafe 家长端</h1>
        <p className="page-subtitle">
          {mode === 'register' ? '输入家庭码，绑定您的孩子' : '登录后查看孩子的情绪周报'}
        </p>
      </div>

      {/* 切换标签 */}
      <div className="tab-row">
        <button
          className={`tab-btn ${mode === 'login' ? 'active' : ''}`}
          onClick={() => { setMode('login'); setError('') }}
        >
          登录
        </button>
        <button
          className={`tab-btn ${mode === 'register' ? 'active' : ''}`}
          onClick={() => { setMode('register'); setError('') }}
        >
          首次注册
        </button>
      </div>

      <form className="card" onSubmit={handleSubmit}>
        {/* 注册模式：家庭码 */}
        {mode === 'register' && (
          <div className="input-group">
            <label className="input-label">家庭码</label>
            <input
              className="input-field"
              maxLength={6}
              placeholder="孩子注册后获得的 6 位码"
              value={form.familyCode}
              onChange={(e: ChangeEvent<HTMLInputElement>) => update('familyCode', e.target.value.toUpperCase())}
              style={{ textTransform: 'uppercase', letterSpacing: '0.2em', textAlign: 'center', fontSize: '1.2em' }}
            />
            <p className="hint-text">💡 家庭码在孩子注册成功后显示，也可在个人中心查看</p>
          </div>
        )}

        {/* 手机号 */}
        <div className="input-group">
          <label className="input-label">手机号</label>
          <input
            className="input-field"
            type="tel"
            maxLength={11}
            placeholder="请输入手机号"
            value={form.phone}
            onChange={(e: ChangeEvent<HTMLInputElement>) => update('phone', e.target.value)}
          />
        </div>

        {/* 密码 */}
        <div className="input-group">
          <label className="input-label">密码</label>
          <input
            className="input-field"
            type="password"
            placeholder={mode === 'register' ? '设置密码（至少 6 位）' : '请输入密码'}
            value={form.password}
            onChange={(e: ChangeEvent<HTMLInputElement>) => update('password', e.target.value)}
          />
        </div>

        {/* 注册模式：关系选择 */}
        {mode === 'register' && (
          <div className="input-group">
            <label className="input-label">您与孩子的关系</label>
            <div className="relation-row">
              {[
                { value: 'mother', label: '👩 妈妈' },
                { value: 'father', label: '👨 爸爸' },
                { value: 'grandparent', label: '👴 祖父母' },
                { value: 'other', label: '🤝 其他' }
              ].map(r => (
                <button
                  key={r.value}
                  type="button"
                  className={`relation-btn ${form.relation === r.value ? 'active' : ''}`}
                  onClick={() => update('relation', r.value)}
                >
                  {r.label}
                </button>
              ))}
            </div>
          </div>
        )}

        {error && <p className="error-text">{error}</p>}

        <button
          className="btn-primary"
          type="submit"
          disabled={loading}
        >
          {loading ? '处理中...' : mode === 'register' ? '注册并绑定' : '登录'}
        </button>
      </form>

      <p className="tip-text">
        如有问题请联系学校心理老师 ·{' '}
        <a href="/parent/privacy">个人信息保护告知</a>
      </p>
    </div>
  )
}
