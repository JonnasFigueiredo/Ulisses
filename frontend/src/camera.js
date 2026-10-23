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
  const verticals = [];
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
      verticals.push(pose.noseVertical);
      onUpdate?.({ ok: true, message: messages.holding, progress: frames.length / count });
    } else {
      const adjust =
        typeof messages.adjust === 'function' ? messages.adjust(pose) : messages.adjust;
      onUpdate?.({ ok: false, message: adjust, progress });
    }
    await sleep(delayMs);
  }
  return { frames, ratios, verticals };
}

/** Banda "neutra": desvio abaixo disto conta como rosto centralizado (partida do movimento). */
export const NEUTRAL_BAND = 0.06;

/** Faixa de YAW considerada "olhando para frente" (~0 de frente; roll-corrigido no servidor). */
export const STRAIGHT_RANGE = { min: -0.24, max: 0.24 };
/** Desvio minimo vs a pose frontal para o GIRO horizontal (yaw). */
export const TURN_DEVIATION = 0.13;
/** Desvio minimo para a INCLINACAO vertical (pitch): sinal mais fraco, exige menos. */
export const PITCH_DEVIATION = 0.09;

/** Limiar do desvio conforme a direcao (um pouco acima do backend, para nao reprovar depois). */
export const deviationThreshold = (direction) =>
  direction === 'UP' || direction === 'DOWN' ? PITCH_DEVIATION : TURN_DEVIATION;

/**
 * Captura um MOVIMENTO de prova de vida como uma rajada CONTINUA: grava do rosto centralizado
 * (frame neutro) ate atingir e segurar o desvio pedido, enviando TODA a trajetoria (centro ->
 * movimento) em ordem. O servidor exige justamente essa transicao observada — o que impede que
 * fotos ja viradas do alvo sejam rotuladas como "giro". Retorna os frames na ordem de captura.
 */
export async function captureChallengeMotion(video, {
  direction,
  baselines,
  onUpdate,
  messages,
  timeoutMs = 30000,
  minFrames = 6,
  maxFrames = 16,
  holdTarget = 3,
  delayMs = 150,
}) {
  const frames = [];
  let sawNeutral = false;
  let motionStarted = false;
  let held = 0;
  const startedAt = Date.now();

  while (true) {
    if (Date.now() - startedAt > timeoutMs) {
      throw new Error(messages.timeout);
    }
    const blob = await captureFrame(video);
    const form = new FormData();
    form.append('frame', new File([blob], 'pose.jpg', { type: 'image/jpeg' }));
    const pose = await api('/api/face/pose', { method: 'POST', form });

    if (!pose.faceDetected || positionIssue(pose, { strict: false })) {
      onUpdate?.({ ok: false, message: messages.noFace, progress: held / holdTarget });
      await sleep(delayMs);
      continue;
    }

    const dev = challengeDeviation(pose, direction, baselines);
    const target = deviationThreshold(direction);
    // Esperar no centro nao deve consumir o limite da trajetoria antes do movimento.
    // Conserva apenas os dois ultimos frames neutros, depois registra a transicao inteira.
    if (!motionStarted && Math.abs(dev) <= NEUTRAL_BAND) {
      sawNeutral = true;
      if (frames.length >= 2) frames.shift();
      frames.push(blob);
    } else if (sawNeutral) {
      motionStarted = true;
      frames.push(blob);
    }
    if (sawNeutral && dev >= target) held += 1;

    if (!sawNeutral) {
      onUpdate?.({ ok: false, message: messages.center, progress: 0 });
    } else if (dev < target) {
      onUpdate?.({ ok: false, message: messages.move, progress: Math.max(0, Math.min(0.6, (dev / target) * 0.6)) });
    } else {
      onUpdate?.({ ok: true, message: messages.hold, progress: 0.6 + 0.4 * (held / holdTarget) });
    }

    if (sawNeutral && held >= holdTarget && frames.length >= minFrames) break;
    if (frames.length >= maxFrames) {
      // Uma tentativa longa demais precisa recomecar do centro, sem aumentar o upload
      // nem interromper o prazo de captura anunciado ao usuario.
      frames.length = 0;
      sawNeutral = false;
      motionStarted = false;
      held = 0;
      onUpdate?.({ ok: false, message: messages.center, progress: 0 });
    }
    await sleep(delayMs);
  }

  return { frames };
}

/** Rotulo, seta e eixo de cada direcao de desafio (LEFT/RIGHT = giro; UP/DOWN = inclinacao). */
export const DIRECTIONS = {
  LEFT: { label: 'ESQUERDA', arrow: '←', verb: 'Vire o rosto para a' },
  RIGHT: { label: 'DIREITA', arrow: '→', verb: 'Vire o rosto para a' },
  UP: { label: 'CIMA', arrow: '↑', verb: 'Incline o rosto para' },
  DOWN: { label: 'BAIXO', arrow: '↓', verb: 'Incline o rosto para' },
};

/**
 * Desvio assinado da pose atual no eixo do desafio (positivo = na direcao pedida),
 * medido contra as linhas de base capturadas na fase frontal.
 */
export function challengeDeviation(pose, direction, baselines) {
  switch (direction) {
    case 'LEFT':
      return pose.noseRatio - baselines.h;
    case 'RIGHT':
      return baselines.h - pose.noseRatio;
    case 'UP':
      return baselines.v - pose.noseVertical;
    case 'DOWN':
      return pose.noseVertical - baselines.v;
    default:
      return 0;
  }
}

export const average = (values) => values.reduce((sum, v) => sum + v, 0) / values.length;

/**
 * Regiao de aceitacao do oval-guia, em coordenadas normalizadas do quadro capturado.
 * O oval e desenhado no centro do palco; aqui exigimos o rosto centralizado e com
 * tamanho adequado para que o guia visual corresponda ao que e validado de fato.
 */
export const OVAL = { maxDx: 0.12, maxDy: 0.15, minHeight: 0.28, maxHeight: 0.85 };

/**
 * Retorna a orientacao de posicionamento pendente, ou null se o rosto esta bem
 * encaixado no oval. Com strict=false (fases de giro), a tolerancia de centro aumenta.
 */
export function positionIssue(pose, { strict = true } = {}) {
  if (!pose || !pose.faceDetected) {
    return 'Posicione o rosto dentro do oval';
  }
  if (pose.multipleFaces) {
    return 'Apenas uma pessoa deve aparecer no video';
  }
  if (pose.faceHeight < OVAL.minHeight) {
    return 'Aproxime o rosto da camera';
  }
  if (pose.faceHeight > OVAL.maxHeight) {
    return 'Afaste um pouco o rosto da camera';
  }
  const slack = strict ? 1 : 1.6;
  if (
    Math.abs(pose.centerX - 0.5) > OVAL.maxDx * slack ||
    Math.abs(pose.centerY - 0.5) > OVAL.maxDy * slack
  ) {
    return 'Centralize o rosto no oval';
  }
  return null;
}

/**
 * Monitora a pose continuamente enquanto `active` (ex.: antes de iniciar a captura),
 * para dar feedback de posicionamento ao vivo no oval-guia.
 */
export function useLivePosition(videoRef, ready, active) {
  const [pose, setPose] = useState(null);

  useEffect(() => {
    if (!ready || !active) {
      setPose(null);
      return undefined;
    }
    let stopped = false;
    (async () => {
      while (!stopped) {
        try {
          const blob = await captureFrame(videoRef.current);
          const form = new FormData();
          form.append('frame', new File([blob], 'pose.jpg', { type: 'image/jpeg' }));
          const result = await api('/api/face/pose', { method: 'POST', form });
          if (!stopped) setPose(result);
        } catch {
          if (!stopped) setPose(null);
        }
        await sleep(400);
      }
    })();
    return () => {
      stopped = true;
    };
  }, [videoRef, ready, active]);

  return pose;
}
