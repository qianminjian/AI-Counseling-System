import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
}

export default class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary]', error, info.componentStack)
  }

  render() {
    if (this.state.hasError) {
      return (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: '100vh', gap: 16, fontFamily: 'system-ui, sans-serif' }}>
          <h2 style={{ margin: 0, fontSize: 20 }}>页面出现异常</h2>
          <p style={{ margin: 0, color: '#666' }}>请刷新页面重试，若持续出现请联系管理员。</p>
          <button onClick={() => window.location.reload()} style={{ padding: '8px 24px', borderRadius: 6, border: '1px solid #d9d9d9', background: '#fff', cursor: 'pointer', fontSize: 14 }}>
            刷新页面
          </button>
        </div>
      )
    }
    return this.props.children
  }
}
