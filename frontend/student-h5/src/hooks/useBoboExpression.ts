/**
 * useBoboExpression —— 表情状态机接线层（TTSFX-004，design/37 §4.1/§三.1）
 *
 * 职责单一：把 emotionBus（单一情绪信号源）与纯 reducer 表情状态机接起来。
 * - emotionBus 发布情绪 → dispatch reply-emotion
 * - 交互事件（typing/thinking/risk/risk-cleared）由宿主组件经 dispatch 注入
 * - 卸载即取消订阅（无泄漏）
 */
import { useEffect, useReducer } from 'react'
import {
  boboExpressionReducer,
  initialExpressionState,
  type BoboExpressionEvent,
} from '../utils/boboExpressions'
import { emotionBus } from '../utils/emotionBus'

export function useBoboExpression() {
  const [state, dispatch] = useReducer(
    boboExpressionReducer,
    null,
    () => initialExpressionState(),
  )

  useEffect(() => {
    // 三方同源契约：表情状态机只从 emotionBus 取情绪信号
    const unsubscribe = emotionBus.subscribe((emotion) => {
      dispatch({ type: 'reply-emotion', emotion })
    })
    return unsubscribe
  }, [])

  return {
    expression: state.expression,
    locked: state.locked,
    dispatch: (event: BoboExpressionEvent) => dispatch(event),
  }
}
