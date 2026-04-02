import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/onboarding': {
        target: 'http://localhost:8080/api/v1',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/onboarding/, ''),
      },
      '/settings': {
        target: 'http://localhost:8080/api/v1',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/settings/, ''),
      },
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    target: 'es2022',
  },
});
