import { useState, useEffect } from 'react'
import { Card, Row, Col, Statistic, Table, Tag, Button, Drawer, Spin, Empty } from 'antd'
import { WarningOutlined, StarOutlined, EyeOutlined } from '@ant-design/icons'
import { getQualityStats, getFlaggedSessions, getSessionMessages, exportSessionPdf } from '../../api'

/** AI 对话质量监控面板 */
export default function QualityPanel() {
  const [stats, setStats] = useState(null)
  const [flagged, setFlagged] = useState([])
  const [loading, setLoading] = useState(true)
  const [replayOpen, setReplayOpen] = useState(false)
  const [messages, setMessages] = useState([])
  const [replayLoading, setReplayLoading] = useState(false)

  useEffect(() => {
    Promise.all([getQualityStats(), getFlaggedSessions()])
      .then(([s, f]) => { setStats(s); setFlagged(f) })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  const openReplay = async (sessionId) => {
    setReplayOpen(true)
    setReplayLoading(true)
    setMessages([])
    try {
      const msgs = await getSessionMessages(sessionId)
      setMessages(msgs || [])
    } catch { /* ignore */ }
    setReplayLoading(false)
  }

  if (loading) return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>

  const columns = [
    { title: '评分', dataIndex: 'rating', key: 'rating', width: 80, align: 'center',
      render: v => <Tag color={v <= 1 ? 'red' : 'orange'}>{v}★</Tag> },
    { title: '学生评价', dataIndex: 'comment', key: 'comment', ellipsis: true,
      render: v => v || <span style={{ color: '#ccc' }}>无评价</span> },
    { title: '时间', dataIndex: 'startedAt', key: 'time', width: 160,
      render: v => v ? new Date(v).toLocaleString('zh-CN') : '-' },
    { title: '操作', key: 'action', width: 100, align: 'center',
      render: (_, r) => (
        <Button type="link" size="small" icon={<EyeOutlined />}
          onClick={() => openReplay(r.sessionId)}>回放</Button>
      ) },
  ]

  return (
    <div>
      {/* 质量指标 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={12} md={6}>
          <Card size="small"><Statistic title="平均评分" value={stats?.avgRating || 0}
            prefix={<StarOutlined />} suffix="/ 5" valueStyle={{ color: '#faad14' }} /></Card>
        </Col>
        <Col xs={12} md={6}>
          <Card size="small"><Statistic title="近 7 天均分" value={stats?.recentAvg || 0}
            suffix="/ 5" /></Card>
        </Col>
        <Col xs={12} md={6}>
          <Card size="small"><Statistic title="低分会话" value={stats?.flaggedCount || 0}
            prefix={<WarningOutlined />} valueStyle={{ color: '#ff4d4f' }} /></Card>
        </Col>
        <Col xs={12} md={6}>
          <Card size="small"><Statistic title="低分率" value={stats?.flagRate || 0}
            suffix="%" valueStyle={{ color: (stats?.flagRate || 0) > 20 ? '#ff4d4f' : '#52c41a' }} /></Card>
        </Col>
      </Row>

      {/* 低分会话列表 */}
      <Card title={<span><WarningOutlined style={{ color: '#ff4d4f' }} /> 待抽检会话（评分 ≤ 2★）</span>} size="small">
        {flagged.length === 0 ? (
          <Empty description="暂无低分会话，AI 对话质量良好 🎉" />
        ) : (
          <Table dataSource={flagged} columns={columns} rowKey="sessionId" size="small" pagination={{ pageSize: 10 }} />
        )}
      </Card>

      {/* 会话回放抽屉 */}
      <Drawer title="会话回放" open={replayOpen} onClose={() => setReplayOpen(false)} width={480}
        extra={<Button size="small" icon={<EyeOutlined />} onClick={() => {
          const sid = flagged.find(f => messages.length > 0)?.sessionId
          if (sid) exportSessionPdf(sid)
        }}>导出 PDF</Button>}>
        {replayLoading ? <Spin /> : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {messages.map((msg, i) => (
              <div key={i} style={{
                padding: '8px 12px', borderRadius: 8, maxWidth: '85%',
                alignSelf: msg.senderType === 'student' ? 'flex-end' : 'flex-start',
                background: msg.senderType === 'student' ? '#e6f7ff' : '#f6ffed',
              }}>
                <div style={{ fontSize: 11, color: '#999', marginBottom: 4 }}>
                  {msg.senderType === 'student' ? '🧒 学生' : '🤖 AI'} · {msg.emotionLabel || ''}
                </div>
                <div style={{ fontSize: 13 }}>{msg.contentSummary || msg.messageContent || ''}</div>
              </div>
            ))}
            {messages.length === 0 && <Empty description="无消息记录" />}
          </div>
        )}
      </Drawer>
    </div>
  )
}
