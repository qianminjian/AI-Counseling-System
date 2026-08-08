import { useState, useEffect } from 'react'
import { View, Text, Button } from '@tarojs/components'
import { getReport } from '../../services/index'
import type { ReportData } from '../../services/index'
import { getUser, clearAuth, isAuthenticated } from '../../utils/auth'
import type { ChildInfo } from '../../utils/auth'
import { redirectTo, navigateTo } from '../../utils/nav'
import { emotionLabel as toZhLabel, emotionEmoji as toEmoji } from '../../../../shared/src/emotionMeta'

export default function ReportPage() {
  const [report, setReport] = useState<ReportData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selectedChild, setSelectedChild] = useState<ChildInfo | null>(null)

  const user = getUser()
  const children = user?.children || []

  // 登录守卫：未登录跳回登录页（隐私数据页保护）
  useEffect(() => {
    if (!isAuthenticated()) {
      redirectTo('/')
    }
  }, [])

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
      // DOC-073 F1（doing/77 §24）：请求器统一 success 契约返回完整信封，删防御双处理
      setReport(res.data as ReportData)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }

  const handleLogout = () => {
    clearAuth()
    redirectTo('/')
  }

  // F4：emoji/label 单一源 shared emotionMeta（周报键兼容英文码值与中文标签两种格式，未知兜底🫧）
  const emotionEmoji = (label: string): string => toEmoji(label) || '🫧'

  return (
    <View className="container report-page">
      {/* 头部 */}
      <View className="report-header">
        <View>
          <Text className="page-title">情绪周报</Text>
          <Text className="page-subtitle">{user?.displayName || '家长'}，您好</Text>
        </View>
        <Button className="logout-btn" onClick={handleLogout}>退出</Button>
      </View>

      {/* 多孩子切换 */}
      {children.length > 1 && (
        <View className="child-tabs">
          {children.map(c => (
            <Button
              key={c.userId}
              className={`child-tab ${selectedChild?.userId === c.userId ? 'active' : ''}`}
              onClick={() => setSelectedChild(c)}
            >
              {c.nickname}
            </Button>
          ))}
        </View>
      )}

      {loading && <View className="loading-area">加载中...</View>}
      {error && <View className="error-area">{error}</View>}

      {report && !loading && (
        <View className="report-content">
          {/* 概览卡片 */}
          <View className="card summary-card">
            <View className="summary-row">
              <View className="summary-item">
                <Text className="summary-value">{report.sessionCount || 0}</Text>
                <Text className="summary-label">本周对话</Text>
              </View>
              <View className="summary-item">
                <Text className="summary-value">{report.totalTurns || 0}</Text>
                <Text className="summary-label">对话轮次</Text>
              </View>
              <View className="summary-item">
                <Text className="summary-value risk-badge" data-level={report.maxRiskLevel}>
                  {report.riskLabel || '良好'}
                </Text>
                <Text className="summary-label">整体状态</Text>
              </View>
            </View>
          </View>

          {/* 情绪分布 */}
          {report.emotionDistribution && Object.keys(report.emotionDistribution).length > 0 && (
            <View className="card">
              <Text className="card-title">情绪分布</Text>
              <View className="emotion-list">
                {Object.entries(report.emotionDistribution).map(([label, count]) => (
                  <View key={label} className="emotion-item">
                    <Text className="emotion-emoji">{emotionEmoji(label)}</Text>
                    <Text className="emotion-label">{toZhLabel(label)}</Text>
                    <Text className="emotion-count">{count} 次</Text>
                  </View>
                ))}
              </View>
            </View>
          )}

          {/* 无数据提示 */}
          {(!report.sessionCount || report.sessionCount === 0) && (
            <View className="card empty-card">
              <Text>📭 本周暂无对话记录</Text>
              <Text className="hint-text">孩子使用 AI 对话后，这里会显示情绪周报</Text>
            </View>
          )}

          {/* 底部操作 */}
          <View className="report-actions">
            <Button className="btn-secondary" onClick={() => navigateTo('/consent')}>
              数据授权管理
            </Button>
          </View>
        </View>
      )}
    </View>
  )
}
