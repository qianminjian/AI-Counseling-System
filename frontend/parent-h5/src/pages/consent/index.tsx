import { useState, useEffect } from 'react'
import { View, Text, Button } from '@tarojs/components'
import { withdrawConsent } from '../../services/index'
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

  const user = getUser()
  const children = user?.children || []

  // 登录守卫：未登录跳回登录页（隐私数据页保护）
  useEffect(() => {
    if (!isAuthenticated()) {
      redirectTo('/')
    }
  }, [])

  const handleWithdraw = async () => {
    if (!selectedChild) return
    setLoading(true)
    try {
      const res = await withdrawConsent(selectedChild.userId)
      setResult((res.data || res) as WithdrawResult)
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

      {/* 撤回按钮 */}
      {!result && selectedChild && (
        <View className="card danger-card">
          {!confirming ? (
            <Button className="btn-danger" onClick={() => setConfirming(true)}>
              撤回「{selectedChild.nickname}」的授权
            </Button>
          ) : (
            <View className="confirm-area">
              <Text className="confirm-text">⚠️ 确认撤回？此操作不可逆！</Text>
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
