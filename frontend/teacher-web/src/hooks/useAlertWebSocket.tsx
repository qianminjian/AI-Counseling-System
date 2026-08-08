import { useEffect, useRef, useCallback } from 'react'
import { notification, Button, message } from 'antd'
import { getToken, takeoverSession } from '../api'
import { usePolling } from './usePolling'

/**
 * 教师端预警 WebSocket 实时推送 Hook
 * 连接 ws://host/ws/alerts
 * 认证：JWT 通过 subprotocol 携带（['alerts.v1', 'auth.<jwt>']），不进 query string，
 * 避免入 nginx access log / 浏览器历史（P1-FE-4）
 * 收到 risk_alert 消息时弹出 antd notification + 触发回调
 */
export function useAlertWebSocket({ onAlert, enabled = true }) {
  const wsRef = useRef(null)
  const reconnectTimer = useRef(null)

  const connect = useCallback(() => {
    const token = getToken()
    if (!token) return

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const url = `${protocol}//${window.location.host}/ws/alerts`
    // alerts.v1 与服务端子协议协商；auth.<jwt> 由后端握手拦截器提取认证
    const ws = new WebSocket(url, ['alerts.v1', `auth.${token}`])
    wsRef.current = ws

    ws.onmessage = (event) => {
      if (event.data === 'pong') return
      try {
        const data = JSON.parse(event.data)
        if (data.type === 'risk_alert') {
          const levelColor = { 3: 'var(--ms-danger)', 2: 'var(--ms-warning)', 1: 'var(--ms-warning)' }
          const isRed = data.riskLevel >= 3

          notification.open({
            message: isRed ? `🚨 ${data.title}` : data.title,
            description: data.body,
            placement: 'topRight',
            duration: isRed ? 0 : 8, // 红色不自动关闭
            style: { borderLeft: `4px solid ${levelColor[data.riskLevel] || 'var(--ms-primary)'}` },
            btn: isRed && data.sessionId ? (
              <Button
                type="primary"
                danger
                size="small"
                onClick={async () => {
                  try {
                    await takeoverSession(data.sessionId)
                    message.success('已接管，请前往线下干预')
                    notification.destroy()
                  } catch (e) {
                    message.error('接管失败: ' + e.message)
                  }
                }}
              >
                立即接管
              </Button>
            ) : undefined,
          })
          onAlert?.(data)
        }
      } catch { /* ignore non-JSON */ }
    }

    ws.onclose = () => {
      // 自动重连（5s 后）
      reconnectTimer.current = setTimeout(connect, 5000)
    }

    ws.onerror = () => ws.close()
  }, [onAlert])

  // 心跳保活（30s）：不随页面隐藏暂停（WS 需持续保活），immediate=false 等首个周期
  // readyState 守卫：连接关闭/重连中不发送（F3 收敛自 onopen 内嵌 interval）
  usePolling(() => {
    const ws = wsRef.current
    if (ws && ws.readyState === WebSocket.OPEN) ws.send('ping')
  }, 30000, { pauseOnHidden: false, immediate: false })

  useEffect(() => {
    if (!enabled) return
    connect()
    return () => {
      clearTimeout(reconnectTimer.current)
      wsRef.current?.close()
    }
  }, [enabled, connect])
}
