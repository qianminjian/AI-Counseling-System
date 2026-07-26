import { useState, useEffect } from 'react'
import { Card, Row, Col, Statistic, Spin, Tag, Button } from 'antd'
import {
  AlertOutlined, ClockCircleOutlined, MessageOutlined, RiseOutlined, SmileOutlined, FileTextOutlined,
} from '@ant-design/icons'
import { getDashboard, getHighRiskStudents, getStats, openWeeklyReport, getSatisfaction } from '../../api'
import { SessionTrendChart, RiskPieChart, ClassBarChart, EmotionBarChart } from './StatsCharts'

/** 轻量 CSS 柱状图（7 天趋势） */
function WeeklyChart({ data }) {
  if (!data || data.length === 0) return null
  const max = Math.max(...data.map((d) => d.count), 1)

  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, height: 120, padding: '0 4px' }}>
      {data.map((d) => (
        <div key={d.date} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
          <span style={{ fontSize: 11, color: '#666' }}>{d.count}</span>
          <div
            style={{
              width: '100%',
              maxWidth: 32,
              height: `${Math.max((d.count / max) * 80, 4)}px`,
              background: d.count > 0 ? 'linear-gradient(to top, #ff7875, #ffa39e)' : '#f0f0f0',
              borderRadius: 4,
              transition: 'height 0.3s ease',
            }}
          />
          <span style={{ fontSize: 10, color: '#999' }}>{d.date.slice(5)}</span>
        </div>
      ))}
    </div>
  )
}

export default function OverviewPanel({ onNavigate }) {
  const [loading, setLoading] = useState(true)
  const [dashboard, setDashboard] = useState(null)
  const [highRisk, setHighRisk] = useState([])
  const [stats, setStats] = useState(null)

  useEffect(() => {
    let cancelled = false
    async function load() {
      try {
        const [dash, hr, st] = await Promise.all([getDashboard(), getHighRiskStudents(), getStats()])
        if (!cancelled) {
          setDashboard(dash)
          setHighRisk(hr)
          setStats(st)
        }
      } catch (e) {
        console.error('加载工作台数据失败', e)
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    load()
    return () => { cancelled = true }
  }, [])

  if (loading) {
    return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>
  }

  const RISK_COLORS = { 3: 'red', 2: 'orange', 1: 'gold' }
  const RISK_LABELS = { 3: '红色', 2: '橙色', 1: '黄色' }

  return (
    <div>
      {/* 周报导出 */}
      <div style={{ marginBottom: 16, textAlign: 'right' }}>
        <Button icon={<FileTextOutlined />} onClick={openWeeklyReport}>导出周报（可打印 PDF）</Button>
      </div>

      {/* 统计卡片 */}
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={6}>
          <Card hoverable onClick={() => onNavigate('alerts')} size="small">
            <Statistic
              title="待处理预警"
              value={dashboard?.pendingAlerts ?? 0}
              prefix={<AlertOutlined style={{ color: '#ff4d4f' }} />}
              valueStyle={{ color: dashboard?.pendingAlerts > 0 ? '#ff4d4f' : '#3f8600' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={6}>
          <Card size="small">
            <Statistic
              title="今日新增预警"
              value={dashboard?.todayAlerts ?? 0}
              prefix={<ClockCircleOutlined style={{ color: '#fa8c16' }} />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={6}>
          <Card size="small">
            <Statistic
              title="今日活跃会话"
              value={dashboard?.todaySessions ?? 0}
              prefix={<MessageOutlined style={{ color: '#1890ff' }} />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={6}>
          <Card size="small">
            <Statistic
              title="近30天平均满意度"
              value={dashboard?.avgSatisfaction ?? '-'}
              suffix={dashboard?.satisfactionCount > 0 ? '/ 5' : ''}
              prefix={<SmileOutlined style={{ color: '#52c41a' }} />}
              valueStyle={{ color: (dashboard?.avgSatisfaction ?? 0) >= 3.5 ? '#52c41a' : '#fa8c16' }}
            />
            <div style={{ fontSize: 11, color: '#999', marginTop: 4 }}>
              {dashboard?.satisfactionCount ?? 0} 条评价
            </div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
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

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
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

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        {/* 高风险学生 */}
        <Col xs={24}>
          <Card
            title={<span><RiseOutlined style={{ color: '#ff4d4f' }} /> 高风险学生</span>}
            size="small"
            extra={<a onClick={() => onNavigate('students')}>查看全部</a>}
          >
            {highRisk.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '24px 0', color: '#999' }}>
                暂无高风险学生 🎉
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {highRisk.slice(0, 5).map((s) => (
                  <div key={s.studentUserId} style={{
                    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                    padding: '8px 12px', background: '#fafafa', borderRadius: 6,
                  }}>
                    <span>{s.displayName}</span>
                    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                      <Tag color={RISK_COLORS[s.maxRiskLevel]}>{RISK_LABELS[s.maxRiskLevel]}</Tag>
                      <span style={{ fontSize: 12, color: '#999' }}>{s.openAlertCount} 条预警</span>
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
  const [data, setData] = useState(null)

  useEffect(() => {
    getSatisfaction().then(setData).catch(() => {})
  }, [])

  if (!data || data.totalRated === 0) return null

  return (
    <Card title={<span><SmileOutlined style={{ color: '#faad14' }} /> 学生满意度</span>} size="small" style={{ marginTop: 16 }}>
      <Row gutter={16} align="middle">
        <Col span={6} style={{ textAlign: 'center' }}>
          <div style={{ fontSize: 32, fontWeight: 700, color: '#faad14' }}>{data.avgRating}</div>
          <div style={{ fontSize: 12, color: '#999' }}>平均评分（共 {data.totalRated} 次）</div>
        </Col>
        <Col span={6} style={{ textAlign: 'center' }}>
          <div style={{ fontSize: 32, fontWeight: 700, color: '#52c41a' }}>{data.recentAvg}</div>
          <div style={{ fontSize: 12, color: '#999' }}>近 7 天（{data.recentCount} 次）</div>
        </Col>
        <Col span={12}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            {data.distribution?.map(d => (
              <div key={d.stars} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <span style={{ width: 36, fontSize: 12 }}>{d.stars}★</span>
                <div style={{ flex: 1, height: 10, background: '#f0f0f0', borderRadius: 5, overflow: 'hidden' }}>
                  <div style={{
                    width: `${data.totalRated ? d.count / data.totalRated * 100 : 0}%`,
                    height: '100%', background: '#faad14', borderRadius: 5,
                  }} />
                </div>
                <span style={{ width: 24, fontSize: 11, color: '#999', textAlign: 'right' }}>{d.count}</span>
              </div>
            ))}
          </div>
        </Col>
      </Row>
    </Card>
  )
}
