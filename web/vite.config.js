import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/ui/',
  plugins: [vue()],
  build: {
    outDir: '../src/main/resources/static/ui',
    emptyOutDir: true
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:7080'
    }
  }
})
