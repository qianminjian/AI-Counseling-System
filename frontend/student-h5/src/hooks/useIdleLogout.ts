import { useState, useEffect, useRef, useCallback } from 'react'

/**
 * 无操作超时自动退出（共享 Pad 隐私保护）
 * - 登录后生效：idleMs 内无任何触摸/按键操作 → 弹"还在吗"警告卡
 * - 警告卡倒计时 countdownSec 秒，点「我还在」重新计时，归零则 onTimeout（清 token 回登录页）
 * - 警告期间不因普通触摸自动消除，必须显式点按钮（避免误触延长上一个同学的会话）
 */
export function useIdleLogout({
  enabled,
  idleMs = 5 * 60 * 1000,
  countdownSec = 60,
  onTimeout,
}: {
  enabled: boolean
  idleMs?: number
  countdownSec?: number
  onTimeout: () => void
}) {
  const [warning, setWarning] = useState(false)
  const [secondsLeft, setSecondsLeft] = useState(countdownSec)
  const lastActivityRef = useRef(Date.now())
  const warningRef = useRef(false)
  const onTimeoutRef = useRef(onTimeout)

  useEffect(() => {
    onTimeoutRef.current = onTimeout
  })

  // 点「我还在！」：关闭警告并重新计时
  const stay = useCallback(() => {
    lastActivityRef.current = Date.now()
    warningRef.current = false
    setWarning(false)
    setSecondsLeft(countdownSec)
  }, [countdownSec])

  useEffect(() => {
    if (!enabled) {
      warningRef.current = false
      setWarning(false)
      return
    }
    lastActivityRef.current = Date.now()
    setSecondsLeft(countdownSec)

    const markActivity = () => {
      // 警告期间忽略普通操作，只认「我还在」按钮
      if (!warningRef.current) lastActivityRef.current = Date.now()
    }
    const events = ['pointerdown', 'keydown', 'touchstart', 'wheel'] as const
    events.forEach((ev) => window.addEventListener(ev, markActivity, { passive: true }))

    const timer = window.setInterval(() => {
      const idle = Date.now() - lastActivityRef.current
      if (!warningRef.current) {
        if (idle >= idleMs) {
          warningRef.current = true
          setWarning(true)
          setSecondsLeft(countdownSec)
        }
      } else {
        const left = countdownSec - Math.floor((idle - idleMs) / 1000)
        if (left <= 0) {
          warningRef.current = false
          setWarning(false)
          onTimeoutRef.current()
        } else {
          setSecondsLeft(left)
        }
      }
    }, 1000)

    return () => {
      events.forEach((ev) => window.removeEventListener(ev, markActivity))
      window.clearInterval(timer)
    }
  }, [enabled, idleMs, countdownSec])

  return { warning, secondsLeft, stay }
}
