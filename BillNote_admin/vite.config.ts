import http from 'node:http'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// 开发代理禁用 keep-alive：Node 19+ 全局 agent 默认 keepAlive=true，而后端
// uvicorn 的 keep-alive 超时只有 5s，代理复用已被后端关闭的死连接时，
// 请求会静默挂起（表现为前端偶发"无反应"，直到 TCP 重传超时）。
const noKeepAliveAgent = new http.Agent({ keepAlive: false })

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '..')
  const backend = env.VITE_API_BASE_URL || 'http://127.0.0.1:8483'
  return {
    // The same SPA is served by nginx for both /admin/ and the standalone /mobile entry.
    base: '/',
    plugins: [react()],
    build: { outDir: 'build' },
    server: {
      host: '0.0.0.0',
      port: Number(env.VITE_ADMIN_PORT || 3016),
      proxy: {
        '/api': { target: backend, changeOrigin: true, agent: noKeepAliveAgent },
        '/static': { target: backend, changeOrigin: true, agent: noKeepAliveAgent },
      },
    },
  }
})
