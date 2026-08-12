import { useState, useEffect, useCallback } from 'react'
import { List, Card, Button, Tag, Space, Typography, message, Empty } from 'antd'
import { CheckOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { getNotifications, markNotificationRead } from '../../api'

const { Text } = Typography

/** 通知项（getNotifications 契约） */
interface NotificationVO {
  notificationId: string
  title: string
  bodySummary: string
  createdAt: string
  deliveryStatus: string
  severity: number
}

export default function NotificationPanel() {
  const [notifications, setNotifications] = useState<NotificationVO[]>([])
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    try {
      const data = await getNotifications()
      setNotifications(data)
    } catch (e) {
      message.error('加载通知失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const handleMarkRead = async (id: string) => {
    try {
      await markNotificationRead(id)
      message.success('已标记为已读')
      // BUG-T-06-01（2026-08-12，UI-TEST-013）：已读后通知 Dashboard 刷新未读徽标（原仅全量刷新同步）
      window.dispatchEvent(new Event('mindsafe:unread-changed'))
      load()
    } catch (e) {
      message.error(e.message)
    }
  }

  const SEVERITY_BORDER: Record<number, string> = {
    3: 'var(--ms-danger)',
    2: 'var(--ms-warning)',
    1: 'var(--ms-warning)',
    0: 'var(--ms-border)',
  }

  return (
    <List
      loading={loading}
      dataSource={notifications}
      locale={{ emptyText: <Empty description="暂无通知" /> }}
      renderItem={(item) => (
        <Card
          size="small"
          style={{
            marginBottom: 12,
            borderLeft: `3px solid ${SEVERITY_BORDER[item.severity] || 'var(--ms-border)'}`,
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <div>
              <Text strong>{item.title}</Text>
              <br />
              <Text type="secondary" className="ms-text-sm">{item.bodySummary}</Text>
              <br />
              <Text type="secondary" style={{ fontSize: 12 }}>
                {dayjs(item.createdAt).format('YYYY-MM-DD HH:mm:ss')}
              </Text>
            </div>
            <Space>
              {item.deliveryStatus !== 'read' ? (
                <Button size="small" icon={<CheckOutlined />} onClick={() => handleMarkRead(item.notificationId)}>
                  已读
                </Button>
              ) : (
                <Tag color="green">已读</Tag>
              )}
            </Space>
          </div>
        </Card>
      )}
    />
  )
}
