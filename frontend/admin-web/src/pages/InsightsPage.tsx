import { useEffect, useState } from 'react'
import { Card, Col, message, Row, Statistic, Table, Tag } from 'antd'
import { fetchAlertFunnel, fetchQualityTrend, fetchTenantHealth, type AlertFunnelVO, type QualityTrendVO, type TenantHealthItem } from '../api'

/** 运营洞察（ADMIN-P2-05，M12：预警漏斗 + 质量趋势 + 租户健康度） */
export default function InsightsPage() {
  const [funnel, setFunnel] = useState<AlertFunnelVO | null>(null) // F-09：显式 VO
  const [trend, setTrend] = useState<QualityTrendVO | null>(null) // F-09：显式 VO
  const [health, setHealth] = useState<TenantHealthItem[]>([]) // F-09：显式 VO

  useEffect(() => {
    fetchAlertFunnel().then(setFunnel).catch((e: Error) => message.error(e.message))
    // OPS-P3-07（doing/96）：质量趋势/租户健康度失败不再静默置空（错误态与空态需可区分）
    fetchQualityTrend().then(setTrend).catch((e: Error) => message.error(`质量趋势加载失败：${e.message}`))
    fetchTenantHealth().then(setHealth).catch((e: Error) => message.error(`租户健康度加载失败：${e.message}`))
  }, [])

  const funnelSteps = [
    ['detected', '检出'], ['notified', '通知'], ['claimed', '认领'], ['resolved', '处置'], ['closed', '闭环'],
  ] as const

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>运营洞察</h2>
      <Row gutter={16} style={{ marginBottom: 16 }}>
        {funnelSteps.map(([key, label]) => (
          <Col span={4} key={key}>
            <Card>
              <Statistic
                title={label}
                value={Number(funnel?.[key] ?? 0)}
                valueStyle={{ color: key === 'closed' ? 'var(--ms-success)' : undefined }}
              />
            </Card>
          </Col>
        ))}
      </Row>
      <Card title="会话质量趋势（近 7 天日均分/样本）" style={{ marginBottom: 16, borderRadius: 'var(--ms-radius-card)' }}>
        <Table<{ day: string; avgScore: number; samples: number }>
          rowKey="day"
          size="small"
          pagination={false}
          dataSource={Object.entries(trend ?? {}).map(([day, v]) => ({
            day,
            avgScore: Number((v as { avgScore?: number }).avgScore ?? 0),
            samples: Number((v as { samples?: number }).samples ?? 0),
          }))}
          columns={[
            { title: '日期', dataIndex: 'day' },
            { title: '日均分', dataIndex: 'avgScore', render: (v: number) => v.toFixed(1) },
            { title: '样本数', dataIndex: 'samples' },
          ]}
        />
      </Card>
      <Card title="租户健康度（未处置/逾期）" style={{ borderRadius: 'var(--ms-radius-card)' }}>
        <Table<TenantHealthItem>
          rowKey="tenantId"
          dataSource={health}
          size="small"
          pagination={false}
          columns={[
            {
              title: '租户',
              dataIndex: 'tenantName',
              render: (v: string, record: TenantHealthItem) =>
                v ? `${v}${record.tenantCode ? `（${String(record.tenantCode)}）` : ''}` : String(record.tenantId).slice(0, 8),
            },
            { title: '事件数', dataIndex: 'total', width: 90 },
            { title: '未处置', dataIndex: 'unhandled', width: 90 },
            { title: '逾期', dataIndex: 'overdue', width: 90 },
            {
              title: '健康度',
              dataIndex: 'health',
              width: 100,
              // BUG-A-006：英文枚举 → 中文语义标签（红/黄/绿）
              render: (h: string) => {
                const map: Record<string, { label: string; color: string }> = {
                  red: { label: '差', color: 'red' },
                  yellow: { label: '中', color: 'orange' },
                  green: { label: '良', color: 'green' },
                }
                const item = map[h] ?? { label: h, color: 'default' }
                return <Tag color={item.color}>{item.label}</Tag>
              },
            },
          ]}
        />
      </Card>
    </div>
  )
}
