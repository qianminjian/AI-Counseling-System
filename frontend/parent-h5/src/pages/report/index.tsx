import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router'
import { getReport } from '../../api/index'
import type { ReportData } from '../../api/index'
import { getUser, clearAuth } from '../../utils/auth'
import type { ChildInfo } from '../../utils/auth'

export default function ReportPage() {
  const navigate = useNavigate()
  const [report, setReport] = useState<ReportData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selectedChild, setSelectedChild] = useState<ChildInfo | null>(null)

  const user = getUser()
  const children = user?.children || []

  useEffect(() => {
    // 默认选第一个孩子
    if (children.length > 0 && !selectedChild) {
      setSelectedChild(children[0])
    }
  }, [])

  useEffect(() => {
    if (!selectedChild) return
    loadReport(selectedChild.userId)
  }, [selectedChild])

  const loadReport = async (studentUserId: string) => {
    setLoading(true)
    setError('')
    try {
      const res = await getReport(studentUserId)
      setReport((res.data || res) as ReportData)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }

  const handleLogout = () => {
    clearAuth()
    navigate('/', { replace: true })
  }

  const emotionEmoji = (label: string): string => {
    const map: Record<string, string> = { '开心': '😊', '平静': '😌', '焦虑': '😰', '难过': '😢', '愤怒': '😠', '恐惧': '😨' }
    return map[label] || '🫧'
  }

  return (
    <div className="container report-page">
      {/* 头部 */}
      <div className="report-header">
        <div>
          <h1 className="page-title">情绪周报</h1>
          <p className="page-subtitle">{user?.displayName || '家长'}，您好</p>
        </div>
        <button className="logout-btn" onClick={handleLogout}>退出</button>
      </div>

      {/* 多孩子切换 */}
      {children.length > 1 && (
        <div className="child-tabs">
          {children.map(c => (
            <button
              key={c.userId}
              className={`child-tab ${selectedChild?.userId === c.userId ? 'active' : ''}`}
              onClick={() => setSelectedChild(c)}
            >
              {c.nickname}
            </button>
          ))}
        </div>
      )}

      {loading && <div className="loading-area">加载中...</div>}
      {error && <div className="error-area">{error}</div>}

      {report && !loading && (
        <div className="report-content">
          {/* 概览卡片 */}
          <div className="card summary-card">
            <div className="summary-row">
              <div className="summary-item">
                <span className="summary-value">{report.sessionCount || 0}</span>
                <span className="summary-label">本周对话</span>
              </div>
              <div className="summary-item">
                <span className="summary-value">{report.totalTurns || 0}</span>
                <span className="summary-label">对话轮次</span>
              </div>
              <div className="summary-item">
                <span className="summary-value risk-badge" data-level={report.maxRiskLevel}>
                  {report.riskLabel || '良好'}
                </span>
                <span className="summary-label">整体状态</span>
              </div>
            </div>
          </div>

          {/* 情绪分布 */}
          {report.emotionDistribution && Object.keys(report.emotionDistribution).length > 0 && (
            <div className="card">
              <h3 className="card-title">情绪分布</h3>
              <div className="emotion-list">
                {Object.entries(report.emotionDistribution).map(([label, count]) => (
                  <div key={label} className="emotion-item">
                    <span className="emotion-emoji">{emotionEmoji(label)}</span>
                    <span className="emotion-label">{label}</span>
                    <span className="emotion-count">{count} 次</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* 无数据提示 */}
          {(!report.sessionCount || report.sessionCount === 0) && (
            <div className="card empty-card">
              <p>📭 本周暂无对话记录</p>
              <p className="hint-text">孩子使用 AI 对话后，这里会显示情绪周报</p>
            </div>
          )}

          {/* 底部操作 */}
          <div className="report-actions">
            <button className="btn-secondary" onClick={() => navigate('/consent')}>
              数据授权管理
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
