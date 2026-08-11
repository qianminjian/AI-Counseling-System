import { riskLevelColor } from '../utils/riskLevel'
import { useEffect, useState } from 'react'
import { Card, message, Table, Tag } from 'antd'
import { fetchDeadLedger, type DeadLedgerItem } from '../api'

/** 处置台账（ADMIN-P1-04/05，M8：通知 dead 兜底台账，脱敏视图 R-7） */
export default function LedgerPage() {
  const [items, setItems] = useState<DeadLedgerItem[]>([])

  useEffect(() => {
    fetchDeadLedger().then(setItems).catch((e: Error) => message.error(e.message))
  }, [])

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>处置台账</h2>
      <Card title={`通知兜底台账（notify_status=dead，${items.length}）——脱敏视图（R-7）`} style={{ borderRadius: 'var(--ms-radius-card)' }}>
        <Table<DeadLedgerItem>
          rowKey="riskEventId"
          dataSource={items}
          size="small"
          pagination={false}
          columns={[
            { title: '风险类型', dataIndex: 'riskType', width: 140 },
            {
              title: '等级',
              dataIndex: 'riskLevel',
              width: 90,
              render: (l: number) => <Tag color={riskLevelColor(l)}>{l}</Tag>,
            },
            { title: '状态', dataIndex: 'status', width: 90 },
            { title: '检出时间', dataIndex: 'detectedAt', render: (v: string) => (v ? String(v).slice(0, 19) : '-') },
          ]}
        />
      </Card>
    </div>
  )
}
