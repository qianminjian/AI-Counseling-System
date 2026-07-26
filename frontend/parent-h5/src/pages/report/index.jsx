import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { getReport } from '../../api/index.js'

const RISK_MAP = {
  0: { label: '良好', color: '#27ae60', emoji: '🟢' },
  1: { label: '关注', color: '#f39c12', emoji: '🟡' },
  2: { label: '预警', color: '#e67e22', emoji: '🟠' },
  3: { label: '高危', color: '#e74c3c', emoji: '🔴' }
}

const EMOTION_EMOJI = {
  happy: '😊', calm: '😐', sad: '😢', anxious: '😰', angry: '😠'
}

export default function ReportPage() {
  const navigate = useNavigate()
  const [report, setReport] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => { loadReport() }, [])

  const loadReport = async () => {
    setLoading(true)
    setError('')
    try {
      const res = await getReport()
      setReport(res.data || res)
    } catch (e) {
      setError(e.message || '加载失败')
    } finally {
      setLoading(false)
    }
  }

  if (loading) {
    return <div className="container report-page"><div className="loading-area">加载中...</div></div>
  }

  if (error) {
    return (
      <div className="container report-page">
        <div className="card">
          <p className="error-text">{error}</p>
          <button className="btn-primary" onClick={loadReport}>重试</button>
        </div>
      </div>
    )
  }

  const risk = RISK_MAP[report?.riskLevel ?? 0] || RISK_MAP[0]
  const emotions = report?.emotionDistribution || []
  const totalSessions = report?.totalSessions ?? 0
  const totalMessages = report?.totalMessages ?? 0
  const suggestion = report?.suggestion || '暂无建议'
  const studentName = report?.studentName || '孩子'
  const period = report?.period || '近 7 天'

  return (
    <div className="container report-page">
      <div className="report-header">
        <h1 className="page-title">🌈 {studentName}的情绪周报</h1>
        <p className="page-subtitle">{period}</p>
      </div>

      {/* 情绪分布 */}
      <div className="card">
        <h2 className="card-title">情绪分布</h2>
        {emotions.length > 0 ? (
          <div className="emotion-list">
            {emotions.map(item => (
              <div className="emotion-row" key={item.emotion}>
                <span className="emotion-emoji">{EMOTION_EMOJI[item.emotion] || '😶'}</span>
                <span className="emotion-name">{item.label || item.emotion}</span>
                <div className="emotion-bar-bg">
                  <div className="emotion-bar" style={{ width: `${item.percentage || 0}%` }} />
                </div>
                <span className="emotion-pct">{item.percentage || 0}%</span>
              </div>
            ))}
          </div>
        ) : (
          <p className="empty-text">本周暂无对话记录</p>
        )}
      </div>

      {/* 统计 */}
      <div className="card stats-card">
        <div className="stat-item">
          <span className="stat-value">{totalSessions}</span>
          <span className="stat-label">本周对话</span>
        </div>
        <div className="stat-divider" />
        <div className="stat-item">
          <span className="stat-value">{totalMessages}</span>
          <span className="stat-label">对话轮次</span>
        </div>
        <div className="stat-divider" />
        <div className="stat-item">
          <span className="stat-value" style={{ color: risk.color }}>{risk.emoji} {risk.label}</span>
          <span className="stat-label">风险状态</span>
        </div>
      </div>

      {/* AI 建议 */}
      <div className="card suggestion-card">
        <h2 className="card-title">💡 AI 建议</h2>
        <p className="suggestion-text">{suggestion}</p>
      </div>

      <button className="btn-link" onClick={() => navigate('/consent')}>
        数据授权管理
      </button>

      <p className="tip-text">本报告仅展示情绪趋势统计，不包含对话原文</p>
    </div>
  )
}
