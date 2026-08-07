import { useState, useEffect } from 'react'
import { Card, Form, Input, Button, message, Divider } from 'antd'
import { UserOutlined, LockOutlined, WechatOutlined } from '@ant-design/icons'
import { api, setToken, setRefreshToken } from '../api'

export default function Login({ onLogin }) {
  const [loading, setLoading] = useState(false)
  const [wecomUrl, setWecomUrl] = useState(null)

  useEffect(() => {
    api('/auth/wecom/auth-url').then(d => {
      if (d.enabled) setWecomUrl(d.authUrl)
    }).catch(() => {})
  }, [])

  const handleSubmit = async (values) => {
    setLoading(true)
    try {
      const data = await api('/auth/login', {
        method: 'POST',
        body: JSON.stringify(values),
      })
      setToken(data.token)
      if (data.refreshToken) setRefreshToken(data.refreshToken)
      message.success(`欢迎回来，${data.displayName}！`)
      onLogin({
        userId: data.userId,
        userType: data.userType,
        displayName: data.displayName,
        mustChangePassword: data.mustChangePassword,
      })
    } catch (e) {
      message.error(e.message || '登录失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{
      minHeight: '100vh',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      background: 'var(--ms-bg)',
      position: 'relative',
      overflow: 'hidden',
    }}>
      {/* 右上青绿渐变光晕（doing/75 方案 A：克制光晕，非整屏渐变） */}
      <div style={{
        position: 'absolute',
        top: -120,
        right: -120,
        width: 420,
        height: 420,
        borderRadius: '50%',
        background: 'radial-gradient(circle, rgba(43,168,160,0.18) 0%, rgba(43,168,160,0) 70%)',
      }} />
      <Card style={{ width: 380, borderRadius: 'var(--ms-radius-card)', boxShadow: 'var(--ms-shadow-card)', position: 'relative' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <div style={{
            width: 72,
            height: 72,
            margin: '0 auto',
            borderRadius: '50%',
            background: 'var(--ms-primary-soft)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 36,
            boxShadow: 'var(--ms-shadow-tab)',
          }}>🛡️</div>
          <h2 style={{ margin: '16px 0 4px', fontSize: 20 }}>MindSafe 教师工作台</h2>
          <p style={{ color: 'var(--ms-text-muted)', fontSize: 13 }}>AI 小学生心理辅导系统</p>
        </div>
        <Form onFinish={handleSubmit} size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名（如：李老师）" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block loading={loading}>
              登录
            </Button>
          </Form.Item>
        </Form>
        {wecomUrl && (
          <>
            <Divider plain style={{ margin: '12px 0' }}>或</Divider>
            <Button block icon={<WechatOutlined />} style={{ color: 'var(--ms-success)', borderColor: 'var(--ms-success)' }}
              onClick={() => { window.location.href = wecomUrl }}>
              企业微信登录
            </Button>
          </>
        )}
      </Card>
    </div>
  )
}
