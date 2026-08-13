import { useEffect, useState } from 'react'
import { Card, Col, message, Row, Statistic, Tag } from 'antd'
import { fetchUsageSummary, type UsageSummaryVO } from '../api'

/** 用量报表（ADMIN-P3-02，M4：计量预览，计费冻结标注） */
export default function UsagePage() {
  const [summary, setSummary] = useState<UsageSummaryVO | null>(null) // F-09：显式 VO

  useEffect(() => {
    fetchUsageSummary(30).then(setSummary).catch((e: Error) => message.error(e.message))
  }, [])

  const metrics = ['llm_call', 'active_student_snapshot', 'tts_call', 'asr_call'] as const

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>用量报表</h2>
      <p style={{ color: 'var(--ms-warning)', fontSize: 13 }}>
        ⚠️ 计量预览（M4 采集层先行，DEC-007）；计费（订阅/账单）设计冻结待 frozen/38 解冻，不涉及计价。
      </p>
      <Row gutter={16}>
        {metrics.map((m) => (
          <Col span={6} key={m}>
            <Card>
              <Statistic
                title={<Tag>{m}</Tag>}
                value={Number(summary?.[m] ?? 0)}
                valueStyle={{ fontSize: 22 }}
              />
            </Card>
          </Col>
        ))}
      </Row>
      <p style={{ color: 'var(--ms-text-muted)', fontSize: 12, marginTop: 12 }}>
        统计窗口：近 {Number(summary?.windowDays ?? 30)} 天（llm_call 单位 token，其余 count）
      </p>
    </div>
  )
}
