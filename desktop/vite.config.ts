import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// PC 客户端固定走 Tauri 注入的后端端口（127.0.0.1），dev 阶段可在
// desktop/.env.local 里放 VITE_API_BASE_URL=http://127.0.0.1:8483 调试
export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  server: {
    port: 5183,
    strictPort: true,
  },
  build: {
    target: 'es2022',
    outDir: 'dist',
  },
})
