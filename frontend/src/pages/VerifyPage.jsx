import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import {
  average,
  collectPosedFrames,
  positionIssue,
  STRAIGHT_RANGE,
  TURN_DEVIATION,
  useCamera,
  useLivePosition,
} from '../camera.js';
import CameraStage from '../components/CameraStage.jsx';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export default function VerifyPage() {
  const { videoRef, ready, error: cameraError } = useCamera();
  const [step, setStep] = useState('idle'); // idle | countdown | straight | turn | upload | done
  const [countdown, setCountdown] = useState(3);
  const [direction, setDirection] = useState(null); // LEFT | RIGHT (lado do proprio usuario)
  const [live, setLive] = useState({ ok: false, message: '', progress: 0 });
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const runningRef = useRef(false);

  const showIdle = step === 'idle' || step === 'done';
  const livePose = useLivePosition(videoRef, ready, showIdle);
  const positionMsg = showIdle && ready ? positionIssue(livePose) : null;
  const positioned = showIdle && livePose != null && positionMsg == null;

  async function start() {
    if (runningRef.current) return;
    runningRef.current = true;
    setError(null);
    setResult(null);
    try {
      // O servidor sorteia o desafio de prova de vida (uso unico, expira em 2 min)
      const challenge = await api('/api/face/challenge', { method: 'POST' });
      setDirection(challenge.direction);
      const directionLabel = challenge.direction === 'LEFT' ? 'ESQUERDA' : 'DIREITA';

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
      const baseline = average(straight.ratios);

      // Desafio: o giro so conta quando detectado na direcao sorteada
      setStep('turn');
      setLive({ ok: false, message: 'Prepare-se...', progress: 0 });
      const turn = await collectPosedFrames(videoRef.current, {
        count: 8,
        isPoseOk: (p) =>
          positionIssue(p, { strict: false }) == null &&
          (challenge.direction === 'LEFT'
            ? p.noseRatio - baseline >= TURN_DEVIATION
            : baseline - p.noseRatio >= TURN_DEVIATION),
        onUpdate: setLive,
        timeoutMs: 30000,
        messages: {
          noFace: 'Rosto nao detectado — mantenha o rosto visivel durante o giro',
          adjust: (p) =>
            positionIssue(p, { strict: false }) ||
            `Vire mais o rosto para a ${directionLabel} e segure`,
          holding: 'Isso! Mantenha o giro',
          timeout: 'O movimento de cabeca nao foi confirmado a tempo. Tente novamente.',
        },
      });

      setStep('upload');
      const form = new FormData();
      form.append('challengeId', challenge.challengeId);
      const phases = [...straight.frames.map(() => 'STRAIGHT'), ...turn.frames.map(() => 'TURN')];
      [...straight.frames, ...turn.frames].forEach((blob, index) => {
        form.append('frames', new File([blob], `frame-${index}.jpg`, { type: 'image/jpeg' }));
      });
      phases.forEach((phase) => form.append('phases', phase));
      const data = await api('/api/face/verify', { method: 'POST', form });
      setResult(data);
      setStep('done');
    } catch (e) {
      setError(e.message);
      setStep('idle');
    } finally {
      runningRef.current = false;
    }
  }

  const directionLabel = direction === 'LEFT' ? 'ESQUERDA' : 'DIREITA';
  const directionArrow = direction === 'LEFT' ? '←' : '→';

  return (
    <div className="page">
      <header className="topbar">
        <Link to="/" className="btn ghost small">← Voltar</Link>
        <h1>Validar identidade</h1>
        <span />
      </header>

      <main className="content narrow">
        <CameraStage
          videoRef={videoRef}
          ready={ready}
          error={cameraError}
          guideState={
            showIdle && livePose != null
              ? positioned
                ? 'ok'
                : 'bad'
              : step === 'straight' || step === 'turn'
                ? live.ok
                  ? 'ok'
                  : 'bad'
                : null
          }
          chip={
            step === 'idle' ? positionMsg || (positioned ? 'Rosto posicionado ✓' : null) : null
          }
          overlay={
            <>
              {step === 'countdown' && (
                <div className="camera-overlay">
                  <span className="countdown">{countdown}</span>
                </div>
              )}
              {step === 'straight' && (
                <div className="instruction-bar">
                  <strong>Olhe direto para a camera</strong>
                  <span className={live.ok ? 'status-msg ok' : 'status-msg warn'}>
                    {live.message}
                  </span>
                  <div className="progress">
                    <div className="progress-fill" style={{ width: `${live.progress * 100}%` }} />
                  </div>
                </div>
              )}
              {step === 'turn' && (
                <div className="instruction-bar challenge">
                  <strong>
                    {directionArrow} Vire o rosto para a {directionLabel} {directionArrow}
                  </strong>
                  <span className={live.ok ? 'status-msg ok' : 'status-msg warn'}>
                    {live.message}
                  </span>
                  <div className="progress">
                    <div className="progress-fill" style={{ width: `${live.progress * 100}%` }} />
                  </div>
                  <span className="muted">Prova de vida: mantenha o giro ate a barra completar</span>
                </div>
              )}
              {step === 'upload' && (
                <div className="camera-overlay">
                  <div className="spinner" />
                  <p>Validando identidade...</p>
                </div>
              )}
            </>
          }
        />

        {error && <p className="error-text">{error}</p>}

        {step === 'idle' && (
          <div className="card">
            <h2>Validacao com prova de vida</h2>
            <p className="muted">
              Alem de comparar seu rosto com a biometria cadastrada, o sistema sorteia um movimento de
              cabeca que deve ser executado ao vivo — fotos e videos gravados nao passam. O movimento e
              verificado em tempo real: a barra so avanca quando voce gira o rosto de verdade.
            </p>
            <button
              type="button"
              className="btn primary big"
              onClick={start}
              disabled={!ready || !positioned}
            >
              {positioned ? 'Iniciar validacao' : 'Posicione o rosto no oval para comecar'}
            </button>
          </div>
        )}

        {step === 'done' && result && (
          <div className={`card ${result.success ? 'result-ok' : 'result-fail'}`}>
            <h2>{result.success ? '✅ Identidade validada' : '❌ Validacao recusada'}</h2>
            <ul className="checklist">
              <li>
                {result.match ? '✔' : '✘'} Reconhecimento facial:{' '}
                {(result.similarity * 100).toFixed(1)}% de similaridade (minimo{' '}
                {(result.threshold * 100).toFixed(0)}%)
              </li>
              <li>
                {result.liveness ? '✔' : '✘'} Prova de vida: {result.livenessDetail}
              </li>
            </ul>
            <div className="actions">
              <button type="button" className="btn primary" onClick={start}>
                Validar novamente
              </button>
              <Link to="/" className="btn ghost">Voltar ao inicio</Link>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}
