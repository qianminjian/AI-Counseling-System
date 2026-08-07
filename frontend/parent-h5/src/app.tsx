import type { PropsWithChildren } from 'react'
import ErrorBoundary from './components/ErrorBoundary'
import './app.scss'

// doing/73 T2/T3：Taro 应用壳（替换原 main.tsx 的 createRoot 入口职责），全局 ErrorBoundary 降级
function App({ children }: PropsWithChildren) {
  return <ErrorBoundary>{children}</ErrorBoundary>
}

export default App
