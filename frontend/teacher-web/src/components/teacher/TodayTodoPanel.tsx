import { useState, useEffect, useCallback } from 'react'
import { Card, Tag, Button, Empty, Spin, Row, Col, Timeline, message } from 'antd'
import {
  AlertOutlined, ClockCircleOutlined, CheckCircleOutlined,
  UserOutlined, FieldTimeOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { getAlerts, claimAlert, getPendingFollowups } from '../../api'
import { evaluateSla, urgencyWeight } from '../../utils/sla'

const RISK_COLORS = { 3: 'red', 2: 'orange', 1: 'gold', 0: 'default' }
const RISK_LABELS = { 3: '红色', 2: '橙色', 1: '黄色', 0: '绿色' }
const RISK_DOT = { 3: 'var(--ms-danger)', 2: 'var(--ms-warning)', 1: 'var(--ms-warning)', 0: 'var(--ms-success)' }

/** SLA 倒计时徽标（逾期红色闪烁提示） */
function SlaBadge({ riskLevel, status, detectedAt }) {
  const sla = evaluateSla(riskLevel, status, detectedAt)
  if (!sla.hasSla) return <span style={{ fontSize: 11, color: '#999' }}>无时限</span>

  if (sla.breached) {
    return (
      <Tag color="red" style={{ margin: 0, fontWeight: 600 }}>
        <FieldTimeOutlined /> 逾期 {sla.overdueMin}min{sla.escalate ? ' · 已升级' : ''}
      </Tag>
    )
  }
  if (sla.remainingMin > 0) {
    const urgent = sla.remainingMin <= 5
    return (
      <Tag color={urgent ? 'orange' : 'blue'} style={{ margin: 0 }}>
        <ClockCircleOutlined /> 剩 {sla.remainingMin}min
      </Tag>
    )
  }
  return null
}

/**
 * 今日待办面板（WB-001，design/35 §3.2）
 * 行动驱动首屏：教师打开 10 秒内知道今天该处理谁。
 * 左：今日待办（SLA 倒计时排序）；右：24h 预警时间线。
 */
export default function TodayTodoPanel({ onNavigate }) {
  const [alerts, setAlerts] = useState([])
  const [followups, setFollowups] = useState([])
  const [loading, setLoading] = useState(true)
  const [claimingId, setClaimingId] = useState(null)

  const load = useCallback(async () => {
    try {
      const [openAlerts, fus] = await Promise.all([
        getAlerts({ limit: 100 }),
        getPendingFollowups().catch(() => []),
      ])
      // 待办 = 未关闭的预警（open/claimed）
      const pending = (openAlerts || []).filter(a => a.status === 'open' || a.status === 'claimed')
      setAlerts(pending)
      setFollowups(fus || [])
    } catch (e) {
      message.error('加载今日待办失败: ' + e.message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  // 每 30s 刷新 SLA 倒计时
  // AUD-047：页面不可见时暂停轮询（与 Dashboard 15s 轮询叠加时不空转请求）
  useEffect(() => {
    const timer = setInterval(() => {
      if (document.hidden) return
      load()
    }, 30000)
    return () => clearInterval(timer)
  }, [load])

  const handleClaim = async (id) => {
    setClaimingId(id)
    try {
      await claimAlert(id)
      message.success('已认领')
      load()
    } catch (e) {
      message.error(e.message)
    } finally {
      setClaimingId(null)
    }
  }

  // 待办排序：逾期 > SLA 剩余
  const sortedAlerts = [...alerts].sort(
    (a, b) => urgencyWeight(a.riskLevel, a.status, a.detectedAt) - urgencyWeight(b.riskLevel, b.status, b.detectedAt)
  )
  const overdueCount = alerts.filter(a => evaluateSla(a.riskLevel, a.status, a.detectedAt).breached).length

  // 24h 时间线（全部预警，按时间倒序）
  const timeline = [...alerts]
    .sort((a, b) => new Date(b.detectedAt).getTime() - new Date(a.detectedAt).getTime())
    .slice(0, 12)

  if (loading) {
    return <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>
  }

  return (
    <Row gutter={[16, 16]}>
      {/* ① 今日待办 */}
      <Col xs={24} lg={14}>
        <Card
          size="small"
          title={
            <span>
              <AlertOutlined style={{ color: 'var(--ms-danger)' }} /> 今日待办
              {overdueCount > 0 && (
                <Tag color="red" style={{ marginLeft: 8 }}>{overdueCount} 项逾期</Tag>
              )}
            </span>
          }
          extra={<a onClick={() => onNavigate('alerts')}>全部预警</a>}
        >
          {sortedAlerts.length === 0 && followups.length === 0 ? (
            <Empty description="今日暂无待办 🎉" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {sortedAlerts.map(a => {
                const sla = evaluateSla(a.riskLevel, a.status, a.detectedAt)
                return (
                  <div
                    key={a.alertId}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 10, padding: '10px 12px',
                      borderRadius: 8,
                      background: sla.breached ? 'var(--ms-danger-soft)' : '#fafafa',
                      border: sla.breached ? '1px solid var(--ms-danger-soft)' : '1px solid #f0f0f0',
                    }}
                  >
                    <span style={{
                      width: 10, height: 10, borderRadius: '50%', flexShrink: 0,
                      background: RISK_DOT[a.riskLevel] || '#d9d9d9',
                    }} />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                        <Tag color={RISK_COLORS[a.riskLevel]} style={{ margin: 0 }}>{RISK_LABELS[a.riskLevel]}</Tag>
                        <span style={{ fontWeight: 500 }}>{a.studentName}</span>
                        <span style={{ fontSize: 12, color: '#999' }}>{a.riskType}</span>
                      </div>
                      <div style={{ fontSize: 12, color: '#999', marginTop: 2 }}>
                        {dayjs(a.detectedAt).format('HH:mm')} · {a.status === 'claimed' ? '已认领' : '待认领'}
                      </div>
                    </div>
                    <SlaBadge riskLevel={a.riskLevel} status={a.status} detectedAt={a.detectedAt} />
                    {a.status === 'open' && (
                      <Button
                        size="small"
                        icon={<UserOutlined />}
                        loading={claimingId === a.alertId}
                        onClick={() => handleClaim(a.alertId)}
                      >
                        认领
                      </Button>
                    )}
                  </div>
                )
              })}

              {/* 复测/随访待办 */}
              {followups.map(f => (
                <div key={f.riskEventId} style={{
                  display: 'flex', alignItems: 'center', gap: 10, padding: '10px 12px',
                  borderRadius: 8, background: 'var(--ms-success-soft)', border: '1px solid #d9f7be',
                }}>
                  <CheckCircleOutlined style={{ color: 'var(--ms-success)' }} />
                  <div style={{ flex: 1 }}>
                    <span style={{ fontWeight: 500 }}>回访待完成</span>
                    <span style={{ fontSize: 12, color: '#999', marginLeft: 8 }}>
                      {f.riskType} · 计划 {f.followUpAt ? dayjs(f.followUpAt).format('MM-DD HH:mm') : '尽快'}
                    </span>
                  </div>
                  <Button size="small" onClick={() => onNavigate('alerts')}>去处理</Button>
                </div>
              ))}
            </div>
          )}
        </Card>
      </Col>

      {/* ② 预警时间线（24h 滚动流） */}
      <Col xs={24} lg={10}>
        <Card size="small" title={<span><ClockCircleOutlined /> 预警时间线（24h）</span>}>
          {timeline.length === 0 ? (
            <Empty description="24 小时内无预警" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            <Timeline
              items={timeline.map(a => ({
                color: RISK_DOT[a.riskLevel] || 'gray',
                children: (
                  <div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                      <span style={{ fontSize: 12, color: '#999' }}>{dayjs(a.detectedAt).format('MM-DD HH:mm')}</span>
                      <Tag color={RISK_COLORS[a.riskLevel]} style={{ margin: 0, fontSize: 11 }}>{RISK_LABELS[a.riskLevel]}</Tag>
                      <span style={{ fontSize: 13 }}>{a.studentName}</span>
                    </div>
                    <div style={{ fontSize: 12, color: '#999', marginTop: 2 }}>
                      {a.riskType} · {a.status === 'resolved' ? '已处理 ✓' : a.status === 'claimed' ? '处理中' : '待处理'}
                    </div>
                  </div>
                ),
              }))}
            />
          )}
        </Card>
      </Col>
    </Row>
  )
}
