import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import fs from 'fs'
import path from 'path'

const certsDir = path.resolve(__dirname, '..', '..', 'context', 'certs')
const hasLocalCerts = fs.existsSync(path.join(certsDir, 'key.pem'))

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
  ],
  // Kept under _old/ after the 2026-10 cutover and served at /legacy/ (api/app.py _spa_dirs).
  base: '/legacy/',
  server: {
    host: '0.0.0.0', // Listen on all network interfaces
    port: 5432,
    strictPort: true,
    https: hasLocalCerts
      ? { key: fs.readFileSync(path.join(certsDir, 'key.pem')), cert: fs.readFileSync(path.join(certsDir, 'cert.pem')) }
      : undefined,
    proxy: {
      '/api': {
        target: 'http://localhost:8765',
        ws: true,
      },
    },
  },
})
