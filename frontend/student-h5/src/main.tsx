import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'
import ErrorBoundary from './components/ErrorBoundary'
import { initRemoteConfig } from './config/remote'

// CFG-002：启动时拉取远程配置（fire-and-forget，不阻塞渲染）
// 接口失败/超时时静默降级到本地默认值
initRemoteConfig()

// DOC-082：PWA SW 自动更新——跳过等待 + 主动检查 + 接管后 reload，
// 避免老用户因旧 SW precache 看到陈旧版本（vite-plugin-pwa 自动生成的 registerSW.js 只 register，未处理 waiting/controllerchange）
// 设计取舍：controllerchange → location.reload() 可能丢失当前聊天 React state（用户代价：reload 后从 localStorage sessionId 拉历史）
// 收益：发布后用户下次访问/刷新即看到新版本，无需手动 unregister 或清缓存
if ('serviceWorker' in navigator) {
  // 已存在 controller → 不是首次注册；后续代码负责接管后 reload
  let reloading = false
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (reloading) return
    reloading = true
    location.reload()
  })

  // registerSW.js 已注册 SW（vite-plugin-pwa 注入）。这里取注册、监听到更新就 reload
  navigator.serviceWorker.ready
    .then(reg => {
      // 周期主动检查更新（默认浏览器只在 navigation 时检查一次，长会话/不刷新可能 24h 不更新）
      const PERIODIC_CHECK_MS = 60_000
      setInterval(() => {
        reg.update().catch(() => {})
      }, PERIODIC_CHECK_MS)

      // 检测到 waiting SW（已装好但未激活的版本）→ 踢一脱 SKIP_WAITING（sw.js 本身也会跳，这里兑底）
      if (reg.waiting) {
        reg.waiting.postMessage({ type: 'SKIP_WAITING' })
      }

      // 后续检测到新 SW 安装完成 → controllerchange 会触发 reload
      reg.addEventListener('updatefound', () => {
        const newSw = reg.installing
        if (!newSw) return
        newSw.addEventListener('statechange', () => {
          // installed 状态表示新 SW 装好（不一定接管）+ 已存在旧 controller → reload
          if (newSw.state === 'installed' && navigator.serviceWorker.controller) {
            // 让 sw.js 自己 skipWaiting + clientsClaim，controllerchange 会触发 reload
          }
        })
      })
    })
    .catch(() => {})
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </StrictMode>,
)
