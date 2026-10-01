import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    // 本地开发端口（后端本地 profile 默认 8080，避免冲突）
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080'
    }
  }
})
