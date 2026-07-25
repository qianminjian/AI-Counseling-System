import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import basicSsl from '@vitejs/plugin-basic-ssl'

export default defineConfig({
  // basicSsl 生成自签名证书启用 HTTPS
  // 麦克风(getUserMedia)和语音识别(SpeechRecognition)要求安全上下文(HTTPS/localhost)
  // 手机通过局域网 IP 访问时必须 HTTPS 才能用语音功能
  plugins: [react(), tailwindcss(), basicSsl()],
  server: {
    port: 3000,
    https: true,
    host: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
