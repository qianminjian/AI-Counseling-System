import { useState, useEffect, useCallback, useRef } from 'react'
import { Card, Row, Col, Statistic, Spin, Tag, Button, Alert } from 'antd'
import {
  AlertOutlined, ClockCircleOutlined, MessageOutlined, RiseOutlined, SmileOutlined, FileTextOutlined,
} from '@ant-design/icons'
import { getDashboard, getHighRiskStudents, getStats, openWeeklyReport, getSatisfaction } from '../../api'
import { usePolling } from '../../hooks/usePolling'
import { SessionTrendChart, RiskPieChart, ClassBarChart, EmotionBarChart } from './StatsCharts'
import { riskColor, riskLabel } from '../../utils/riskLevel'
import TodayTodoPanel from './TodayTodoPanel'

/** 满意度统计（getSatisfaction 契约） */
interface SatisfactionVO {
  totalRated: number
  avgRating: number
  recentAvg: number
  recentCount: number
  distribution?: Array<{ stars: number; count: number }>
}

/** 轻量 CSS 柱状图（7 天趋势） */
function WeeklyChart({ data }: { data: Array<{ date: string; count: number }> }) {
  if (!data || data.length === 0) return null
  const max = Math.max(...data.map((d) => d.count), 1)

  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, height: 120, padding: '0 4px' }}>
      {data.map((d) => (
        <div key={d.date} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
          <span style={{ fontSize: 11, color: 'var(--ms-text-secondary)' }}>{d.count}</span>
          <div
            style={{
              width: '100%',
              maxWidth: 32,
              height: `${Math.max((d.count / max) * 80, 4)}px`,
              background: d.count > 0 ? 'linear-gradient(to top, var(--ms-danger), var(--ms-danger-soft))' : 'var(--ms-border-soft)',
              borderRadius: 4,
              transition: 'height 0.3s ease',
            }}
          />
          <span style={{ fontSize: 10, color: 'var(--ms-text-muted)' }}>{d.date.slice(5)}</span>
        </div>
      ))}
    </div>
  )
}

export default function OverviewPanel({ onNavigate }: { onNavigate: (tab: string) => void }) {
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [dashboard, setDashboard] = useState(null)
  const [highRisk, setHighRisk] = useState([])
  const [stats, setStats] = useState(null)

  // AUD-019：加载失败不再静默留白——置错误态 + 重试（与 BigScreen 错误态一致）
  // FA-08：单一 load（初次挂载与错误重试共用），mountedRef 守卫防卸载后 setState
  const mountedRef = useRef(true)
  useEffect(() => () => { mountedRef.current = false }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setLoadError(false)
    try {
      const [dash, hr, st] = await Promise.all([getDashboard(), getHighRiskStudents(), getStats()])
      if (!mountedRef.current) return
      setDashboard(dash)
      setHighRisk(hr)
      setStats(st)
    } catch (e) {
      console.error('加载工作台数据失败', e)
      if (mountedRef.current) setLoadError(true)
    } finally {
      if (mountedRef.current) setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  // BUG-UI-05：工作台数据 30s 轮询刷新——认领/处置预警后计数与今日待办自动更新，不依赖手动刷新
  usePolling(load, 30000, { immediate: false })

  if (loading) {
    return <div className="ms-empty-lg"><Spin size="large" /></div>
  }

  if (loadError) {
    return (
      <div style={{ padding: 80 }}>
        <Alert
          type="error"
          showIcon
          message="工作台数据加载失败"
          description="网络异常或服务暂不可用，请检查后重试。"
          action={<Button onClick={load}>重新加载</Button>}
        />
      </div>
    )
  }
  return (
    <div>
      {/* 今日待办（行动驱动首屏，WB-001） */}
      <div className="ms-mb-16">
        <TodayTodoPanel onNavigate={onNavigate} />
      </div>

      {/* 周报导出 */}
      <div className="ms-mb-16" style={{ textAlign: 'right' }}>
        <Button icon={<FileTextOutlined />} onClick={openWeeklyReport}>导出周报（可打印 PDF）</Button>
      </div>

      {/* 统计卡片 */}
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={6}>
          <Card hoverable onClick={() => onNavigate('alerts')} size="small">
            <Statistic
              title="待处理预警"
              value={dashboard?.pendingAlerts ?? 0}
              prefix={<AlertOutlined className="ms-text-danger" />}
              valueStyle={{ color: dashboard?.pendingAlerts > 0 ? 'var(--ms-danger)' : 'var(--ms-success)' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={6}>
          <Card size="small">
            <Statistic
              title="今日新增预警"
              value={dashboard?.todayAlerts ?? 0}
              prefix={<ClockCircleOutlined className="ms-text-warning" />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={6}>
          <Card size="small">
            <Statistic
              title="今日活跃会话"
              value={dashboard?.todaySessions ?? 0}
              prefix={<MessageOutlined className="ms-text-primary" />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={6}>
          <Card size="small">
            <Statistic
              title="近30天平均满意度"
              value={dashboard?.avgSatisfaction ?? '-'}
              suffix={dashboard?.satisfactionCount > 0 ? '/ 5' : ''}
              prefix={<SmileOutlined className="ms-text-success" />}
              valueStyle={{ color: (dashboard?.avgSatisfaction ?? 0) >= 3.5 ? 'var(--ms-success)' : 'var(--ms-warning)' }}
            />
            <div style={{ fontSize: 11, color: 'var(--ms-text-muted)', marginTop: 4 }}>
              {dashboard?.satisfactionCount ?? 0} 条评价
            </div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} className="ms-mt-16">
        {/* 30天会话趋势 */}
        <Col xs={24} lg={14}>
          <Card title="近 30 天会话趋势" size="small">
            <SessionTrendChart data={stats?.sessionTrend} />
          </Card>
        </Col>

        {/* 风险等级分布 */}
        <Col xs={24} lg={10}>
          <Card title="风险等级分布" size="small">
            <RiskPieChart data={stats?.riskDistribution} />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} className="ms-mt-16">
        {/* 班级对比 */}
        <Col xs={24} lg={14}>
          <Card title="班级预警对比" size="small">
            <ClassBarChart data={stats?.classComparison} />
          </Card>
        </Col>

        {/* 情绪分布 */}
        <Col xs={24} lg={10}>
          <Card title="近 30 天学生情绪分布" size="small">
            <EmotionBarChart data={stats?.emotionDistribution} />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} className="ms-mt-16">
        {/* 高风险学生 */}
        <Col xs={24}>
          <Card
            title={<span><RiseOutlined className="ms-text-danger" /> 高风险学生</span>}
            size="small"
            extra={<a onClick={() => onNavigate('students')}>查看全部</a>}
          >
            {highRisk.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '24px 0', color: 'var(--ms-text-muted)' }}>
                暂无高风险学生 🎉
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {highRisk.slice(0, 5).map((s) => (
                  <div key={s.studentUserId} style={{
                    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                    padding: '8px 12px', background: 'var(--ms-bg-elevated)', borderRadius: 6,
                  }}>
                    <span>{s.displayName}</span>
                    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                      <Tag color={riskColor(s.maxRiskLevel)}>{riskLabel(s.maxRiskLevel)}</Tag>
                      <span className="ms-hint">{s.openAlertCount} 条预警</span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </Card>
        </Col>
      </Row>

      {/* 满意度看板 */}
      <SatisfactionCard />
    </div>
  )
}

/** 满意度卡片 */
function SatisfactionCard() {
  const [data, setData] = useState<SatisfactionVO | null>(null)
  // F-09：加载失败不静默——console.error + 局部错误条 + 重试（AUD-019 只覆盖主加载）
  const [error, setError] = useState<string | null>(null)
  const [retryKey, setRetryKey] = useState(0)

  useEffect(() => {
    getSatisfaction()
      .then(d => { setData(d); setError(null) })
      .catch((e) => {
        console.error('[OverviewPanel] 加载满意度失败:', e)
        setError('满意度数据加载失败，请检查网络后重试')
      })
  }, [retryKey])

  // F-09：加载失败展示错误条（提供重试入口）
  if (error) {
    return (
      <Alert type="error" showIcon message={error} className="ms-mt-16"
        action={<Button size="small" onClick={() => setRetryKey(k => k + 1)}>重试</Button>} />
    )
  }

  if (!data || data.totalRated === 0) return null

  return (
    <Card title={<span><SmileOutlined className="ms-text-warning" /> 学生满意度</span>} size="small" className="ms-mt-16">
      <Row gutter={16} align="middle">
        <Col span={6} style={{ textAlign: 'center' }}>
          <div className="ms-stat-big ms-text-warning">{data.avgRating}</div>
          <div className="ms-hint">平均评分（共 {data.totalRated} 次）</div>
        </Col>
        <Col span={6} style={{ textAlign: 'center' }}>
          <div className="ms-stat-big ms-text-success">{data.recentAvg}</div>
          <div className="ms-hint">近 7 天（{data.recentCount} 次）</div>
        </Col>
        <Col span={12}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            {data.distribution?.map(d => (
              <div key={d.stars} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <span style={{ width: 36, fontSize: 12 }}>{d.stars}★</span>
                <div style={{ flex: 1, height: 10, background: 'var(--ms-border-soft)', borderRadius: 5, overflow: 'hidden' }}>
                  <div style={{
                    width: `${data.totalRated ? d.count / data.totalRated * 100 : 0}%`,
                    height: '100%', background: 'var(--ms-warning)', borderRadius: 5,
                  }} />
                </div>
                <span style={{ width: 24, fontSize: 11, color: 'var(--ms-text-muted)', textAlign: 'right' }}>{d.count}</span>
              </div>
            ))}
          </div>
        </Col>
      </Row>
    </Card>
  )
}
