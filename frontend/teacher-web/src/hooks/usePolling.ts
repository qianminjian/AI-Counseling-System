import { useEffect, useRef } from 'react'

interface PollingOptions {
  /** 页面不可见时暂停轮询（AUD-047，默认开启） */
  pauseOnHidden?: boolean
  /** 挂载后立即执行一次（默认开启） */
  immediate?: boolean
}

/**
 * 统一轮询 Hook（F3 收敛，doing/78 §F3）
 * 收敛 Dashboard / BigScreen / TodayTodoPanel / WebSocket 心跳四处轮询实现：
 * - fnRef 模式：始终调用最新闭包，调用方无需维护依赖数组
 * - pauseOnHidden 默认开启：后台标签页不空转请求（AUD-047）
 * - 卸载自动清理定时器
 */
export function usePolling(fn: () => void, interval: number, options: PollingOptions = {}) {
  const { pauseOnHidden = true, immediate = true } = options
  const fnRef = useRef(fn)
  fnRef.current = fn

  useEffect(() => {
    if (interval <= 0) return
    if (immediate) fnRef.current()
    const timer = setInterval(() => {
      if (pauseOnHidden && document.hidden) return
      fnRef.current()
    }, interval)
    return () => clearInterval(timer)
  }, [interval, pauseOnHidden, immediate])
}
