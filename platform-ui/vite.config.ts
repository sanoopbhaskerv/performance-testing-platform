/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/runs': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      }
    }
  },
  test: {
    // jsdom rather than happy-dom: component tests here assert on accessible roles and labels
    // (CLAUDE.md §8 requires them), and jsdom's accessibility tree is the more faithful of the two.
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    // Explicit imports from 'vitest' in every test file rather than globals, so the app's tsconfig
    // needs no ambient test types and a stray `expect` left in production code stays a compile error.
    globals: false,
    include: ['src/**/*.test.{ts,tsx}'],
  }
})
