import { defineConfig } from 'vitest/config'
import { svelte } from '@sveltejs/vite-plugin-svelte'

// `npm run dev` serves the page with hot reload and sends the data paths to a
// local server (`./gradlew :server:run`, port 8080).
export default defineConfig({
  plugins: [svelte()],
  server: {
    proxy: {
      // changeOrigin off: the server sees the page's own Host, as it does in production,
      // which the admin API's same-origin check compares with Origin (M6.3).
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
      '/v1': { target: 'http://localhost:8080', ws: true },
    },
  },
  build: { outDir: 'dist', emptyOutDir: true },
  // app.css is read by the look's tests (M8.1); Vitest otherwise hands every CSS import over empty.
  test: { environment: 'node', css: { include: [/app\.css/] } },
})
