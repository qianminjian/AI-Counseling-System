import { useState, useEffect } from 'react'
import type { ReactNode } from 'react'
import { Tag, List, Spin, Empty, Drawer, message } from 'antd'
import { getSessionMessages } from '../../api'
import SessionSummaryCard from './SessionSummaryCard'
import { emotionLabel } from '../../../../shared/src/emotionMeta'
import { riskColor, riskLabel } from '../../utils/riskLevel'

/**
 * 会话消息回放抽屉（FA-04，DOC-074）
 * 基线取自 StudentPanel 原内联实现（含 cancelled 守卫，防旧响应覆盖新会话）；
 * QualityPanel 复用后统一补上守卫，消除快速切换会话时的竞态。
 * extra 供调用方在抽屉头部附加操作（如 QualityPanel 的「导出 PDF」）。
 * BUG-UI-01（design/11「摘要而非原文」「默认不开放完整原始聊天全文」）：
 * 学生档案场景默认不加载/不展示逐轮原文，仅显示 AI 语义摘要；
 * 质量监控（QualityPanel）质控回放需检查对话内容，显式传 showTranscript 开启。
 */
export default function SessionMessagesDrawer({ sessionId, onClose, extra = null, showTranscript = false }: {
  sessionId: string | null
  onClose: () => void
  extra?: ReactNode
  showTranscript?: boolean
}) {
  const [messages, setMessages] = useState([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!sessionId || !showTranscript) return
    let cancelled = false
    setLoading(true)
    getSessionMessages(sessionId)
      .then((data) => { if (!cancelled) setMessages(data) })
      .catch((e) => message.error('加载对话摘要失败: ' + e.message))
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [sessionId])

  return (
    <Drawer
      title="对话摘要"
      open={!!sessionId}
      onClose={onClose}
      width={420}
      styles={{ body: { padding: '12px 16px' } }}
      extra={extra}
    >
      {/* AI 会话摘要卡片（design/11：摘要而非原文） */}
      <div style={{ marginBottom: 12 }}>
        <SessionSummaryCard sessionId={sessionId} />
      </div>

      {/* BUG-UI-01：逐轮原文仅质量监控质控场景展示（showTranscript） */}
      {showTranscript && (loading ? (
        <div className="ms-empty"><Spin /></div>
      ) : messages.length === 0 ? (
        <Empty description="暂无对话摘要记录" />
      ) : (
        <List
          size="small"
          dataSource={messages}
          renderItem={(msg) => (
            <List.Item style={{ display: 'block', padding: '8px 0' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                {/* 学生：青屿主色软底（替换 antd 默认蓝）；AI：语义绿 */}
                <Tag
                  className={msg.senderType === 'student' ? 'ms-tag-claim' : 'ms-m-0'}
                  color={msg.senderType === 'student' ? undefined : 'green'}
                >
                  {msg.senderType === 'student' ? '学生' : 'AI'}
                </Tag>
                <span style={{ fontSize: 11, color: 'var(--ms-text-muted)' }}>第 {msg.turnCount} 轮</span>
                {msg.emotionLabel && <Tag className="ms-tag-sm">{emotionLabel(msg.emotionLabel)}</Tag>}
                {msg.riskLevel > 0 && (
                  <Tag color={riskColor(msg.riskLevel)} className="ms-tag-sm">
                    {riskLabel(msg.riskLevel)}
                  </Tag>
                )}
              </div>
              <div className="ms-text-sm" style={{ color: 'var(--ms-text)', lineHeight: 1.5 }}>{msg.contentSummary}</div>
            </List.Item>
          )}
        />
      ))}
    </Drawer>
  )
}
