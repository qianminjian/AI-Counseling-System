import { useState, useEffect } from 'react'
import { Card, Tag, Typography, Space, Spin, Empty, Alert } from 'antd'
import { FileTextOutlined, BulbOutlined } from '@ant-design/icons'
import { getSessionSummary } from '../../api'

const { Text, Paragraph } = Typography

/**
 * 会话 AI 摘要卡片
 * 展示 LLM 生成的结构化摘要：主要话题 / 情绪趋势 / 关键点 / 风险提示 / 建议
 */
export default function SessionSummaryCard({ sessionId }) {
  const [loading, setLoading] = useState(false)
  const [data, setData] = useState(null)

  useEffect(() => {
    if (!sessionId) return
    setLoading(true)
    getSessionSummary(sessionId)
      .then(res => setData(res))
      .catch(() => setData(null))
      .finally(() => setLoading(false))
  }, [sessionId])

  if (loading) {
    return <Card size="small"><Spin size="small" /> 加载摘要中...</Card>
  }

  if (!data || data.status === 'not_found') {
    return <Empty description="暂无会话记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
  }

  if (data.status === 'pending') {
    return (
      <Card size="small">
        <Text type="secondary">AI 摘要生成中，请稍后刷新查看...</Text>
      </Card>
    )
  }

  // 解析 JSON 摘要
  let summary
  try {
    summary = JSON.parse(data.summary)
  } catch {
    // 非 JSON 格式，直接展示原文
    return (
      <Card size="small" title={<><FileTextOutlined /> 会话摘要</>}>
        <Paragraph>{data.summary}</Paragraph>
      </Card>
    )
  }

  return (
    <Card
      size="small"
      title={<><FileTextOutlined /> AI 会话摘要</>}
      styles={{ body: { padding: '12px 16px' } }}
    >
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <div>
          <Text strong>主要话题：</Text>
          <Tag color="blue">{summary.mainTopic || '未识别'}</Tag>
        </div>

        <div>
          <Text strong>情绪趋势：</Text>
          <Text>{summary.emotionTrend || '—'}</Text>
        </div>

        {summary.keyPoints?.length > 0 && (
          <div>
            <Text strong>关键点：</Text>
            <ul style={{ margin: '4px 0', paddingLeft: 20 }}>
              {summary.keyPoints.map((p, i) => <li key={i}>{p}</li>)}
            </ul>
          </div>
        )}

        {summary.riskNote && summary.riskNote !== '无' && (
          <Alert
            type="warning"
            showIcon
            message={`风险提示：${summary.riskNote}`}
            style={{ padding: '4px 12px' }}
          />
        )}

        {summary.suggestion && (
          <div>
            <BulbOutlined style={{ color: 'var(--ms-warning)', marginRight: 6 }} />
            <Text italic>{summary.suggestion}</Text>
          </div>
        )}
      </Space>
    </Card>
  )
}
