/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

// WildFly sert le build depuis la racine du contexte : base '/'.
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      manifest: {
        name: 'TupleTrace',
        short_name: 'TupleTrace',
        description: 'Partage de jeux de données traçables',
        lang: 'fr',
        start_url: '/',
        display: 'standalone',
        background_color: '#f6f8f6',
        theme_color: '#111d13',
        icons: [{ src: 'icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any maskable' }],
      },
      workbox: {
        // Cache = coquille statique uniquement. Jamais de réponse /api/** (BF-PWA-01, SR-CON-06).
        globPatterns: ['**/*.{js,css,html,svg,woff2}'],
        navigateFallback: 'index.html',
        navigateFallbackDenylist: [/^\/api\//],
        runtimeCaching: [],
      },
    }),
  ],
  server: {
    proxy: { '/api': { target: 'https://localhost:8443', secure: false, changeOrigin: true } },
  },
  test: { env: { VITE_USE_MOCKS: 'true', VITE_MOCK_AUTH: 'false' }, environment: 'jsdom', globals: true, setupFiles: ['./src/test/setup.ts'] },
});
