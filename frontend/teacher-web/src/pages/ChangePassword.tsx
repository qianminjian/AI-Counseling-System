import { useState } from 'react'
import { Card, Form, Input, Button, message } from 'antd'
import { LockOutlined, KeyOutlined } from '@ant-design/icons'
import { api } from '../api'

/**
 * 首次登录强制改密页（方案 B：临时密码 + 首次改密）
 * mustChangePassword=true 时由 App.jsx 路由到此页，不可跳过
 */
export default function ChangePassword({ userName, onChanged }) {
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (values) => {
    if (values.newPassword !== values.confirmPassword) {
      message.error('两次输入的新密码不一致')
      return
    }
    setLoading(true)
    try {
      await api('/auth/change-password', {
        method: 'POST',
        body: JSON.stringify({
          oldPassword: values.oldPassword,
          newPassword: values.newPassword,
        }),
      })
      message.success('密码修改成功！')
      onChanged()
    } catch (e) {
      message.error(e.message || '修改失败')
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
      <Card style={{ width: 400, borderRadius: 12, boxShadow: '0 8px 32px rgba(0,0,0,0.1)' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <div style={{ fontSize: 40 }}>🔑</div>
          <h2 style={{ margin: '8px 0 4px', fontSize: 20 }}>首次登录 · 设置密码</h2>
          <p style={{ color: '#999', fontSize: 13 }}>
            {userName ? `${userName}，请` : '请'}修改临时密码后继续使用
          </p>
        </div>
        <Form onFinish={handleSubmit} size="large" layout="vertical">
          <Form.Item
            name="oldPassword"
            label="临时密码"
            rules={[{ required: true, message: '请输入临时密码' }]}
          >
            <Input.Password prefix={<KeyOutlined />} placeholder="种子文件中的临时密码" />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 8, message: '密码至少 8 位' },
            ]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="至少 8 位" />
          </Form.Item>
          <Form.Item
            name="confirmPassword"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请确认新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) {
                    return Promise.resolve()
                  }
                  return Promise.reject(new Error('两次密码不一致'))
                },
              }),
            ]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="再次输入新密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block loading={loading}>
              确认修改
            </Button>
          </Form.Item>
        </Form>
        <p style={{ textAlign: 'center', color: '#999', fontSize: 12, margin: 0 }}>
          ⚠️ 首次登录必须修改密码，修改后方可使用教师工作台
        </p>
      </Card>
    </div>
  )
}
