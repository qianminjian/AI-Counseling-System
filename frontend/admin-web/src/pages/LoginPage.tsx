import { useState } from 'react'
import { Button, Form, Input, message } from 'antd'
import { platformLogin } from '../api'

interface LoginPageProps {
  onLogin: (role: string, name: string) => void
}

/** 平台管理员登录页（ADMIN-P0-04，青屿风格 §8.5） */
export default function LoginPage({ onLogin }: LoginPageProps) {
  const [loading, setLoading] = useState(false)

  const handleFinish = async (values: { username: string; password: string }) => {
    setLoading(true)
    try {
      const result = await platformLogin(values.username, values.password)
      onLogin(result.role, result.displayName)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '登录失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--ms-bg)' }}>
      <div style={{ width: 380, padding: 40, background: 'var(--ms-card)', borderRadius: 'var(--ms-radius-card)', boxShadow: 'var(--ms-shadow-card)' }}>
        <h1 style={{ margin: '0 0 8px', fontSize: 22, color: 'var(--ms-text)' }}>MindSafe 平台管理后台</h1>
        <p style={{ margin: '0 0 24px', color: 'var(--ms-text-muted)', fontSize: 13 }}>独立平台账号登录（PLATFORM_ 登录态）</p>
        <Form layout="vertical" onFinish={handleFinish}>
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input autoComplete="username" placeholder="平台管理员用户名" />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password autoComplete="current-password" placeholder="密码" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading} style={{ background: 'var(--ms-primary)' }}>
            登录
          </Button>
        </Form>
      </div>
    </div>
  )
}
