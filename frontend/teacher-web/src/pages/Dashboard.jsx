import { useState, useEffect } from 'react'
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

export default function Dashboard({ user, onLogout }) {
  const [tab, setTab] = useState('notifications')
  const [notifications, setNotifications] = useState([])
  const [riskEvents, setRiskEvents] = useState([])
  const [students, setStudents] = useState([])
  const [unreadCount, setUnreadCount] = useState(0)

  const loadData = async () => {
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
    } catch (e) {
      message.error('加载数据失败: ' + e.message)
    }
  }

  useEffect(() => { loadData() }, [])

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
