import { useState, useEffect } from 'react'
import { api } from '../api'

const STATUS_MAP = {
  active: { label: '进行中', color: 'text-green-600 bg-green-50' },
  completed: { label: '已完成', color: 'text-blue-600 bg-blue-50' },
  ended: { label: '已结束', color: 'text-gray-500 bg-gray-50' },
}

const RISK_EMOJI = { 0: '🟢', 1: '🟡', 2: '🟠', 3: '🔴' }

function formatDate(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  const now = new Date()
  const diff = now - d
  if (diff < 86400000 && d.getDate() === now.getDate()) {
    return `今天 ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`
  }
  if (diff < 172800000) return '昨天'
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

/**
 * 会话历史列表（嵌入 EmotionSelect 页面底部）
 */
export default function SessionHistory() {
  const [sessions, setSessions] = useState([])
  const [loading, setLoading] = useState(true)
  const [expanded, setExpanded] = useState(false)

  useEffect(() => {
    api('/sessions?limit=10')
      .then(setSessions)
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  if (loading || sessions.length === 0) return null

  const displayed = expanded ? sessions : sessions.slice(0, 3)

  return (
    <div className="w-full max-w-sm lg:max-w-md mt-8">
      <div className="flex items-center justify-between mb-3">
        <span className="text-sm text-gray-400">历史对话</span>
        {sessions.length > 3 && (
          <button
            onClick={() => setExpanded(!expanded)}
            className="text-xs text-gray-400 underline"
          >
            {expanded ? '收起' : `查看全部 ${sessions.length} 条`}
          </button>
        )}
      </div>
      <div className="space-y-2">
        {displayed.map((s) => {
          const status = STATUS_MAP[s.status] || STATUS_MAP.ended
          return (
            <div key={s.sessionId} className="flex items-center justify-between px-4 py-3 bg-white/70 rounded-xl">
              <div className="flex items-center gap-2">
                <span className="text-sm">{RISK_EMOJI[s.riskLevel] || '⚪'}</span>
                <span className="text-sm text-gray-600">{formatDate(s.startedAt)}</span>
              </div>
              <div className="flex items-center gap-2">
                {s.satisfactionRating && (
                  <span className="text-xs text-gray-400">{'⭐'.repeat(s.satisfactionRating)}</span>
                )}
                <span className={`text-xs px-2 py-0.5 rounded-full ${status.color}`}>
                  {status.label}
                </span>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
