import { useState } from 'react'
import { withdrawConsent } from '../../api/index.js'
import { clearToken } from '../../utils/auth.js'

export default function ConsentPage() {
  const [loading, setLoading] = useState(false)
  const [withdrawn, setWithdrawn] = useState(false)

  const handleWithdraw = async () => {
    const confirmed = window.confirm(
      '撤回后将冻结孩子账号并删除心理画像数据，此操作不可逆。\n\n确定要撤回吗？'
    )
    if (!confirmed) return

    setLoading(true)
    try {
      await withdrawConsent()
      setWithdrawn(true)
      clearToken()
    } catch (e) {
      alert(e.message || '操作失败')
    } finally {
      setLoading(false)
    }
  }

  if (withdrawn) {
    return (
      <div className="container consent-page">
        <div className="result-area">
          <span className="result-emoji">✅</span>
          <h1 className="page-title">已撤回同意</h1>
          <p className="result-desc">孩子的账号已冻结，心理画像数据已删除。</p>
          <p className="result-desc">如需恢复使用，请联系学校心理老师重新生成授权链接。</p>
        </div>
      </div>
    )
  }

  return (
    <div className="container consent-page">
      <div className="page-header">
        <h1 className="page-title">数据授权管理</h1>
        <p className="page-subtitle">管理您对孩子数据使用的授权</p>
      </div>

      <div className="card status-card">
        <div className="status-row">
          <span className="status-label">当前状态</span>
          <span className="status-value active">✅ 已授权</span>
        </div>
        <div className="status-row">
          <span className="status-label">授权范围</span>
          <span className="status-value">AI 心理辅导对话 + 情绪统计</span>
        </div>
        <div className="status-row">
          <span className="status-label">数据保护</span>
          <span className="status-value">对话原文仅 AI 可见，教师/家长只看统计</span>
        </div>
      </div>

      <div className="card warning-card">
        <h2 className="warning-title">⚠️ 撤回同意将：</h2>
        <div className="warning-list">
          <p className="warning-item">· 冻结孩子的账号（无法继续使用）</p>
          <p className="warning-item">· 删除已生成的心理画像数据</p>
          <p className="warning-item">· 保留风险预警记录（法律要求）</p>
          <p className="warning-item">· 此操作不可逆</p>
        </div>
      </div>

      <button className="btn-danger" disabled={loading} onClick={handleWithdraw}>
        {loading ? '处理中...' : '撤回同意'}
      </button>

      <p className="tip-text">如需恢复，请联系学校重新生成授权链接</p>
      <p className="tip-text">依据《个人信息保护法》第 47 条，您有权撤回同意</p>
    </div>
  )
}
