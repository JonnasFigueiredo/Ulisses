import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import basicSsl from '@vitejs/plugin-basic-ssl';

// `npm run dev`        -> HTTP em localhost (a camera funciona: localhost e contexto seguro)
// `npm run dev:mobile` -> HTTPS com certificado autoassinado, para acessar pelo celular
//                         via IP da rede local (navegadores exigem HTTPS para liberar a camera)
export default defineConfig(({ mode }) => ({
  plugins: [react(), ...(mode === 'mobile' ? [basicSsl()] : [])],
  server: {
    host: true,
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
}));
