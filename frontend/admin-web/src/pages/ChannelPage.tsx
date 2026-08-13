import { useEffect, useState } from 'react'
import { Card, Col, message, Row, Statistic, Tag } from 'antd'
import { fetchChannelStats, type ChannelStatsVO } from '../api'

/** 通知渠道（ADMIN-P2-04/06，M10：渠道发送统计 + 失败台账入口） */
export default function ChannelPage() {
  const [stats, setStats] = useState<ChannelStatsVO | null>(null) // F-09：显式 VO

  useEffect(() => {
    fetchChannelStats().then(setStats).catch((e: Error) => message.error(e.message))
  }, [])

  const byChannel = (stats?.byChannel ?? {}) as Record<string, number>

  return (
    <div>
      <h2 style={{ marginTop: 0 }}>通知渠道</h2>
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card><Statistic title="近 30 天发送总数" value={Number(stats?.total ?? 0)} /></Card>
        </Col>
      </Row>
      <Card title="按渠道分布（近 30 天）" style={{ borderRadius: 'var(--ms-radius-card)' }}>
        <Row gutter={[16, 16]}>
          {Object.entries(byChannel).map(([channel, count]) => (
            <Col span={6} key={channel}>
              <Card size="small">
                <Statistic title={<Tag>{channel}</Tag>} value={count} />
              </Card>
            </Col>
          ))}
        </Row>
      </Card>
      <p style={{ color: 'var(--ms-text-muted)', fontSize: 12, marginTop: 12 }}>
        失败台账（notify_status=dead）见「处置台账」页；触达策略配置化经 YAGNI 议决（2026-08-09）：
        当前预警链路=站内+WebSocket+企微告警，无多渠道分发点，策略化改造待真实多通道需求出现（P2-04 关闭）。
      </p>
    </div>
  )
}
