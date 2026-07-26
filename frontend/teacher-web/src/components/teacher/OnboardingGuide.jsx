import { useState } from 'react'
import { Modal, Steps, Button } from 'antd'
import {
  DashboardOutlined, AlertOutlined, TeamOutlined, BellOutlined, SettingOutlined,
} from '@ant-design/icons'

const ONBOARDING_KEY = 'mindsafe_onboarding_done'

const STEPS = [
  {
    icon: <DashboardOutlined style={{ fontSize: 32, color: '#1677ff' }} />,
    title: '工作台',
    desc: '这里是你的工作首页，展示今日会话、预警概览、高风险学生和满意度数据。每天打开先看这里。',
  },
  {
    icon: <AlertOutlined style={{ fontSize: 32, color: '#ff4d4f' }} />,
    title: '预警队列',
    desc: 'AI 检测到学生风险信号时会自动生成预警。红色预警需要立即处理，点击"接管"进行线下干预。',
  },
  {
    icon: <TeamOutlined style={{ fontSize: 32, color: '#52c41a' }} />,
    title: '学生管理',
    desc: '查看每位学生的心理档案、历史会话和 AI 摘要。支持批量导入学生、添加教师备注。',
  },
  {
    icon: <BellOutlined style={{ fontSize: 32, color: '#faad14' }} />,
    title: '实时通知',
    desc: '风险预警会通过浏览器弹窗 + 声音实时推送。红色预警弹窗不会自动关闭，确保你不会错过。',
  },
  {
    icon: <SettingOutlined style={{ fontSize: 32, color: '#722ed1' }} />,
    title: '管理控制台',
    desc: '管理员可以导入学生、查看审计日志、下载模板。平台总览提供跨学校的全局数据。',
  },
]

/** 新手引导弹窗（首次登录显示） */
export default function OnboardingGuide() {
  const [visible, setVisible] = useState(() => !localStorage.getItem(ONBOARDING_KEY))
  const [current, setCurrent] = useState(0)

  const finish = () => {
    localStorage.setItem(ONBOARDING_KEY, 'true')
    setVisible(false)
  }

  if (!visible) return null

  const step = STEPS[current]

  return (
    <Modal open={visible} footer={null} onCancel={finish} width={440} centered>
      <div style={{ textAlign: 'center', padding: '16px 0' }}>
        <div style={{ marginBottom: 16 }}>{step.icon}</div>
        <h3 style={{ fontSize: 18, marginBottom: 8 }}>{step.title}</h3>
        <p style={{ color: '#666', fontSize: 14, lineHeight: 1.7, minHeight: 60 }}>{step.desc}</p>

        <Steps current={current} size="small" style={{ margin: '20px 0' }}
          items={STEPS.map((_, i) => ({ title: '' }))} />

        <div style={{ display: 'flex', justifyContent: 'space-between' }}>
          <Button onClick={finish} type="text" size="small">跳过</Button>
          <div style={{ display: 'flex', gap: 8 }}>
            {current > 0 && <Button onClick={() => setCurrent(c => c - 1)}>上一步</Button>}
            {current < STEPS.length - 1 ? (
              <Button type="primary" onClick={() => setCurrent(c => c + 1)}>下一步</Button>
            ) : (
              <Button type="primary" onClick={finish}>开始使用 🎉</Button>
            )}
          </div>
        </div>
      </div>
    </Modal>
  )
}
