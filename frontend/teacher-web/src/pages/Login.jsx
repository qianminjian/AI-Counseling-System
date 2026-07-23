import { useState } from 'react'
import { Card, Form, Input, Button, message } from 'antd'
import { UserOutlined, LockOutlined } from '@ant-design/icons'
import { api, setToken } from '../api'

export default function Login({ onLogin }) {
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (values) => {
    setLoading(true)
    try {
      const data = await api('/auth/login', {
        method: 'POST',
        body: JSON.stringify(values),
      })
      setToken(data.token)
      message.success(`欢迎回来，${data.displayName}！`)
      onLogin({ userId: data.userId, userType: data.userType, displayName: data.displayName })
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
      background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
    }}>
      <Card style={{ width: 380, borderRadius: 12, boxShadow: '0 8px 32px rgba(0,0,0,0.1)' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <div style={{ fontSize: 40 }}>🛡️</div>
          <h2 style={{ margin: '8px 0 4px', fontSize: 20 }}>MindSafe 教师工作台</h2>
          <p style={{ color: '#999', fontSize: 13 }}>AI 小学生心理辅导系统</p>
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
      </Card>
    </div>
  )
}
