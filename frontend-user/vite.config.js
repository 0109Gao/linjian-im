import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5175,
    proxy: {
      '/api': {
        target: 'http://localhost:8083',
        changeOrigin: true
      },
      // WebSocket 必须单独配 ws:true。少了这一项，握手会被 vite 当成普通 HTTP
      // 请求转发，表现为「REST 一切正常、WebSocket 一直 400」。
      '/ws': {
        target: 'ws://localhost:8083',
        ws: true,
        changeOrigin: true
      }
    }
  }
})
