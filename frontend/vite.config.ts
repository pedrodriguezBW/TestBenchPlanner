import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// En desarrollo, las llamadas a /api se envían al backend Java (puerto 5001).
// La compilación se deja en dist/ y el backend la empaqueta y la sirve en «/».
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:5001',
      '/clasico': 'http://127.0.0.1:5001',
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
