import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

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
        '/api': { target: backend, changeOrigin: true },
        '/static': { target: backend, changeOrigin: true },
      },
    },
  }
})
