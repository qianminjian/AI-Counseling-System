import { useState, useEffect } from 'react'

const EMOTION_LABELS = {
  happy: '😊 开心', sad: '😢 难过', angry: '😠 生气',
  scared: '😨 害怕', nervous: '😰 紧张', calm: '😌 平静',
}

const RISK_COLORS = { 3: '#ff4d4f', 2: '#fa8c16', 1: '#faad14', 0: '#52c41a' }

/**
 * 家长情绪周报页面（只读，token 鉴权）
 * URL: /parent?token=xxx
 */
export default function ParentReport() {
  const [report, setReport] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const token = params.get('token')
    if (!token) {
      setError('缺少访问凭证')
      setLoading(false)
      return
    }

    fetch('/api/v1/parent/report', {
      headers: { Authorization: `Bearer ${token}` },
    })
      .then(r => r.json())
      .then(json => {
        if (json.success) {
          setReport(json.data)
        } else {
          setError(json.message || '加载失败')
        }
      })
      .catch(() => setError('网络错误'))
      .finally(() => setLoading(false))
  }, [])

  if (loading) {
    return (
      <div style={styles.container}>
        <div style={styles.card}><p style={{ textAlign: 'center', color: '#999' }}>加载中...</p></div>
      </div>
    )
  }

  if (error) {
    return (
      <div style={styles.container}>
        <div style={styles.card}>
          <p style={{ textAlign: 'center', color: '#ff4d4f' }}>{error}</p>
          <p style={{ textAlign: 'center', color: '#999', fontSize: 13 }}>请联系老师重新分享链接</p>
        </div>
      </div>
    )
  }

  const emotions = Object.entries(report.emotionDistribution || {})
    .sort((a, b) => b[1] - a[1])

  return (
    <div style={styles.container}>
      <div style={styles.header}>
        <h2 style={{ margin: 0, fontSize: 20 }}>🌈 情绪周报</h2>
        <p style={{ margin: '4px 0 0', fontSize: 13, color: '#666' }}>
          {report.studentNickname} · {report.gradeCode}{report.classCode}
        </p>
      </div>

      <div style={styles.card}>
        <div style={styles.statRow}>
          <div style={styles.statItem}>
            <span style={styles.statNum}>{report.sessionCount}</span>
            <span style={styles.statLabel}>本周对话次数</span>
          </div>
          <div style={styles.statItem}>
            <span style={styles.statNum}>{report.totalTurns}</span>
            <span style={styles.statLabel}>总对话轮次</span>
          </div>
          <div style={styles.statItem}>
            <span style={{ ...styles.statNum, color: RISK_COLORS[report.maxRiskLevel] }}>
              {report.riskLabel}
            </span>
            <span style={styles.statLabel}>整体状态</span>
          </div>
        </div>
      </div>

      <div style={styles.card}>
        <h3 style={styles.cardTitle}>情绪分布</h3>
        {emotions.length === 0 ? (
          <p style={{ color: '#999', fontSize: 13 }}>本周暂无情绪记录</p>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {emotions.map(([key, count]) => (
              <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ width: 80, fontSize: 13 }}>{EMOTION_LABELS[key] || key}</span>
                <div style={{ flex: 1, height: 16, background: '#f0f0f0', borderRadius: 8, overflow: 'hidden' }}>
                  <div style={{
                    width: `${Math.min(count / emotions[0][1] * 100, 100)}%`,
                    height: '100%',
                    background: 'linear-gradient(90deg, #74b9ff, #0984e3)',
                    borderRadius: 8,
                  }} />
                </div>
                <span style={{ width: 24, fontSize: 12, color: '#666', textAlign: 'right' }}>{count}</span>
              </div>
            ))}
          </div>
        )}
      </div>

      <div style={styles.card}>
        <h3 style={styles.cardTitle}>温馨提示</h3>
        <p style={{ fontSize: 13, color: '#555', lineHeight: 1.6, margin: 0 }}>
          以上数据来自孩子与 AI 心理小助手的互动摘要。如需了解更多，
          建议与学校心理老师沟通。每个孩子都有情绪波动的时候，
          您的理解和陪伴是最好的支持。💛
        </p>
      </div>

      <p style={{ textAlign: 'center', fontSize: 11, color: '#bbb', marginTop: 16 }}>
        报告生成时间：{new Date(report.generatedAt).toLocaleString('zh-CN')}
      </p>
    </div>
  )
}

const styles = {
  container: {
    minHeight: '100vh',
    background: 'linear-gradient(180deg, #f8f9ff 0%, #eef2f7 100%)',
    padding: '24px 16px',
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
  },
  header: { textAlign: 'center', marginBottom: 20 },
  card: {
    background: '#fff',
    borderRadius: 12,
    padding: '16px 20px',
    marginBottom: 12,
    boxShadow: '0 2px 8px rgba(0,0,0,0.04)',
  },
  cardTitle: { margin: '0 0 12px', fontSize: 15, fontWeight: 600 },
  statRow: { display: 'flex', justifyContent: 'space-around' },
  statItem: { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 },
  statNum: { fontSize: 22, fontWeight: 700, color: '#2d3436' },
  statLabel: { fontSize: 12, color: '#999' },
}
