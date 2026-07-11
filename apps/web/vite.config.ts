import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          const reactModuleNames = ['/react/', 'react-dom', '/scheduler/'];
          if (reactModuleNames.some((moduleName) => id.includes(moduleName))) {
            return 'react-vendor';
          }
          const chartModuleNames = ['recharts', 'd3-', `victory-${'vendor'}`];
          return chartModuleNames.some((moduleName) => id.includes(moduleName))
            ? 'chart-vendor'
            : undefined;
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
});
