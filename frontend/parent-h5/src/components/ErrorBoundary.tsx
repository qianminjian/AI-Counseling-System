import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'
import { View, Text, Button } from '@tarojs/components'
import { locationRedirect } from '../platform/redirect'

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
        <View style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: '60vh', gap: 12, padding: 24, fontFamily: 'system-ui, sans-serif', color: '#555' }}>
          <Text style={{ margin: 0, fontSize: 18 }}>页面出现异常</Text>
          <Text style={{ margin: 0, fontSize: 14, color: '#888' }}>请刷新页面重试</Text>
          <Button onClick={() => locationRedirect('')} style={{ padding: '10px 24px', borderRadius: 8, border: 'none', background: '#4f8ef7', color: '#fff', fontSize: 14, cursor: 'pointer' }}>
            刷新页面
          </Button>
        </View>
      )
    }
    return this.props.children
  }
}
