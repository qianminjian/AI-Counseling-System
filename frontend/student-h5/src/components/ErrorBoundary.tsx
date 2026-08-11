import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'

interface Props {
  children: ReactNode
  fallback?: ReactNode
}

interface State {
  hasError: boolean
  error: Error | null
}

/**
 * 全局错误边界：捕获子组件渲染异常，展示降级 UI 而非白屏。
 * 心理危机场景下白屏 = 用户被抛弃，必须有兜底。
 */
export default class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false, error: null }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary]', error, info.componentStack)
  }

  private handleRetry = () => {
    this.setState({ hasError: false, error: null })
  }

  render() {
    if (this.state.hasError) {
      if (this.props.fallback) return this.props.fallback
      return (
        <div style={{
          display: 'flex', flexDirection: 'column', alignItems: 'center',
          justifyContent: 'center', minHeight: '60vh', gap: 16, padding: 24,
          fontFamily: 'system-ui, sans-serif', color: 'var(--text-faint)',
        }}>
          <div style={{ fontSize: 48 }}>🫂</div>
          <h2 style={{ margin: 0, fontSize: 18, fontWeight: 600, color: 'var(--text-strong)' }}>页面遇到了一点问题</h2>
          <p style={{ margin: 0, fontSize: 14, color: 'var(--text-faint)', textAlign: 'center' }}>
            别担心，你的对话记录不会丢失。<br />试试刷新页面，或者点击下面的按钮重试。
          </p>
          <div style={{ display: 'flex', gap: 12, marginTop: 8 }}>
            <button
              onClick={this.handleRetry}
              style={{
                padding: '10px 24px', borderRadius: 8, border: 'none',
                background: 'var(--primary)', color: '#fff', fontSize: 14, cursor: 'pointer',
              }}
            >
              重试
            </button>
            <button
              onClick={() => window.location.reload()}
              style={{
                padding: '10px 24px', borderRadius: 8,
                border: '1px solid var(--border)', background: 'var(--card-bg)', fontSize: 14, cursor: 'pointer',
              }}
            >
              刷新页面
            </button>
          </div>
        </div>
      )
    }
    return this.props.children
  }
}
