import { useState } from 'react'
import { View, Text, Button, Input, Form } from '@tarojs/components'
import { parentRegister, parentLogin } from '../../services/index'
import type { AuthResult } from '../../services/index'
import { setToken, setRefreshToken, setUser, isAuthenticated } from '../../utils/auth'
import { redirectTo, navigateTo } from '../../utils/nav'
import { inputValue } from '../../utils/event'

type Mode = 'login' | 'register'

interface FormState {
  familyCode: string
  phone: string
  password: string
  relation: string
}

export default function VerifyPage() {
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
    redirectTo('/report')
    return null
  }

  const update = (key: keyof FormState, value: string) => {
    setForm(f => ({ ...f, [key]: value }))
    setError('')
  }

  const handleSubmit = async (e?: { preventDefault?: () => void }) => {
    e?.preventDefault?.()
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
      redirectTo('/report')
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败，请重试')
    } finally {
      setLoading(false)
    }
  }

  return (
    <View className="container verify-page">
      <View className="logo-area">
        <Text className="logo-emoji">🌈</Text>
        <Text className="page-title">MindSafe 家长端</Text>
        <Text className="page-subtitle">
          {mode === 'register' ? '输入家庭码，绑定您的孩子' : '登录后查看孩子的情绪周报'}
        </Text>
      </View>

      {/* 切换标签 */}
      <View className="tab-row">
        <Button
          className={`tab-btn ${mode === 'login' ? 'active' : ''}`}
          onClick={() => { setMode('login'); setError('') }}
        >
          登录
        </Button>
        <Button
          className={`tab-btn ${mode === 'register' ? 'active' : ''}`}
          onClick={() => { setMode('register'); setError('') }}
        >
          首次注册
        </Button>
      </View>

      <Form className="card" onSubmit={handleSubmit}>
        {/* 注册模式：家庭码 */}
        {mode === 'register' && (
          <View className="input-group">
            <Text className="input-label">家庭码</Text>
            <Input
              className="input-field"
              maxlength={6}
              placeholder="孩子注册后获得的 6 位码"
              value={form.familyCode}
              onInput={(e) => update('familyCode', inputValue(e).toUpperCase())}
              style={{ textTransform: 'uppercase', letterSpacing: '0.2em', textAlign: 'center', fontSize: '1.2em' }}
            />
            <Text className="hint-text">💡 家庭码在孩子注册成功后显示，也可在个人中心查看</Text>
          </View>
        )}

        {/* 手机号 */}
        <View className="input-group">
          <Text className="input-label">手机号</Text>
          <Input
            className="input-field"
            type="number"
            maxlength={11}
            placeholder="请输入手机号"
            value={form.phone}
            onInput={(e) => update('phone', inputValue(e))}
          />
        </View>

        {/* 密码 */}
        <View className="input-group">
          <Text className="input-label">密码</Text>
          <Input
            className="input-field"
            password
            placeholder={mode === 'register' ? '设置密码（至少 6 位）' : '请输入密码'}
            value={form.password}
            onInput={(e) => update('password', inputValue(e))}
          />
        </View>

        {/* 注册模式：关系选择 */}
        {mode === 'register' && (
          <View className="input-group">
            <Text className="input-label">您与孩子的关系</Text>
            <View className="relation-row">
              {[
                { value: 'mother', label: '👩 妈妈' },
                { value: 'father', label: '👨 爸爸' },
                { value: 'grandparent', label: '👴 祖父母' },
                { value: 'other', label: '🤝 其他' }
              ].map(r => (
                <Button
                  key={r.value}
                  className={`relation-btn ${form.relation === r.value ? 'active' : ''}`}
                  onClick={() => update('relation', r.value)}
                >
                  {r.label}
                </Button>
              ))}
            </View>
          </View>
        )}

        {error && <Text className="error-text">{error}</Text>}

        <Button
          className="btn-primary"
          formType="submit"
          disabled={loading}
        >
          {loading ? '处理中...' : mode === 'register' ? '注册并绑定' : '登录'}
        </Button>
      </Form>

      <Text className="tip-text">
        如有问题请联系学校心理老师 ·{' '}
        <Text className="privacy-link" onClick={() => navigateTo('/privacy')}>个人信息保护告知</Text>
      </Text>
    </View>
  )
}
