import { riskLevelName, riskLevelColor } from '../utils/riskLevel'
import { useEffect, useState } from 'react'
import { Card, message, Table, Tag } from 'antd'
import { fetchSlaStats, type SlaStatsItem } from '../api'

/** 时效监控（ADMIN-P1-04，M8：SLA 达标率/逾期/P95 按等级） */
export default function SlaPage() {
  const [stats, setStats] = useState<SlaStatsItem[]>([]) // F-09：显式 VO

  useEffect(() => {
    fetchSlaStats().then(setStats).catch((e: Error) => message.error(e.message))
  }, [])

  // doing/90 P-009：等级映射收敛至共享 riskLevel

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>时效监控</h2>
      <Card title="SLA 达标率（检出→处置，按等级聚合，近 30 天）" style={{ borderRadius: 'var(--ms-radius-card)' }}>
        <Table<SlaStatsItem>
          rowKey="riskLevel"
          dataSource={stats}
          size="small"
          pagination={false}
          columns={[
            {
              title: '等级',
              dataIndex: 'riskLevel',
              width: 100,
              render: (l: number) => <Tag color={riskLevelColor(l)}>{riskLevelName(l)}</Tag>,
            },
            { title: '事件数', dataIndex: 'total', width: 90 },
            { title: '达标', dataIndex: 'onTime', width: 90 },
            { title: '逾期', dataIndex: 'overdue', width: 90 },
            { title: '达标率', dataIndex: 'onTimeRate', width: 100, render: (v: number) => `${v}%` },
            { title: 'P95 处理时长', dataIndex: 'p95Minutes', render: (v: number) => `${v} 分钟` },
          ]}
        />
      </Card>
    </div>
  )
}
