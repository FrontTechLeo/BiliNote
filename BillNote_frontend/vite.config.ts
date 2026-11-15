import http from 'node:http'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import tailwindcss from '@tailwindcss/vite'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

function readAppVersion() {
  const fallbackVersion = '0.0.0'

  try {
    const tauriConfigPath = path.resolve(__dirname, 'src-tauri/tauri.conf.json')
    const tauriConfig = JSON.parse(fs.readFileSync(tauriConfigPath, 'utf-8')) as { version?: string }
    return tauriConfig.version || fallbackVersion
  }
  catch {
    return fallbackVersion
  }
}

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  // 在 Docker 环境中，父目录可能没有 .env 文件，使用当前目录
  const envDir = process.env.DOCKER_BUILD ? __dirname : path.resolve(__dirname, '../')
  const env = loadEnv(mode, envDir)

  const apiBaseUrl = env.VITE_API_BASE_URL || 'http://127.0.0.1:8483'
  const noKeepAliveAgent = new http.Agent({ keepAlive: false })
  const port = parseInt(env.VITE_FRONTEND_PORT || '3015', 10)
  const appVersion = env.VITE_APP_VERSION || process.env.VITE_APP_VERSION || readAppVersion()

  return {
    base: './',
    define: {
      __APP_VERSION__: JSON.stringify(appVersion),
    },
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    build: {
      rollupOptions: {
        output: {
          manualChunks: {
            markdown: ['react-markdown', 'react-syntax-highlighter', 'remark-gfm', 'remark-math', 'rehype-katex'],
            markmap: ['markmap-lib', 'markmap-view', 'markmap-toolbar', 'markmap-common'],
            vendor: ['react', 'react-dom', 'react-router-dom'],
          },
        },
      },
    },
    server: {
      host: '0.0.0.0',
      port: port,
      allowedHosts: true, // 允许任意域名访问
      proxy: {
        // 开发代理禁用 keep-alive：Node 19+ 默认 keepAlive=true，复用 uvicorn
        // 5s 超时后关闭的死连接会导致请求静默挂起（前端偶发"无反应"）。
        '/api': {
          target: apiBaseUrl,
          changeOrigin: true,
          agent: noKeepAliveAgent,
          rewrite: path => path.replace(/^\/api/, '/api'),
        },
        '/static': {
          target: apiBaseUrl,
          changeOrigin: true,
          agent: noKeepAliveAgent,
          rewrite: path => path.replace(/^\/static/, '/static'),
        },
      },
    },
  }
})
