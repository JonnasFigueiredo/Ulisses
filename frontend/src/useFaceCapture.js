import { useRef, useState } from 'react';
import { api } from './api.js';
import {
  average,
  captureChallengeMotion,
  collectPosedFrames,
  DIRECTIONS,
  positionIssue,
  STRAIGHT_RANGE,
  useCamera,
  useLivePosition,
} from './camera.js';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Encapsula TODO o fluxo de captura com prova de vida (sorteio do desafio, fase frontal e os dois
 * movimentos), compartilhado entre a validacao e a demo de ponto. `capture()` executa a captura e
 * devolve o FormData pronto (challengeId + frames + phases) — quem chama decide o endpoint. Assim,
 * qualquer tela usa exatamente a mesma logica de liveness/anti-spoofing.
 */
export function useFaceCapture() {
  const { videoRef, ready, error: cameraError } = useCamera();
  const [step, setStep] = useState('idle'); // idle | countdown | straight | turn | upload | done
  const [countdown, setCountdown] = useState(3);
  const [directions, setDirections] = useState([]);
  const [turnIndex, setTurnIndex] = useState(0);
  const [live, setLive] = useState({ ok: false, message: '', progress: 0 });
  const runningRef = useRef(false);

  const idleOrDone = step === 'idle' || step === 'done';
  const livePose = useLivePosition(videoRef, ready, idleOrDone);
  const positionMsg = idleOrDone && ready ? positionIssue(livePose) : null;
  const positioned = idleOrDone && livePose != null && positionMsg == null;

  async function capture() {
    if (runningRef.current) throw new Error('Captura em andamento');
    runningRef.current = true;
    try {
      // Desafio: DOIS movimentos entre 4 direcoes, uso unico, expira em 2 min
      const challenge = await api('/api/face/challenge', { method: 'POST' });
      setDirections(challenge.directions);

      setStep('countdown');
      for (let c = 3; c >= 1; c--) {
        setCountdown(c);
        await sleep(800);
      }

      // Fase frontal: identidade + referencia de pose
      setStep('straight');
      setLive({ ok: false, message: 'Prepare-se...', progress: 0 });
      const straight = await collectPosedFrames(videoRef.current, {
        count: 6,
        isPoseOk: (p) =>
          positionIssue(p) == null &&
          p.noseRatio >= STRAIGHT_RANGE.min &&
          p.noseRatio <= STRAIGHT_RANGE.max,
        onUpdate: setLive,
        timeoutMs: 30000,
        messages: {
          noFace: 'Rosto nao detectado — centralize o rosto no oval',
          adjust: (p) => positionIssue(p) || 'Olhe direto para a camera, sem inclinar a cabeca',
          holding: 'Perfeito, segure assim!',
          timeout: 'Nao conseguimos detectar o rosto a tempo. Verifique a iluminacao e tente novamente.',
        },
      });
      const baselines = { h: average(straight.ratios), v: average(straight.verticals) };

      const allFrames = [...straight.frames];
      const allPhases = straight.frames.map(() => 'STRAIGHT');
      for (let i = 0; i < challenge.directions.length; i++) {
        const dir = challenge.directions[i];
        const info = DIRECTIONS[dir];
        setTurnIndex(i);
        setStep('turn');
        setLive({ ok: false, message: 'Prepare-se...', progress: 0 });
        if (i > 0) await sleep(900);
        const turn = await captureChallengeMotion(videoRef.current, {
          direction: dir,
          baselines,
          onUpdate: setLive,
          timeoutMs: 30000,
          messages: {
            noFace: 'Rosto nao detectado — mantenha o rosto visivel e centralizado',
            center: 'Centralize o rosto para comecar o movimento',
            move: `${info.verb} ${info.label}`,
            hold: 'Isso! Segure a pose',
            timeout: 'O movimento nao foi confirmado a tempo. Comece centralizado e mova o rosto. Tente novamente.',
          },
        });
        turn.frames.forEach((blob) => {
          allFrames.push(blob);
          allPhases.push(`TURN${i + 1}`);
        });
      }

      setStep('upload');
      const form = new FormData();
      form.append('challengeId', challenge.challengeId);
      allFrames.forEach((blob, index) => {
        form.append('frames', new File([blob], `frame-${index}.jpg`, { type: 'image/jpeg' }));
      });
      allPhases.forEach((phase) => form.append('phases', phase));
      return form;
    } finally {
      runningRef.current = false;
    }
  }

  return {
    videoRef,
    ready,
    cameraError,
    step,
    setStep,
    countdown,
    directions,
    turnIndex,
    live,
    idleOrDone,
    livePose,
    positionMsg,
    positioned,
    capture,
  };
}
