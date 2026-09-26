import { defineConfig } from 'vitest/config'
import { svelte } from '@sveltejs/vite-plugin-svelte'

// `npm run dev` serves the page with hot reload and sends the data paths to a
// local server (`./gradlew :server:run`, port 8080).
export default defineConfig({
  plugins: [svelte()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/v1': { target: 'http://localhost:8080', ws: true },
    },
  },
  build: { outDir: 'dist', emptyOutDir: true },
  test: { environment: 'node' },
})
