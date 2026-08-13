import { riskLevelKey } from '../utils/riskLevel'
import { useEffect, useState } from 'react'
import { Card, Col, Descriptions, Row, Spin, Statistic, Table, Tag } from 'antd'
import { fetchRiskOverview, fetchRiskOverdue, type RiskOverview, type RiskOverdueItem } from '../api'

/** 风险全景（ADMIN-P1-04，M8：红橙黄绿分布 + 今日新增/未处置 + 逾期清单） */
export default function RiskPage() {
  const [overview, setOverview] = useState<RiskOverview | null>(null)
  const [overdue, setOverdue] = useState<RiskOverdueItem[]>([]) // F-09：显式 VO
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchRiskOverview()
      .then(setOverview)
      .catch((e: Error) => setError(e.message))
    fetchRiskOverdue()
      .then(setOverdue)
      .catch(() => setOverdue([]))
  }, [])

  // doing/90 P-009：等级映射收敛至共享 riskLevel（仅 CSS token 语义色映射保留唯一）
  const levelColor: Record<string, string> = {
    red: 'var(--ms-danger)',
    orange: 'var(--ms-warning)',
    yellow: 'var(--ms-warning-border)',
    green: 'var(--ms-success)',
  }

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>风险全景</h2>
      {error ? <span style={{ color: 'var(--ms-danger)' }}>{error}</span> : null}
      {!overview && !error ? <Spin /> : null}
      {overview ? (
        <>
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={6}>
              <Card><Statistic title="今日新增" value={overview.todayNew} /></Card>
            </Col>
            <Col span={6}>
              <Card><Statistic title="未处置" value={overview.unhandled} valueStyle={{ color: 'var(--ms-warning)' }} /></Card>
            </Col>
            {Object.entries(overview.levelDistribution).map(([level, count]) => (
              <Col span={3} key={level}>
                <Card>
                  <Statistic
                    title={level}
                    value={count}
                    valueStyle={{ color: levelColor[level] ?? 'var(--ms-text)' }}
                  />
                </Card>
              </Col>
            ))}
          </Row>
          <Card title="近 7 天趋势" style={{ borderRadius: 'var(--ms-radius-card)' }}>
            <Descriptions size="small" column={7}>
              {Object.entries(overview.trend7d).map(([day, count]) => (
                <Descriptions.Item key={day} label={day.slice(5)}>{count}</Descriptions.Item>
              ))}
            </Descriptions>
          </Card>
        </>
      ) : null}
      <Card title={`逾期未处置清单（${overdue.length}）`} style={{ marginTop: 16, borderRadius: 'var(--ms-radius-card)' }}>
        <Table<RiskOverdueItem>
          rowKey={(r) => String(r.riskEventId)}
          dataSource={overdue}
          size="small"
          pagination={false}
          columns={[
            { title: '类型', dataIndex: 'riskType', width: 120 },
            {
              title: '等级',
              dataIndex: 'riskLevel',
              width: 90,
              render: (level: number) => (
                <Tag color={levelColor[riskLevelKey(level)]}>{level}</Tag>
              ),
            },
            { title: '状态', dataIndex: 'status', width: 90 },
            { title: '检出时间', dataIndex: 'detectedAt', render: (v: string) => (v ? String(v).slice(0, 19) : '-') },
          ]}
        />
      </Card>
    </div>
  )
}
