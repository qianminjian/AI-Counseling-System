import type { PropsWithChildren } from 'react'
import './app.scss'

// doing/73 T2：Taro 应用壳（替换原 main.tsx 的 createRoot 入口职责）
function App({ children }: PropsWithChildren) {
  return children
}

export default App
