import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'
import ErrorBoundary from './components/ErrorBoundary'
import { initRemoteConfig } from './config/remote'

// CFG-002：启动时拉取远程配置（fire-and-forget，不阻塞渲染）
// 接口失败/超时时静默降级到本地默认值
initRemoteConfig()

// FE-002（doing/95）+ F-02（doing/96）：SW 更新逻辑整块删除——PWA 已停用（F-6 禁用后
// vite-plugin-pwa 配置块已删除，构建不生成 sw.js），本块 30 行逻辑 + 60s setInterval 永不触发（死代码）；
// 若未来恢复 PWA 需基于 vite-plugin-pwa registerSW.js 语义重建

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </StrictMode>,
)
