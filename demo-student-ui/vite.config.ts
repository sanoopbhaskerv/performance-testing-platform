import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Dual-stack, and not by accident. Vite's default binds [::1] only, so anything reaching this
    // app from outside a browser resolves `localhost` to whichever family it prefers and fails
    // against a server that is demonstrably up: Chrome's pinned resolver got ECONNREFUSED on
    // 127.0.0.1, and Java's HttpClient — which does not fall back between families — timed out on
    // ::1. Binding '::' accepts both (Node maps IPv4 onto the IPv6 socket), so `localhost` works
    // whoever is asking.
    host: '::',
    port: 5174,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
