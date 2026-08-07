import { useState, useEffect, useCallback } from 'react'
import { getStats, getDashboard, getSatisfaction } from '../api'
import { emotionLabel } from '../utils/emotionLabels'

const REFRESH_INTERVAL = 30000

/** 数据大屏（学校展厅 / 领导汇报用）；onExit 存在时显示“返回工作台”（F-3：管理者默认落地大屏，design/35 §3.1） */
export default function BigScreen({ onExit }: { onExit?: () => void }) {
  const [stats, setStats] = useState(null)
  const [dashboard, setDashboard] = useState(null)
  const [satisfaction, setSatisfaction] = useState(null)
  const [time, setTime] = useState(new Date())
  // P2-13：加载失败不静默——错误提示 + 重试入口
  const [error, setError] = useState<string | null>(null)

  const fetchData = useCallback(async () => {
    try {
      const [s, d, sat] = await Promise.all([getStats(), getDashboard(), getSatisfaction()])
      setStats(s)
      setDashboard(d)
      setSatisfaction(sat)
      setError(null)
    } catch {
      setError('数据加载失败，请检查网络后重试')
    }
  }, [])

  useEffect(() => {
    fetchData()
    // AUD-047：页面不可见时暂停轮询（大屏切后台不空转请求）
    const timer = setInterval(() => {
      if (document.hidden) return
      fetchData()
    }, REFRESH_INTERVAL)
    const clock = setInterval(() => setTime(new Date()), 1000)
    return () => { clearInterval(timer); clearInterval(clock) }
  }, [fetchData])

  const s = stats || {}
  const d = dashboard || {}

  return (
    <div style={styles.root}>
      {/* 标题栏 */}
      <header style={styles.header}>
        <div style={styles.headerLeft}>
          <span style={{ fontSize: 28 }}>🛡️</span>
          <span style={styles.title}>MindSafe 学生心理守护平台</span>
        </div>
        <div style={styles.headerRight}>
          {onExit && (
            <button onClick={onExit} style={styles.exitButton} aria-label="返回工作台">
              ← 返回工作台
            </button>
          )}
          {time.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })}
          <span style={styles.clock}>{time.toLocaleTimeString('zh-CN')}</span>
        </div>
      </header>

      {/* P2-13：加载失败错误条（不静默） */}
      {error && (
        <div style={styles.errorBar} role="alert">
          <span>⚠️ {error}</span>
          <button onClick={fetchData} style={styles.retryButton} aria-label="重试">重试</button>
        </div>
      )}

      {/* 核心指标 */}
      <div style={styles.metricsRow}>
        <MetricCard label="今日会话" value={d.todaySessions || 0} unit="次" color="#4fc3f7" />
        <MetricCard label="活跃学生" value={d.activeStudents || 0} unit="人" color="#81c784" />
        <MetricCard label="待处理预警" value={d.pendingAlerts || 0} unit="条" color="#ff8a65" />
        <MetricCard label="平均满意度" value={satisfaction?.avgRating || '-'} unit="/ 5" color="#ffd54f" />
        <MetricCard label="累计会话" value={d.totalSessions || 0} unit="次" color="#ce93d8" />
      </div>

      {/* 图表区域 */}
      <div style={styles.chartsRow}>
        {/* 7 天会话趋势 */}
        <div style={styles.chartCard}>
          <h3 style={styles.chartTitle}>📈 近 7 天会话趋势</h3>
          <BarChart data={s.sessionTrend || []} dataKey="count" labelKey="date" color="#4fc3f7" />
        </div>

        {/* 情绪分布 */}
        <div style={styles.chartCard}>
          <h3 style={styles.chartTitle}>🎭 情绪分布</h3>
          <EmotionBars data={s.emotionDistribution || []} />
        </div>

        {/* 风险等级分布 */}
        <div style={styles.chartCard}>
          <h3 style={styles.chartTitle}>⚠️ 风险等级分布</h3>
          <RiskDonut data={s.riskDistribution || []} />
        </div>
      </div>

      {/* 底部：班级对比 */}
      <div style={styles.bottomRow}>
        <div style={{ ...styles.chartCard, flex: 1 }}>
          <h3 style={styles.chartTitle}>🏫 班级预警对比</h3>
          <ClassBars data={s.classComparison || []} />
        </div>
      </div>

      {/* 水印 */}
      <div style={styles.watermark}>MindSafe · 数据每 30 秒自动刷新</div>
    </div>
  )
}

function MetricCard({ label, value, unit, color }) {
  return (
    <div style={styles.metricCard}>
      <div style={{ ...styles.metricValue, color }}>{value}<span style={styles.metricUnit}>{unit}</span></div>
      <div style={styles.metricLabel}>{label}</div>
    </div>
  )
}

function BarChart({ data, dataKey, labelKey, color }) {
  const max = Math.max(...data.map(d => d[dataKey] || 0), 1)
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, height: 140, padding: '0 8px' }}>
      {data.map((d, i) => (
        <div key={i} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
          <span style={{ fontSize: 11, color: '#aaa' }}>{d[dataKey] || 0}</span>
          <div style={{
            width: '70%', borderRadius: 4, background: color, opacity: 0.85,
            height: `${Math.max((d[dataKey] || 0) / max * 100, 4)}px`,
            transition: 'height 0.6s ease',
          }} />
          <span style={{ fontSize: 10, color: '#666' }}>{(d[labelKey] || '').slice(5)}</span>
        </div>
      ))}
    </div>
  )
}

const EMOTION_COLORS = { happy: '#ffd54f', sad: '#64b5f6', angry: '#ef5350', scared: '#ce93d8', nervous: '#ffb74d', neutral: '#90a4ae' }

function EmotionBars({ data }) {
  const max = Math.max(...data.map(d => d.count || 0), 1)
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: '8px 0' }}>
      {data.slice(0, 6).map((d, i) => (
        <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ width: 48, fontSize: 12, color: '#ccc', textAlign: 'right' }}>{emotionLabel(d.emotion)}</span>
          <div style={{ flex: 1, height: 16, background: '#1e2a3a', borderRadius: 8, overflow: 'hidden' }}>
            <div style={{
              height: '100%', borderRadius: 8, transition: 'width 0.6s ease',
              width: `${(d.count || 0) / max * 100}%`,
              background: EMOTION_COLORS[d.emotion] || '#4fc3f7',
            }} />
          </div>
          <span style={{ width: 32, fontSize: 11, color: '#888' }}>{d.count}</span>
        </div>
      ))}
    </div>
  )
}

const RISK_COLORS = { 0: '#4caf50', 1: '#ffd54f', 2: '#ff9800', 3: '#f44336' }
const RISK_LABELS = { 0: '安全', 1: '黄色', 2: '橙色', 3: '红色' }

function RiskDonut({ data }) {
  const total = data.reduce((s, d) => s + (d.count || 0), 0) || 1
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 24, padding: '16px 0' }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {data.map((d, i) => (
          <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <div style={{ width: 12, height: 12, borderRadius: 3, background: RISK_COLORS[d.level] || '#666' }} />
            <span style={{ fontSize: 12, color: '#ccc' }}>{RISK_LABELS[d.level] || d.label}</span>
            <span style={{ fontSize: 12, color: '#888' }}>{d.count} ({Math.round((d.count || 0) / total * 100)}%)</span>
          </div>
        ))}
      </div>
    </div>
  )
}

function ClassBars({ data }) {
  const max = Math.max(...data.map(d => d.alertCount || 0), 1)
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12, height: 100, padding: '0 16px' }}>
      {data.slice(0, 8).map((d, i) => (
        <div key={i} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
          <span style={{ fontSize: 11, color: '#ff8a65' }}>{d.alertCount}</span>
          <div style={{
            width: '60%', borderRadius: 4, background: '#ff8a65', opacity: 0.8,
            height: `${Math.max((d.alertCount || 0) / max * 70, 4)}px`,
          }} />
          <span style={{ fontSize: 10, color: '#888' }}>{d.classCode}</span>
        </div>
      ))}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  root: {
    minHeight: '100vh', background: 'linear-gradient(180deg, #0a1628 0%, #0d2137 100%)',
    color: '#e0e0e0', fontFamily: '-apple-system, sans-serif', padding: '20px 32px',
    display: 'flex', flexDirection: 'column', gap: 20,
  },
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'center' },
  headerLeft: { display: 'flex', alignItems: 'center', gap: 12 },
  title: { fontSize: 22, fontWeight: 700, color: '#fff', letterSpacing: 2 },
  headerRight: { fontSize: 13, color: '#888', display: 'flex', gap: 16 },
  exitButton: {
    background: 'rgba(79,195,247,0.12)', color: '#4fc3f7', border: '1px solid rgba(79,195,247,0.4)',
    borderRadius: 6, padding: '4px 12px', fontSize: 12, cursor: 'pointer',
  },
  errorBar: {
    display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 12,
    background: 'rgba(244,67,54,0.12)', border: '1px solid rgba(244,67,54,0.4)',
    color: '#ff8a80', borderRadius: 8, padding: '8px 16px', fontSize: 13,
  },
  retryButton: {
    background: 'rgba(244,67,54,0.2)', color: '#ff8a80', border: '1px solid rgba(244,67,54,0.5)',
    borderRadius: 6, padding: '2px 12px', fontSize: 12, cursor: 'pointer',
  },
  clock: { fontSize: 18, color: '#4fc3f7', fontWeight: 600 },
  metricsRow: { display: 'flex', gap: 16 },
  metricCard: {
    flex: 1, background: 'rgba(255,255,255,0.04)', borderRadius: 12,
    padding: '20px 16px', textAlign: 'center', border: '1px solid rgba(255,255,255,0.06)',
  },
  metricValue: { fontSize: 36, fontWeight: 700 },
  metricUnit: { fontSize: 14, marginLeft: 4, opacity: 0.6 },
  metricLabel: { fontSize: 13, color: '#999', marginTop: 6 },
  chartsRow: { display: 'flex', gap: 16, flex: 1 },
  chartCard: {
    flex: 1, background: 'rgba(255,255,255,0.03)', borderRadius: 12,
    padding: '16px 20px', border: '1px solid rgba(255,255,255,0.06)',
  },
  chartTitle: { fontSize: 14, color: '#ccc', marginBottom: 12, fontWeight: 500 },
  bottomRow: { display: 'flex', gap: 16 },
  watermark: { textAlign: 'center', fontSize: 11, color: '#444', marginTop: 8 },
}
