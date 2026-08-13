import { useEffect, useState } from 'react'
import { Card, Col, message, Row, Statistic, Tag } from 'antd'
import { fetchConsentStats, type ConsentStatsVO } from '../api'

/** 数据合规视图（ADMIN-P3-03，M11：告知同意覆盖统计） */
export default function CompliancePage() {
  const [stats, setStats] = useState<ConsentStatsVO | null>(null) // F-09：显式 VO

  useEffect(() => {
    fetchConsentStats().then(setStats).catch((e: Error) => message.error(e.message))
  }, [])

  const byType = (stats?.byType ?? {}) as Record<string, number>

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>数据合规中心</h2>
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card><Statistic title="告知同意记录总数" value={Number(stats?.total ?? 0)} /></Card>
        </Col>
        <Col span={8}>
          <Card><Statistic title="近 7 天新增" value={Number(stats?.last7d ?? 0)} /></Card>
        </Col>
      </Row>
      <Card title="同意类型分布" style={{ borderRadius: 'var(--ms-radius-card)' }}>
        <Row gutter={[16, 16]}>
          {Object.entries(byType).map(([type, count]) => (
            <Col span={6} key={type}>
              <Card size="small">
                <Statistic title={<Tag>{type}</Tag>} value={count} />
              </Card>
            </Col>
          ))}
        </Row>
      </Card>
      <p style={{ color: 'var(--ms-text-muted)', fontSize: 12, marginTop: 12 }}>
        审计全景（跨租户操作日志）见「审计日志」页；数据导出审批流设计冻结待议决（M11 冻结项不实施）。
      </p>
    </div>
  )
}
