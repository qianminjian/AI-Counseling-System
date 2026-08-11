import { useState, useEffect, useCallback } from 'react'
import type { CSSProperties } from 'react'
import { getStats, getDashboard, getSatisfaction } from '../api'
import type { DailyCount, RiskDistItem, ClassRiskItem, EmotionItem } from '../api'
import { emotionLabel } from '../../../shared/src/emotionMeta'
import { riskHexBright, riskLabel } from '../utils/riskLevel'
import { usePolling } from '../hooks/usePolling'

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

  // 轮询收敛（F3）：初始加载 + 30s 刷新；AUD-047 不可见暂停由 usePolling 默认承担
  usePolling(fetchData, REFRESH_INTERVAL)

  useEffect(() => {
    // 时钟 1s 刷新（实时时钟不走轮询收敛）
    const clock = setInterval(() => setTime(new Date()), 1000)
    return () => clearInterval(clock)
  }, [])

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
        <MetricCard label="今日会话" value={d.todaySessions || 0} unit="次" color="var(--ms-chart-1)" />
        <MetricCard label="活跃学生" value={d.activeStudents || 0} unit="人" color="var(--ms-chart-2)" />
        <MetricCard label="待处理预警" value={d.pendingAlerts || 0} unit="条" color="var(--ms-chart-3)" />
        <MetricCard label="平均满意度" value={satisfaction?.avgRating || '-'} unit="/ 5" color="var(--ms-chart-4)" />
        <MetricCard label="累计会话" value={d.totalSessions || 0} unit="次" color="var(--ms-chart-5)" />
      </div>

      {/* 图表区域 */}
      <div style={styles.chartsRow}>
        {/* 7 天会话趋势 */}
        <div style={styles.chartCard}>
          <h3 style={styles.chartTitle}>📈 近 7 天会话趋势</h3>
          <BarChart data={s.sessionTrend || []} dataKey="count" labelKey="date" color="var(--ms-chart-1)" />
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

function MetricCard({ label, value, unit, color }: { label: string; value: React.ReactNode; unit: string; color: string }) {
  return (
    <div style={styles.metricCard}>
      <div style={{ ...styles.metricValue, color }}>{value}<span style={styles.metricUnit}>{unit}</span></div>
      <div style={styles.metricLabel}>{label}</div>
    </div>
  )
}

function BarChart({ data, dataKey, labelKey, color }: {
  data: DailyCount[]; dataKey: keyof DailyCount; labelKey: keyof DailyCount; color: string
}) {
  const max = Math.max(...data.map(d => Number(d[dataKey]) || 0), 1)
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, height: 140, padding: '0 8px' }}>
      {data.map((d, i) => (
        <div key={i} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
          <span style={{ fontSize: 11, color: 'var(--ms-bs-text-muted)' }}>{Number(d[dataKey]) || 0}</span>
          <div style={{
            width: '70%', borderRadius: 4, background: color, opacity: 0.85,
            height: `${Math.max((Number(d[dataKey]) || 0) / max * 100, 4)}px`,
            transition: 'height 0.6s ease',
          }} />
          <span style={{ fontSize: 10, color: 'var(--ms-bs-text-faint)' }}>{String(d[labelKey] ?? '').slice(5)}</span>
        </div>
      ))}
    </div>
  )
}

// F-06：情绪图表色收编 --ms-chart-* 系列（与指标卡/柱图同 token 体系）
const EMOTION_COLORS: Record<string, string> = {
  happy: 'var(--ms-chart-4)', sad: 'var(--ms-chart-6)', angry: 'var(--ms-chart-7)',
  scared: 'var(--ms-chart-5)', nervous: 'var(--ms-chart-8)', neutral: 'var(--ms-chart-9)',
}

function EmotionBars({ data }: { data: EmotionItem[] }) {
  const max = Math.max(...data.map(d => d.count || 0), 1)
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: '8px 0' }}>
      {data.slice(0, 6).map((d, i) => (
        <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ width: 48, fontSize: 12, color: 'var(--ms-bs-text-fainter)', textAlign: 'right' }}>{emotionLabel(d.emotion)}</span>
          <div style={{ flex: 1, height: 16, background: 'var(--ms-bs-track)', borderRadius: 8, overflow: 'hidden' }}>
            <div style={{
              height: '100%', borderRadius: 8, transition: 'width 0.6s ease',
              width: `${(d.count || 0) / max * 100}%`,
              background: EMOTION_COLORS[d.emotion] || 'var(--ms-chart-1)',
            }} />
          </div>
          <span style={{ width: 32, fontSize: 11, color: 'var(--ms-bs-text-dim)' }}>{d.count}</span>
        </div>
      ))}
    </div>
  )
}

function RiskDonut({ data }: { data: RiskDistItem[] }) {
  const total = data.reduce((s, d) => s + (d.count || 0), 0) || 1
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 24, padding: '16px 0' }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {data.map((d, i) => (
          <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <div style={{ width: 12, height: 12, borderRadius: 3, background: riskHexBright(d.level) }} />
            <span style={{ fontSize: 12, color: 'var(--ms-bs-text-fainter)' }}>{riskLabel(d.level) || d.label}</span>
            <span style={{ fontSize: 12, color: 'var(--ms-bs-text-dim)' }}>{d.count} ({Math.round((d.count || 0) / total * 100)}%)</span>
          </div>
        ))}
      </div>
    </div>
  )
}

function ClassBars({ data }: { data: ClassRiskItem[] }) {
  const max = Math.max(...data.map(d => d.alertCount || 0), 1)
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12, height: 100, padding: '0 16px' }}>
      {data.slice(0, 8).map((d, i) => (
        <div key={i} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
          <span style={{ fontSize: 11, color: 'var(--ms-chart-3)' }}>{d.alertCount}</span>
          <div style={{
            width: '60%', borderRadius: 4, background: 'var(--ms-chart-3)', opacity: 0.8,
            height: `${Math.max((d.alertCount || 0) / max * 70, 4)}px`,
          }} />
          <span style={{ fontSize: 10, color: 'var(--ms-bs-text-dim)' }}>{d.classCode}</span>
        </div>
      ))}
    </div>
  )
}

const styles: Record<string, CSSProperties> = {
  root: {
    minHeight: '100vh', background: 'linear-gradient(180deg, var(--ms-bs-bg-start) 0%, var(--ms-bs-bg-end) 100%)',
    color: 'var(--ms-bs-text)', fontFamily: '-apple-system, sans-serif', padding: '20px 32px',
    display: 'flex', flexDirection: 'column', gap: 20,
  },
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'center' },
  headerLeft: { display: 'flex', alignItems: 'center', gap: 12 },
  title: { fontSize: 22, fontWeight: 700, color: 'var(--ms-bs-text-strong)', letterSpacing: 2 },
  headerRight: { fontSize: 13, color: 'var(--ms-bs-text-dim)', display: 'flex', gap: 16 },
  exitButton: {
    background: 'var(--ms-bs-exit-bg)', color: 'var(--ms-chart-1)', border: '1px solid var(--ms-bs-exit-border)',
    borderRadius: 6, padding: '4px 12px', fontSize: 12, cursor: 'pointer',
  },
  errorBar: {
    display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 12,
    background: 'var(--ms-bs-error-bg)', border: '1px solid var(--ms-bs-error-border)',
    color: 'var(--ms-bs-error)', borderRadius: 8, padding: '8px 16px', fontSize: 13,
  },
  retryButton: {
    background: 'var(--ms-bs-error-btn-bg)', color: 'var(--ms-bs-error)', border: '1px solid var(--ms-bs-error-btn-border)',
    borderRadius: 6, padding: '2px 12px', fontSize: 12, cursor: 'pointer',
  },
  clock: { fontSize: 18, color: 'var(--ms-chart-1)', fontWeight: 600 },
  metricsRow: { display: 'flex', gap: 16 },
  metricCard: {
    flex: 1, background: 'var(--ms-bs-card-bg)', borderRadius: 12,
    padding: '20px 16px', textAlign: 'center', border: '1px solid var(--ms-bs-card-border)',
  },
  metricValue: { fontSize: 36, fontWeight: 700 },
  metricUnit: { fontSize: 14, marginLeft: 4, opacity: 0.6 },
  metricLabel: { fontSize: 13, color: 'var(--ms-bs-text-weak)', marginTop: 6 },
  chartsRow: { display: 'flex', gap: 16, flex: 1 },
  chartCard: {
    flex: 1, background: 'var(--ms-bs-chart-bg)', borderRadius: 12,
    padding: '16px 20px', border: '1px solid var(--ms-bs-card-border)',
  },
  chartTitle: { fontSize: 14, color: 'var(--ms-bs-text-fainter)', marginBottom: 12, fontWeight: 500 },
  bottomRow: { display: 'flex', gap: 16 },
  watermark: { textAlign: 'center', fontSize: 11, color: 'var(--ms-bs-watermark)', marginTop: 8 },
}
