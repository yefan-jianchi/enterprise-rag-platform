import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 配置：开发端口 5173，/api 代理到 Java 后端 8080
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // @ 指向 src 目录，避免深层相对路径
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    host: '127.0.0.1',
    open: false,
    proxy: {
      // 所有 /api 请求转发到 Java 后端，前端无需处理跨域
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: false
      }
    }
  },
  build: {
    outDir: 'dist',
    // 关闭生产环境 sourcemap，减小产物体积
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        // 手动分包：把体积较大的第三方库单独拆出来，避免单个 chunk 过大
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          elementPlus: ['element-plus', '@element-plus/icons-vue']
        }
      }
    }
  }
})
