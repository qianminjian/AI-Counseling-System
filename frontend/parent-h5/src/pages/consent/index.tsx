import { useState, useEffect } from 'react'
import { View, Text, Button } from '@tarojs/components'
import { withdrawConsent, getConsentStatus } from '../../services/index'
import type { ConsentStatusData } from '../../services/index'
import { getUser, isAuthenticated } from '../../utils/auth'
import type { ChildInfo } from '../../utils/auth'
import { redirectTo, navigateTo } from '../../utils/nav'

interface WithdrawResult {
  error?: string
  message?: string
}

export default function ConsentPage() {
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<WithdrawResult | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [selectedChild, setSelectedChild] = useState<ChildInfo | null>(null)
  const [consentStatus, setConsentStatus] = useState<ConsentStatusData | null>(null)
  const [statusLoading, setStatusLoading] = useState(false)

  const user = getUser()
  const children = user?.children || []

  // 登录守卫：未登录跳回登录页（隐私数据页保护）
  useEffect(() => {
    if (!isAuthenticated()) {
      redirectTo('/')
    }
  }, [])

  // BUG-P-P04-01：选择孩子后加载授权状态（已授权/已撤回 + 时间 + 版本）
  useEffect(() => {
    if (!selectedChild) {
      setConsentStatus(null)
      return
    }
    setStatusLoading(true)
    setConsentStatus(null)
    getConsentStatus(selectedChild.userId)
      .then(s => setConsentStatus(s))
      .catch(() => setConsentStatus(null)) // 状态查询失败不阻塞撤回流程
      .finally(() => setStatusLoading(false))
  }, [selectedChild])

  const withdrawn = consentStatus?.status === 'withdrawn'

  const formatTime = (iso?: string | null) => {
    if (!iso) return ''
    const d = new Date(iso)
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  }

  const handleWithdraw = async () => {
    if (!selectedChild) return
    setLoading(true)
    try {
      const res = await withdrawConsent(selectedChild.userId)
      // F-04：request 已解包 data，直接使用返回值
      setResult(res)
    } catch (err) {
      setResult({ error: err instanceof Error ? err.message : '操作失败' })
    } finally {
      setLoading(false)
      setConfirming(false)
    }
  }

  return (
    <View className="container consent-page">
      <View className="consent-header">
        <Button className="back-btn" onClick={() => navigateTo('/report')}>← 返回</Button>
        <Text className="page-title">数据授权管理</Text>
      </View>

      <View className="card">
        <Text className="card-title">授权说明</Text>
        <Text className="consent-text">
          您已授权学校心理老师查看孩子的 AI 对话情绪摘要。
          撤回授权后，孩子账号将被冻结，心理画像数据将被删除。
          此操作不可逆，如需恢复请联系学校重新授权。
        </Text>
      </View>

      {/* 选择孩子 */}
      {children.length > 0 && !result && (
        <View className="card">
          <Text className="card-title">选择孩子</Text>
          <View className="child-list">
            {children.map(c => (
              <Button
                key={c.userId}
                className={`child-select-btn ${selectedChild?.userId === c.userId ? 'active' : ''}`}
                onClick={() => setSelectedChild(c)}
              >
                {c.nickname}（{c.gradeCode || ''}{c.classCode || ''}）
              </Button>
            ))}
          </View>
        </View>
      )}

      {/* 授权状态（BUG-P-P04-01：状态 + 时间 + 版本） */}
      {!result && selectedChild && (
        <View className="card">
          <Text className="card-title">授权状态</Text>
          {statusLoading ? (
            <Text className="hint-text">加载中...</Text>
          ) : consentStatus ? (
            <View className="consent-status">
              <View className="status-row">
                <Text className="status-label">当前状态</Text>
                <Text className={`status-badge ${withdrawn ? 'withdrawn' : 'active'}`}>
                  {withdrawn ? '已撤回' : '已授权'}
                </Text>
              </View>
              <View className="status-row">
                <Text className="status-label">授权时间</Text>
                <Text className="status-value">{formatTime(consentStatus.consentedAt) || '—'}</Text>
              </View>
              <View className="status-row">
                <Text className="status-label">政策版本</Text>
                <Text className="status-value">{consentStatus.consentVersion || '—'}</Text>
              </View>
              {withdrawn && consentStatus.withdrawnAt && (
                <View className="status-row">
                  <Text className="status-label">撤回时间</Text>
                  <Text className="status-value">{formatTime(consentStatus.withdrawnAt)}</Text>
                </View>
              )}
            </View>
          ) : (
            <Text className="hint-text">状态查询失败，请稍后重试</Text>
          )}
        </View>
      )}

      {/* 撤回按钮（已撤回状态禁用） */}
      {!result && selectedChild && !withdrawn && (
        <View className="card danger-card">
          {!confirming ? (
            <Button className="btn-danger" onClick={() => setConfirming(true)}>
              撤回「{selectedChild.nickname}」的授权
            </Button>
          ) : (
            <View className="confirm-area">
              {/* BUG-P-P05-01：二次确认文案补齐具体警示（冻结账号/删除画像/不可逆） */}
              <Text className="confirm-text">⚠️ 确认撤回？此操作不可逆！</Text>
              <Text className="confirm-detail">撤回后：孩子账号将被冻结，心理画像数据将被删除。如需恢复请联系学校重新授权。</Text>
              <View className="confirm-btns">
                <Button className="btn-danger" disabled={loading} onClick={handleWithdraw}>
                  {loading ? '处理中...' : '确认撤回'}
                </Button>
                <Button className="btn-secondary" onClick={() => setConfirming(false)}>取消</Button>
              </View>
            </View>
          )}
        </View>
      )}

      {/* 已撤回提示（BUG-P-P04-01：撤回后展示终态提示，按钮不可用） */}
      {!result && selectedChild && withdrawn && (
        <View className="card danger-card">
          <Text className="confirm-text">⚠️ 已撤回授权：孩子账号已冻结，心理画像已删除</Text>
          <Text className="hint-text">此操作不可逆，如需恢复请联系学校重新授权</Text>
        </View>
      )}

      {/* 结果 */}
      {result && (
        <View className="card result-card">
          {result.error ? (
            <Text className="error-text">{result.error}</Text>
          ) : (
            <View>
              <Text className="success-text">✅ {result.message || '已撤回授权'}</Text>
              <Button className="btn-secondary" onClick={() => navigateTo('/report')}>返回周报</Button>
            </View>
          )}
        </View>
      )}
    </View>
  )
}
