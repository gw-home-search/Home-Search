import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
export default defineConfig({ plugins: [react()], server: { port: 5174, proxy: { '/api': process.env.ADMIN_SERVICE_PROXY_TARGET ?? 'http://localhost:8081' } }, test: { environment: 'jsdom' } });
