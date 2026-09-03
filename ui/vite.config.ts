/// <reference types="vitest" />
import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
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
    rollupOptions: {
      output: {
        // Ensure CSS loads before JS in the generated index.html
        manualChunks: undefined,
      },
    },
  },
  plugins: [
    {
      name: 'css-before-js',
      transformIndexHtml(html) {
        // Reorder: put <link> (CSS) before <script> (JS) in <head>
        return html.replace(
          /(<script type="module"[^>]*><\/script>)\s*(<link rel="stylesheet"[^>]*>)/,
          '$2\n  $1'
        );
      },
    },
  ],
  test: {
    reporters: ['default', 'junit'],
    outputFile: {
      junit: './coverage/junit.xml',
    },
    coverage: {
      enabled: true,
      provider: 'v8',
      reporter: ['text', 'text-summary', 'json-summary', 'html'],
      reportsDirectory: './coverage',
      include: ['src/**/*.ts'],
      exclude: [
        'src/**/*.test.ts',
        'src/types/**',
        'src/generated/**',
        'src/api/generated/**',
        'src/main.ts',
      ],
    },
  },
});
