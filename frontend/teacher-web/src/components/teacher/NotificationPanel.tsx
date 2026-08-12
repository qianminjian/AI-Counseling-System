import { useState, useEffect, useCallback } from 'react'
import { List, Card, Button, Tag, Space, Typography, message, Empty, Segmented, Pagination } from 'antd'
import { CheckOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { getNotifications, markNotificationRead, type NotificationVO } from '../../api'

const { Text } = Typography

/** BUG-T-06-02/03（2026-08-12）：筛选 + 分页 + 学生姓名 */
export default function NotificationPanel() {
  const [notifications, setNotifications] = useState<NotificationVO[]>([])
  const [loading, setLoading] = useState(true)
  const [status, setStatus] = useState('ALL')
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const pageSize = 10

  const load = useCallback(async () => {
    try {
      const data = await getNotifications(status, page, pageSize)
      setNotifications(data.items)
      setTotal(data.total)
    } catch (e) {
      message.error('加载通知失败')
    } finally {
      setLoading(false)
    }
  }, [status, page])

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
    <div>
      {/* BUG-T-06-02：全部/未读/已读筛选 */}
      <Segmented
        value={status}
        onChange={(v) => { setStatus(String(v)); setPage(1) }}
        options={[
          { label: '全部', value: 'ALL' },
          { label: '未读', value: 'UNREAD' },
          { label: '已读', value: 'READ' },
        ]}
        style={{ marginBottom: 16 }}
      />
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
                <Space size={8} wrap>
                  <Text strong>{item.title}</Text>
                  {/* BUG-T-06-03：学生姓名（从 risk_event 关联填充） */}
                  {item.studentNickname && <Tag color="blue">{item.studentNickname}</Tag>}
                </Space>
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
      {/* BUG-T-06-02：分页 */}
      {total > pageSize && (
        <Pagination
          current={page}
          pageSize={pageSize}
          total={total}
          onChange={setPage}
          showSizeChanger={false}
          style={{ textAlign: 'right', marginTop: 8 }}
        />
      )}
    </div>
  )
}
