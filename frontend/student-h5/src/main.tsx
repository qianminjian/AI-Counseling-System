import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'
import ErrorBoundary from './components/ErrorBoundary'
import { initRemoteConfig } from './config/remote'

// CFG-002：启动时拉取远程配置（fire-and-forget，不阻塞渲染）
// 接口失败/超时时静默降级到本地默认值
initRemoteConfig()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </StrictMode>,
)
