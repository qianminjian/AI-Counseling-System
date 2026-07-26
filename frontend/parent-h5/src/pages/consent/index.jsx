import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { withdrawConsent } from '../../api/index.js'
import { getUser } from '../../utils/auth.js'

export default function ConsentPage() {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState(null)
  const [confirming, setConfirming] = useState(false)
  const [selectedChild, setSelectedChild] = useState(null)

  const user = getUser()
  const children = user?.children || []

  const handleWithdraw = async () => {
    if (!selectedChild) return
    setLoading(true)
    try {
      const res = await withdrawConsent(selectedChild.userId)
      setResult(res.data || res)
    } catch (e) {
      setResult({ error: e.message })
    } finally {
      setLoading(false)
      setConfirming(false)
    }
  }

  return (
    <div className="container consent-page">
      <div className="consent-header">
        <button className="back-btn" onClick={() => navigate('/report')}>← 返回</button>
        <h1 className="page-title">数据授权管理</h1>
      </div>

      <div className="card">
        <h3 className="card-title">授权说明</h3>
        <p className="consent-text">
          您已授权学校心理老师查看孩子的 AI 对话情绪摘要。
          撤回授权后，孩子账号将被冻结，心理画像数据将被删除。
          此操作不可逆，如需恢复请联系学校重新授权。
        </p>
      </div>

      {/* 选择孩子 */}
      {children.length > 0 && !result && (
        <div className="card">
          <h3 className="card-title">选择孩子</h3>
          <div className="child-list">
            {children.map(c => (
              <button
                key={c.userId}
                className={`child-select-btn ${selectedChild?.userId === c.userId ? 'active' : ''}`}
                onClick={() => setSelectedChild(c)}
              >
                {c.nickname}（{c.gradeCode || ''}{c.classCode || ''}）
              </button>
            ))}
          </div>
        </div>
      )}

      {/* 撤回按钮 */}
      {!result && selectedChild && (
        <div className="card danger-card">
          {!confirming ? (
            <button className="btn-danger" onClick={() => setConfirming(true)}>
              撤回「{selectedChild.nickname}」的授权
            </button>
          ) : (
            <div className="confirm-area">
              <p className="confirm-text">⚠️ 确认撤回？此操作不可逆！</p>
              <div className="confirm-btns">
                <button className="btn-danger" disabled={loading} onClick={handleWithdraw}>
                  {loading ? '处理中...' : '确认撤回'}
                </button>
                <button className="btn-secondary" onClick={() => setConfirming(false)}>取消</button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* 结果 */}
      {result && (
        <div className="card result-card">
          {result.error ? (
            <p className="error-text">{result.error}</p>
          ) : (
            <div>
              <p className="success-text">✅ {result.message || '已撤回授权'}</p>
              <button className="btn-secondary" onClick={() => navigate('/report')}>返回周报</button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
