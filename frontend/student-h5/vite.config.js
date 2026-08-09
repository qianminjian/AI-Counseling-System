import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import basicSsl from '@vitejs/plugin-basic-ssl'
import { VitePWA } from 'vite-plugin-pwa'
import { copyFileSync, mkdirSync, rmSync, existsSync, readdirSync, readFileSync } from 'node:fs'
import { resolve, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = fileURLToPath(new URL('.', import.meta.url))

/**
 * 构建时把 ONNX Runtime WASM 从 node_modules 复制到 dist/ort/（语音唤醒 Whisper 本地推理用）。
 * - 不进 git：文件来自 npm 依赖（onnxruntime-web），本地与 CI 构建均可复现；
 * - 运行时从本机服务器 /mindsafe/ort/ 加载，不走 jsdelivr CDN（国内不稳定，学校内网可能不通外网）；
 * - 同时清理打包器输出到 dist/assets/ 的冗余 ort-*.wasm（运行时经 wasmPaths 显式指向 /ort/）。
 * 说明：asyncify 变体供 Chrome/Android 等，plain 变体供 Safari/iOS（与 transformers 默认选择一致）。
 */
function copyOnnxWasm() {
  const ORT_FILES = [
    'ort-wasm-simd-threaded.asyncify.mjs',
    'ort-wasm-simd-threaded.asyncify.wasm',
    'ort-wasm-simd-threaded.mjs',
    'ort-wasm-simd-threaded.wasm',
  ]
  return {
    name: 'copy-onnx-wasm',
    apply: 'build',
    closeBundle() {
      const src = resolve(__dirname, 'node_modules/onnxruntime-web/dist')
      const outDir = resolve(__dirname, 'dist/ort')
      mkdirSync(outDir, { recursive: true })
      for (const f of ORT_FILES) {
        copyFileSync(join(src, f), join(outDir, f))
      }
      const assetsDir = resolve(__dirname, 'dist/assets')
      if (existsSync(assetsDir)) {
        for (const f of readdirSync(assetsDir)) {
          if (f.startsWith('ort-') && f.endsWith('.wasm')) {
            rmSync(join(assetsDir, f))
          }
        }
      }
      console.log('[copy-onnx-wasm] ONNX Runtime WASM 已复制到 dist/ort/')
    },
  }
}

/**
 * Dev 模式中间件：将 /mindsafe/ort/* 请求代理到 node_modules/onnxruntime-web/dist/。
 * 生产构建由 copyOnnxWasm 插件复制静态文件，dev 模式不触发 closeBundle，
 * 需要此中间件保证语音唤醒/声纹推理在本地开发时也能加载 WASM。
 */
function serveOnnxWasmDev() {
  return {
    name: 'serve-onnx-wasm-dev',
    apply: 'serve',
    configureServer(server) {
      const src = resolve(__dirname, 'node_modules/onnxruntime-web/dist')

      // ━━ Cross-Origin Isolation 头：启用 SharedArrayBuffer（ORT WASM 多线程必需） ━━
      // 没有这个头，iOS Safari 上 SharedArrayBuffer 为 undefined，模型加载 100% 后崩溃
      server.middlewares.use((req, res, next) => {
        res.setHeader('Cross-Origin-Opener-Policy', 'same-origin')
        res.setHeader('Cross-Origin-Embedder-Policy', 'require-corp')
        res.setHeader('Cross-Origin-Resource-Policy', 'same-origin')
        next()
      })

      server.middlewares.use((req, res, next) => {
        const url = req.url || ''
        // 匹配 /mindsafe/ort/<file> 或 /ort/<file>
        const match = url.match(/\/ort\/([\w.-]+)$/)
        if (match) {
          const filePath = join(src, match[1])
          if (existsSync(filePath)) {
            const ext = match[1].endsWith('.wasm') ? 'application/wasm' : 'application/javascript'
            res.setHeader('Content-Type', ext)
            res.setHeader('Cache-Control', 'public, max-age=86400')
            res.end(readFileSync(filePath))
            return
          }
        }
        next()
      })
    },
  }
}

// HTTPS 默认开启（手机局域网直连时麦克风需要安全上下文）
// 设为 VITE_HTTPS=false 可关闭（配合公网隧道使用时，HTTPS 由隧道层提供）
const useHttps = process.env.VITE_HTTPS !== 'false'

export default defineConfig({
  base: '/mindsafe/',
  // basicSsl 生成自签名证书启用 HTTPS
  // 麦克风(getUserMedia)和语音识别(SpeechRecognition)要求安全上下文(HTTPS/localhost)
  // 手机通过局域网 IP 访问时必须 HTTPS 才能用语音功能
  plugins: [
    react(),
    tailwindcss(),
    copyOnnxWasm(),
    serveOnnxWasmDev(),
    ...(useHttps ? [basicSsl()] : []),
    // F-6 修复：学生端禁用 PWA/SW（disable:true）——SW 接管会干扰 transformers.js 加载（路由/缓存策略冲突），
    // 导致"语音引擎加载中"卡住、唤醒不可用。学生端是实时在线应用，PWA 离线价值低。
    VitePWA({
      disable: true, // BUG-F6-SW：构建不生成 sw.js，不自动注册；唤醒链路免受 SW 接管干扰
      includeAssets: ['favicon.svg', 'icons.svg'],
      manifest: {
        name: '波波小精灵',
        short_name: '波波小精灵',
        description: '想说什么，就跟波波说——AI 小学生心理辅导伙伴',
        theme_color: '#0EA5E9',
        background_color: '#ffffff',
        display: 'standalone',
        orientation: 'portrait',
        scope: '/mindsafe/',
        start_url: '/mindsafe/',
        icons: [
          // 使用相对路径：vite-plugin-pwa 会剥离绝对路径的 base 前缀，
          // 相对路径保留原样，浏览器基于 manifest URL（/mindsafe/manifest.webmanifest）解析 → /mindsafe/pwa-192.png
          { src: 'pwa-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'pwa-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        // 新 SW 立即接管，不等旧页面关闭（解决旧 SW 缓存无 COOP/COEP 头的 HTML）
        skipWaiting: true,
        clientsClaim: true,
        // 清除旧版预缓存
        cleanupOutdatedCaches: true,
        // 缓存静态资源，API 请求走网络优先
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
        // Transformers.js（Whisper 唤醒引擎）是按需动态 chunk（约 516KB，仅开启语音唤醒时加载），
        // 排除出 PWA 预缓存，避免未使用该功能的用户白白下载（首次加载后走浏览器 HTTP 缓存）。
        // ONNX wasm（dist/ort/，约 12-22MB）不在 globPatterns 扩展名内，天然不会被预缓存。
        globIgnores: ['**/transformers.web-*.js'],
        runtimeCaching: [
          {
            urlPattern: /^\/api\/.*/,
            handler: 'NetworkOnly',
          },
        ],
      },
    }),
  ],
  server: {
    port: 3000,
    https: useHttps,
    host: true,
    // 允许通过公网隧道域名访问（localtunnel 等，开发测试用）
    allowedHosts: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
