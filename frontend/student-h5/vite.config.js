import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import basicSsl from '@vitejs/plugin-basic-ssl'

// HTTPS 默认开启（手机局域网直连时麦克风需要安全上下文）
// 设为 VITE_HTTPS=false 可关闭（配合公网隧道使用时，HTTPS 由隧道层提供）
const useHttps = process.env.VITE_HTTPS !== 'false'

export default defineConfig({
  // basicSsl 生成自签名证书启用 HTTPS
  // 麦克风(getUserMedia)和语音识别(SpeechRecognition)要求安全上下文(HTTPS/localhost)
  // 手机通过局域网 IP 访问时必须 HTTPS 才能用语音功能
  plugins: [react(), tailwindcss(), ...(useHttps ? [basicSsl()] : [])],
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
