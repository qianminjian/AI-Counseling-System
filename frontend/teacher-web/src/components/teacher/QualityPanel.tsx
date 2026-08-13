import { useState, useEffect, useRef } from 'react'
import { Card, Row, Col, Statistic, Table, Tag, Button, Spin, Empty, Alert } from 'antd'
import type { TableProps } from 'antd'
import { WarningOutlined, StarOutlined, EyeOutlined } from '@ant-design/icons'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'
import { getQualityStats, getFlaggedSessions, getQualityTrend, exportSessionPdf, type QualityTrendItem } from '../../api'
import { useECharts } from '../../hooks/useECharts'
import SessionMessagesDrawer from './SessionMessagesDrawer'

// T-06-01：按需注册折线图（与 StatsCharts 同模式）
echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

/** 低分会话（getFlaggedSessions 契约） */
interface FlaggedSessionVO {
  sessionId: string
  rating: number
  comment?: string
  startedAt: string
}

/** 质量统计（getQualityStats 契约，F-04 doing/98：显式类型） */
interface QualityStatsVO {
  avgRating: number
  recentAvg: number
  flaggedCount: number
  flagRate: number
}

/** AI 对话质量监控面板 */
export default function QualityPanel() {
  const [stats, setStats] = useState<QualityStatsVO | null>(null)
  const [flagged, setFlagged] = useState<FlaggedSessionVO[]>([])
  const [loading, setLoading] = useState(true)
  const [replayOpen, setReplayOpen] = useState(false)
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null)
  // F-09：加载失败不静默——console.error + 局部错误条 + 重试（AUD-019 只覆盖主加载）
  const [error, setError] = useState<string | null>(null)
  const [retryKey, setRetryKey] = useState(0)
  // T-06-01：近 30 天质量趋势（LLM-as-Judge 综合分按日均值）
  const [trend, setTrend] = useState<QualityTrendItem[]>([])
  const trendRef = useRef<HTMLDivElement | null>(null)

  const trendOption: EChartsOption | null = trend.length > 0 ? {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: trend.map(t => t.date.slice(5)), boundaryGap: false },
    yAxis: { type: 'value', min: 0, max: 5 },
    series: [{
      name: '综合分均值',
      type: 'line',
      smooth: true,
      data: trend.map(t => t.avgScore),
      lineStyle: { color: '#1677ff', width: 2 },
      itemStyle: { color: '#1677ff' },
      areaStyle: { opacity: 0.08 },
    }],
  } : null
  useECharts(trendRef, trendOption)

  useEffect(() => {
    setLoading(true)
    Promise.all([getQualityStats(), getFlaggedSessions(), getQualityTrend()])
      .then(([s, f, t]) => { setStats(s); setFlagged(f); setTrend(t); setError(null) })
      .catch((e) => {
        console.error('[QualityPanel] 加载质量统计失败:', e)
        setError('质量数据加载失败，请检查网络后重试')
      })
      .finally(() => setLoading(false))
  }, [retryKey])

  // FA-04：消息加载与渲染统一走共享 SessionMessagesDrawer（含 cancelled 守卫）
  const openReplay = (sessionId: string) => {
    setCurrentSessionId(sessionId)
    setReplayOpen(true)
  }

  if (loading) return <div className="ms-empty-lg"><Spin size="large" /></div>

  // F-09：加载失败展示错误条（不再渲染空面板，提供重试入口）
  if (error) {
    return (
      <Alert type="error" showIcon message={error}
        action={<Button size="small" onClick={() => setRetryKey(k => k + 1)}>重试</Button>} />
    )
  }

  const columns: TableProps<FlaggedSessionVO>['columns'] = [
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

      {/* 近 30 天质量趋势（T-06-01） */}
      <Card title={<span><StarOutlined /> 近 30 天质量趋势（LLM-as-Judge 综合分均值）</span>} size="small" style={{ marginBottom: 24 }}>
        <div ref={trendRef} style={{ height: 220 }} />
      </Card>

      {/* 低分会话列表 */}
      <Card title={<span><WarningOutlined className="ms-text-danger" /> 待抽检会话（评分 ≤ 2★）</span>} size="small">
        {flagged.length === 0 ? (
          <Empty description="暂无低分会话，AI 对话质量良好 🎉" />
        ) : (
          <Table dataSource={flagged} columns={columns} rowKey="sessionId" size="small" pagination={{ pageSize: 10 }} />
        )}
      </Card>

      {/* 会话回放抽屉（FA-04：共享组件，含 cancelled 守卫；导出 PDF 保持当前会话）
          BUG-UI-01：质控回放需检查对话内容，显式开启 showTranscript */}
      <SessionMessagesDrawer
        sessionId={currentSessionId}
        onClose={() => setReplayOpen(false)}
        showTranscript
        extra={(
          <Button size="small" icon={<EyeOutlined />} onClick={() => {
            // P1-FE-3：导出当前回放的会话；曾误用 flagged.find 取列表第一条 → 张冠李戴
            if (currentSessionId) exportSessionPdf(currentSessionId)
          }}>导出 PDF</Button>
        )}
      />
    </div>
  )
}
