import { useEffect, useRef, useState } from 'react';
import { api } from './api.js';

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
 * Captura um unico frame JPEG do video em andamento.
 * Os frames sao enviados SEM espelhamento (o backend depende da orientacao real
 * da imagem para validar a direcao do movimento de cabeca).
 */
export async function captureFrame(video) {
  const canvas = document.createElement('canvas');
  const scale = Math.min(1, 640 / (video.videoWidth || 640));
  canvas.width = Math.round((video.videoWidth || 640) * scale);
  canvas.height = Math.round((video.videoHeight || 480) * scale);
  const ctx = canvas.getContext('2d');
  ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
  return new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.85));
}

/**
 * Captura guiada com validacao de pose em tempo real: cada frame passa pelo backend
 * (/api/face/pose) e SO e aproveitado se a pose estiver correta. A barra de progresso
 * so avanca quando o usuario realmente executa o que foi pedido; caso contrario a
 * mensagem de orientacao permanece na tela ate o tempo limite.
 *
 * - isPoseOk(pose): decide se a pose atual atende a etapa (recebe {noseRatio, interocular, enrollQuality})
 * - messages.adjust: string fixa ou funcao (pose) => string para orientacao dinamica
 * - onUpdate({ok, message, progress}): feedback ao vivo para a UI
 */
export async function collectPosedFrames(
  video,
  { count, isPoseOk, messages, onUpdate, timeoutMs = 30000, delayMs = 150 },
) {
  const frames = [];
  const ratios = [];
  const startedAt = Date.now();

  while (frames.length < count) {
    if (Date.now() - startedAt > timeoutMs) {
      throw new Error(messages.timeout);
    }
    const blob = await captureFrame(video);
    const form = new FormData();
    form.append('frame', new File([blob], 'pose.jpg', { type: 'image/jpeg' }));
    const pose = await api('/api/face/pose', { method: 'POST', form });

    const progress = frames.length / count;
    if (!pose.faceDetected) {
      onUpdate?.({ ok: false, message: messages.noFace, progress });
    } else if (isPoseOk(pose)) {
      frames.push(blob);
      ratios.push(pose.noseRatio);
      onUpdate?.({ ok: true, message: messages.holding, progress: frames.length / count });
    } else {
      const adjust =
        typeof messages.adjust === 'function' ? messages.adjust(pose) : messages.adjust;
      onUpdate?.({ ok: false, message: adjust, progress });
    }
    await sleep(delayMs);
  }
  return { frames, ratios };
}

/** Faixa considerada "olhando para frente" (posicao relativa do nariz entre os olhos). */
export const STRAIGHT_RANGE = { min: 0.38, max: 0.62 };
/** Desvio minimo vs a pose frontal para considerar que o rosto foi virado. */
export const TURN_DEVIATION = 0.13;

export const average = (values) => values.reduce((sum, v) => sum + v, 0) / values.length;
