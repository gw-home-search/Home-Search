import react from '@vitejs/plugin-react'; import {defineConfig} from 'vite';
export default defineConfig({plugins:[react()],build:{ssr:'seo-renderer/server.ts',outDir:'dist/renderer',emptyOutDir:true,rollupOptions:{output:{entryFileNames:'server.mjs'}}},ssr:{noExternal:true}});
