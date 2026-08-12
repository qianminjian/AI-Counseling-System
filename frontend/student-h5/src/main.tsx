import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'
import ErrorBoundary from './components/ErrorBoundary'
import { initRemoteConfig } from './config/remote'

// CFG-002：启动时拉取远程配置（fire-and-forget，不阻塞渲染）
// 接口失败/超时时静默降级到本地默认值
initRemoteConfig()

// FE-002（doing/95）：SW 更新逻辑整块删除——F-6 已禁用 vite-plugin-pwa（vite.config.js disable:true，
// 构建不生成 sw.js），本块 30 行逻辑 + 60s setInterval 永不触发（死代码 + 模块级未清理定时器）；
// 若未来恢复 PWA 需基于 vite-plugin-pwa registerSW.js 语义重建

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </StrictMode>,
)
