import { useState, useEffect } from 'react'
import { Card, Row, Col, Statistic, Table, Tag, Button, Drawer, Spin, Empty } from 'antd'
import { WarningOutlined, StarOutlined, EyeOutlined } from '@ant-design/icons'
import { getQualityStats, getFlaggedSessions, getSessionMessages, exportSessionPdf } from '../../api'
import { emotionLabel } from '../../utils/emotionLabels'

/** AI 对话质量监控面板 */
export default function QualityPanel() {
  const [stats, setStats] = useState(null)
  const [flagged, setFlagged] = useState([])
  const [loading, setLoading] = useState(true)
  const [replayOpen, setReplayOpen] = useState(false)
  const [currentSessionId, setCurrentSessionId] = useState(null)
  const [messages, setMessages] = useState([])
  const [replayLoading, setReplayLoading] = useState(false)

  useEffect(() => {
    Promise.all([getQualityStats(), getFlaggedSessions()])
      .then(([s, f]) => { setStats(s); setFlagged(f) })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  const openReplay = async (sessionId) => {
    setCurrentSessionId(sessionId)
    setReplayOpen(true)
    setReplayLoading(true)
    setMessages([])
    try {
      const msgs = await getSessionMessages(sessionId)
      setMessages(msgs || [])
    } catch { /* ignore */ }
    setReplayLoading(false)
  }

  if (loading) return <div className="ms-empty-lg"><Spin size="large" /></div>

  const columns = [
    { title: '评分', dataIndex: 'rating', key: 'rating', width: 80, align: 'center' as const,
      render: v => <Tag color={v <= 1 ? 'red' : 'orange'}>{v}★</Tag> },
    { title: '学生评价', dataIndex: 'comment', key: 'comment', ellipsis: true,
      render: v => v || <span style={{ color: 'var(--ms-text-muted)' }}>无评价</span> },
    { title: '时间', dataIndex: 'startedAt', key: 'time', width: 160,
      render: v => v ? new Date(v).toLocaleString('zh-CN') : '-' },
    { title: '操作', key: 'action', width: 100, align: 'center' as const,
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
            prefix={<StarOutlined />} suffix="/ 5" valueStyle={{ color: 'var(--ms-warning)' }} /></Card>
        </Col>
        <Col xs={12} md={6}>
          <Card size="small"><Statistic title="近 7 天均分" value={stats?.recentAvg || 0}
            suffix="/ 5" /></Card>
        </Col>
        <Col xs={12} md={6}>
          <Card size="small"><Statistic title="低分会话" value={stats?.flaggedCount || 0}
            prefix={<WarningOutlined />} valueStyle={{ color: 'var(--ms-danger)' }} /></Card>
        </Col>
        <Col xs={12} md={6}>
          <Card size="small"><Statistic title="低分率" value={stats?.flagRate || 0}
            suffix="%" valueStyle={{ color: (stats?.flagRate || 0) > 20 ? 'var(--ms-danger)' : 'var(--ms-success)' }} /></Card>
        </Col>
      </Row>

      {/* 低分会话列表 */}
      <Card title={<span><WarningOutlined className="ms-text-danger" /> 待抽检会话（评分 ≤ 2★）</span>} size="small">
        {flagged.length === 0 ? (
          <Empty description="暂无低分会话，AI 对话质量良好 🎉" />
        ) : (
          <Table dataSource={flagged} columns={columns} rowKey="sessionId" size="small" pagination={{ pageSize: 10 }} />
        )}
      </Card>

      {/* 会话回放抽屉 */}
      <Drawer title="会话回放" open={replayOpen} onClose={() => setReplayOpen(false)} width={480}
        extra={<Button size="small" icon={<EyeOutlined />} onClick={() => {
          // P1-FE-3：导出当前回放的会话；曾误用 flagged.find 取列表第一条 → 张冠李戴
          if (currentSessionId) exportSessionPdf(currentSessionId)
        }}>导出 PDF</Button>}>
        {replayLoading ? <Spin /> : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {messages.map((msg, i) => (
              <div key={i} style={{
                padding: '8px 12px', borderRadius: 8, maxWidth: '85%',
                alignSelf: msg.senderType === 'student' ? 'flex-end' : 'flex-start',
                background: msg.senderType === 'student' ? 'var(--ms-primary-soft)' : 'var(--ms-success-soft)',
              }}>
                <div style={{ fontSize: 11, color: 'var(--ms-text-muted)', marginBottom: 4 }}>
                  {msg.senderType === 'student' ? '🧒 学生' : '🤖 AI'} · {emotionLabel(msg.emotionLabel)}
                </div>
                <div className="ms-text-sm">{msg.contentSummary || msg.messageContent || ''}</div>
              </div>
            ))}
            {messages.length === 0 && <Empty description="无消息记录" />}
          </div>
        )}
      </Drawer>
    </div>
  )
}
