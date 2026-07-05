import { useEffect, useRef, useState } from 'react';

/**
 * Abre a camera frontal (webcam no desktop, camera de selfie no celular)
 * e cuida do ciclo de vida do stream.
 */
export function useCamera() {
  const videoRef = useRef(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    let stream;
    let cancelled = false;

    async function start() {
      if (!navigator.mediaDevices?.getUserMedia) {
        setError(
          window.isSecureContext
            ? 'Este navegador nao suporta acesso a camera.'
            : 'A camera exige conexao segura (HTTPS). Acesse o endereco https:// exibido pelo servidor.',
        );
        return;
      }
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'user', width: { ideal: 640 }, height: { ideal: 480 } },
          audio: false,
        });
        if (cancelled) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }
        const video = videoRef.current;
        video.srcObject = stream;
        await video.play();
        setReady(true);
      } catch (e) {
        setError(
          e.name === 'NotAllowedError'
            ? 'Permissao de camera negada. Libere o acesso a camera e recarregue a pagina.'
            : `Nao foi possivel abrir a camera (${e.name}).`,
        );
      }
    }

    start();
    return () => {
      cancelled = true;
      stream?.getTracks().forEach((t) => t.stop());
    };
  }, []);

  return { videoRef, ready, error };
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Captura uma sequencia de frames JPEG do video em andamento.
 * Os frames sao enviados SEM espelhamento (o backend depende da orientacao real
 * da imagem para validar a direcao do movimento de cabeca).
 */
export async function captureFrames(video, { count, intervalMs, onProgress }) {
  const canvas = document.createElement('canvas');
  const scale = Math.min(1, 640 / (video.videoWidth || 640));
  canvas.width = Math.round((video.videoWidth || 640) * scale);
  canvas.height = Math.round((video.videoHeight || 480) * scale);
  const ctx = canvas.getContext('2d');

  const frames = [];
  for (let i = 0; i < count; i++) {
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.85));
    if (blob) frames.push(blob);
    onProgress?.((i + 1) / count);
    if (i < count - 1) await sleep(intervalMs);
  }
  return frames;
}
