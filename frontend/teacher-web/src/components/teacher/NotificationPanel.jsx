import { useState, useEffect, useCallback } from 'react'
import { List, Card, Button, Tag, Space, Typography, message, Empty } from 'antd'
import { CheckOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { getNotifications, markNotificationRead } from '../../api'

const { Text } = Typography

export default function NotificationPanel() {
  const [notifications, setNotifications] = useState([])
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

  const handleMarkRead = async (id) => {
    try {
      await markNotificationRead(id)
      message.success('已标记为已读')
      load()
    } catch (e) {
      message.error(e.message)
    }
  }

  const SEVERITY_BORDER = {
    3: '#ff4d4f',
    2: '#fa8c16',
    1: '#faad14',
    0: '#d9d9d9',
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
            borderLeft: `3px solid ${SEVERITY_BORDER[item.severity] || '#d9d9d9'}`,
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <div>
              <Text strong>{item.title}</Text>
              <br />
              <Text type="secondary" style={{ fontSize: 13 }}>{item.bodySummary}</Text>
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
