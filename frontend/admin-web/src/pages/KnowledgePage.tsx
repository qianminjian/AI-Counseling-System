import { useEffect, useState } from 'react'
import { Card, Col, Descriptions, message, Row, Statistic, Tag } from 'antd'
import { fetchKnowledgeStats, type KnowledgeStatsVO } from '../api'

/** 知识库统计（ADMIN-P2-03，M9：平台级状态/分类分布） */
export default function KnowledgePage() {
  const [stats, setStats] = useState<KnowledgeStatsVO | null>(null) // F-09：显式 VO

  useEffect(() => {
    fetchKnowledgeStats().then(setStats).catch((e: Error) => message.error(e.message))
  }, [])

  const byStatus = (stats?.byStatus ?? {}) as Record<string, number>
  const byCategory = (stats?.byCategory ?? {}) as Record<string, number>
  const total = Object.values(byStatus).reduce((a, b) => a + b, 0)

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>知识库统计</h2>
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}><Card><Statistic title="文档总数" value={total} /></Card></Col>
        {Object.entries(byStatus).map(([status, count]) => (
          <Col span={4} key={status}>
            <Card><Statistic title={`状态 ${status}`} value={count} /></Card>
          </Col>
        ))}
      </Row>
      <Card title="按分类分布" style={{ borderRadius: 'var(--ms-radius-card)' }}>
        <Descriptions size="small" column={4}>
          {Object.entries(byCategory).map(([category, count]) => (
            <Descriptions.Item key={category} label={<Tag>{category}</Tag>}>{count}</Descriptions.Item>
          ))}
        </Descriptions>
      </Card>
    </div>
  )
}
