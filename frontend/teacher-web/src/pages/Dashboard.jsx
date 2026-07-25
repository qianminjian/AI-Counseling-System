import { useState, useEffect, useRef, useCallback } from 'react'
import { Layout, Menu, Badge, List, Tag, Card, Table, Button, Space, Typography, message } from 'antd'
import {
  BellOutlined, WarningOutlined, TeamOutlined, LogoutOutlined, CheckOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { api } from '../api'

const { Header, Sider, Content } = Layout
const { Title, Text } = Typography

const RISK_COLORS = { 3: 'red', 2: 'orange', 1: 'gold', 0: 'default' }
const RISK_LABELS = { 3: '红色', 2: '橙色', 1: '黄色', 0: '绿色' }

/** 轮询间隔（毫秒） */
const POLL_INTERVAL = 15000

/** 播放提示音（Web Audio API，无需外部文件） */
function playAlertSound() {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)()
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.connect(gain)
    gain.connect(ctx.destination)
    osc.frequency.value = 880
    osc.type = 'sine'
    gain.gain.setValueAtTime(0.3, ctx.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.5)
    osc.start(ctx.currentTime)
    osc.stop(ctx.currentTime + 0.5)
  } catch { /* 音频不可用时静默 */ }
}

/** 发送浏览器桌面通知 */
function sendDesktopNotification(title, body) {
  if (!('Notification' in window)) return
  if (Notification.permission === 'granted') {
    new Notification(title, { body, icon: '🛡️' })
  }
}

export default function Dashboard({ user, onLogout }) {
  const [tab, setTab] = useState('notifications')
  const [notifications, setNotifications] = useState([])
  const [riskEvents, setRiskEvents] = useState([])
  const [students, setStudents] = useState([])
  const [unreadCount, setUnreadCount] = useState(0)
  const prevUnreadRef = useRef(0)

  const loadData = useCallback(async (silent = false) => {
    try {
      const [notifs, events, studs, unread] = await Promise.all([
        api('/teacher/notifications'),
        api('/teacher/risk-events'),
        api('/teacher/students'),
        api('/teacher/notifications/unread-count'),
      ])
      setNotifications(notifs)
      setRiskEvents(events)
      setStudents(studs)
      setUnreadCount(unread)

      // 检测新增未读通知 → 桌面通知 + 提示音
      if (unread > prevUnreadRef.current && prevUnreadRef.current >= 0) {
        const newCount = unread - prevUnreadRef.current
        playAlertSound()
        sendDesktopNotification(
          '🛡️ MindSafe 新预警',
          `收到 ${newCount} 条新的风险预警通知，请及时查看。`
        )
      }
      prevUnreadRef.current = unread
    } catch (e) {
      if (!silent) message.error('加载数据失败: ' + e.message)
    }
  }, [])

  // 挂载时：请求桌面通知权限 + 首次加载
  useEffect(() => {
    if ('Notification' in window && Notification.permission === 'default') {
      Notification.requestPermission()
    }
    loadData()
  }, [loadData])

  // P0 轮询：每 15 秒静默拉取 unread-count（纯前端增强，零后端改动）
  useEffect(() => {
    const timer = setInterval(() => loadData(true), POLL_INTERVAL)
    return () => clearInterval(timer)
  }, [loadData])

  const markRead = async (id) => {
    await api(`/teacher/notifications/${id}/read`, { method: 'PUT' })
    message.success('已标记为已读')
    loadData()
  }

  const riskColumns = [
    { title: '时间', dataIndex: 'detectedAt', render: (v) => dayjs(v).format('MM-DD HH:mm') },
    { title: '等级', dataIndex: 'riskLevel', render: (v) => <Tag color={RISK_COLORS[v]}>{RISK_LABELS[v]}</Tag> },
    { title: '类型', dataIndex: 'riskType' },
    { title: '状态', dataIndex: 'status', render: (v) => <Tag color={v === 'open' ? 'red' : 'green'}>{v === 'open' ? '待处理' : '已关闭'}</Tag> },
  ]

  const studentColumns = [
    { title: '姓名', dataIndex: 'displayName' },
    { title: '年级', dataIndex: 'gradeCode' },
    { title: '班级', dataIndex: 'classCode' },
  ]

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider theme="light" width={220} style={{ borderRight: '1px solid #f0f0f0' }}>
        <div style={{ padding: '20px 16px', textAlign: 'center' }}>
          <div style={{ fontSize: 28 }}>🛡️</div>
          <Text strong style={{ fontSize: 15 }}>MindSafe</Text>
        </div>
        <Menu
          mode="inline"
          selectedKeys={[tab]}
          onClick={({ key }) => setTab(key)}
          items={[
            { key: 'notifications', icon: <Badge count={unreadCount} size="small"><BellOutlined /></Badge>, label: '预警通知' },
            { key: 'risk-events', icon: <WarningOutlined />, label: '风险事件' },
            { key: 'students', icon: <TeamOutlined />, label: '学生档案' },
          ]}
        />
      </Sider>

      <Layout>
        <Header style={{ background: '#fff', padding: '0 24px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderBottom: '1px solid #f0f0f0' }}>
          <Title level={4} style={{ margin: 0 }}>
            {tab === 'notifications' && '预警通知'}
            {tab === 'risk-events' && '风险事件'}
            {tab === 'students' && '学生档案'}
          </Title>
          <Button icon={<LogoutOutlined />} onClick={onLogout}>退出</Button>
        </Header>

        <Content style={{ padding: 24, background: '#f5f5f5' }}>
          {tab === 'notifications' && (
            <List
              dataSource={notifications}
              locale={{ emptyText: '暂无通知' }}
              renderItem={(item) => (
                <Card size="small" style={{ marginBottom: 12, borderLeft: `3px solid ${item.severity >= 3 ? '#ff4d4f' : item.severity >= 2 ? '#fa8c16' : '#faad14'}` }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                    <div>
                      <Text strong>{item.title}</Text>
                      <br />
                      <Text type="secondary" style={{ fontSize: 13 }}>{item.bodySummary}</Text>
                      <br />
                      <Text type="secondary" style={{ fontSize: 12 }}>{dayjs(item.createdAt).format('YYYY-MM-DD HH:mm:ss')}</Text>
                    </div>
                    <Space>
                      {item.deliveryStatus !== 'read' && (
                        <Button size="small" icon={<CheckOutlined />} onClick={() => markRead(item.notificationId)}>
                          已读
                        </Button>
                      )}
                      {item.deliveryStatus === 'read' && <Tag color="green">已读</Tag>}
                    </Space>
                  </div>
                </Card>
              )}
            />
          )}

          {tab === 'risk-events' && (
            <Card>
              <Table dataSource={riskEvents} columns={riskColumns} rowKey="riskEventId" pagination={{ pageSize: 20 }} />
            </Card>
          )}

          {tab === 'students' && (
            <Card>
              <Table dataSource={students} columns={studentColumns} rowKey="userId" pagination={{ pageSize: 20 }} />
            </Card>
          )}
        </Content>
      </Layout>
    </Layout>
  )
}
