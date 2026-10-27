import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/identity/admin/',
  plugins: [vue()],
  build: {
    outDir: '../target/generated-resources/static/identity/admin',
    emptyOutDir: true,
    sourcemap: false,
    manifest: true,
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.ts'],
    clearMocks: true,
  },
})
