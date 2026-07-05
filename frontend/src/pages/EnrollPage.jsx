import { useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../api.js';
import {
  average,
  collectPosedFrames,
  STRAIGHT_RANGE,
  TURN_DEVIATION,
  useCamera,
} from '../camera.js';
import CameraStage from '../components/CameraStage.jsx';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const PHASE_TITLES = {
  STRAIGHT: 'Olhe direto para a camera',
  LEFT: 'Vire o rosto para a ESQUERDA',
  RIGHT: 'Vire o rosto para a DIREITA',
};

export default function EnrollPage() {
  const navigate = useNavigate();
  const { videoRef, ready, error: cameraError } = useCamera();
  const [step, setStep] = useState('idle'); // idle | countdown | capture | upload | done
  const [countdown, setCountdown] = useState(3);
  const [phaseKey, setPhaseKey] = useState('STRAIGHT');
  const [live, setLive] = useState({ ok: false, message: '', progress: 0 });
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const runningRef = useRef(false);

  async function runPhase(key, count, isPoseOk, adjustMessage) {
    setPhaseKey(key);
    setLive({ ok: false, message: 'Prepare-se...', progress: 0 });
    return collectPosedFrames(videoRef.current, {
      count,
      isPoseOk,
      onUpdate: setLive,
      timeoutMs: 30000,
      messages: {
        noFace: 'Rosto nao detectado — centralize o rosto no oval',
        adjust: adjustMessage,
        holding: 'Perfeito, segure assim!',
        timeout:
          'Nao conseguimos confirmar a pose a tempo. Verifique a iluminacao, siga as instrucoes e tente novamente.',
      },
    });
  }

  async function start() {
    if (runningRef.current) return;
    runningRef.current = true;
    setError(null);
    setResult(null);
    try {
      setStep('countdown');
      for (let c = 3; c >= 1; c--) {
        setCountdown(c);
        await sleep(800);
      }

      setStep('capture');

      // Etapa 1 — frontal: qualidade de cadastro (resolucao do rosto) + referencia de pose
      const straight = await runPhase(
        'STRAIGHT',
        8,
        (p) =>
          p.enrollQuality && p.noseRatio >= STRAIGHT_RANGE.min && p.noseRatio <= STRAIGHT_RANGE.max,
        (p) =>
          !p.enrollQuality
            ? 'Aproxime um pouco o rosto da camera'
            : 'Olhe direto para a camera, sem inclinar a cabeca',
      );
      const baseline = average(straight.ratios);

      // Etapa 2 — giro real para a esquerda (so avanca quando o giro e detectado)
      const left = await runPhase(
        'LEFT',
        5,
        (p) => p.noseRatio - baseline >= TURN_DEVIATION,
        'Vire mais o rosto para a ESQUERDA e segure',
      );

      // Etapa 3 — giro real para a direita
      const right = await runPhase(
        'RIGHT',
        5,
        (p) => baseline - p.noseRatio >= TURN_DEVIATION,
        'Vire mais o rosto para a DIREITA e segure',
      );

      setStep('upload');
      const form = new FormData();
      const phases = [
        ...straight.frames.map(() => 'STRAIGHT'),
        ...left.frames.map(() => 'LEFT'),
        ...right.frames.map(() => 'RIGHT'),
      ];
      [...straight.frames, ...left.frames, ...right.frames].forEach((blob, index) => {
        form.append('frames', new File([blob], `frame-${index}.jpg`, { type: 'image/jpeg' }));
      });
      phases.forEach((phase) => form.append('phases', phase));
      const data = await api('/api/face/enroll', { method: 'POST', form });
      setResult(data);
      setStep('done');
    } catch (e) {
      setError(e.message);
      setStep('idle');
    } finally {
      runningRef.current = false;
    }
  }

  return (
    <div className="page">
      <header className="topbar">
        <Link to="/" className="btn ghost small">← Voltar</Link>
        <h1>Cadastro facial</h1>
        <span />
      </header>

      <main className="content narrow">
        <CameraStage
          videoRef={videoRef}
          ready={ready}
          error={cameraError}
          highlight={step === 'capture' && live.ok}
          overlay={
            <>
              {step === 'countdown' && (
                <div className="camera-overlay">
                  <span className="countdown">{countdown}</span>
                </div>
              )}
              {step === 'capture' && (
                <div className={`instruction-bar${phaseKey !== 'STRAIGHT' ? ' challenge' : ''}`}>
                  <strong>
                    {phaseKey === 'LEFT' && '← '}
                    {PHASE_TITLES[phaseKey]}
                    {phaseKey === 'RIGHT' && ' →'}
                  </strong>
                  <span className={live.ok ? 'status-msg ok' : 'status-msg warn'}>
                    {live.message}
                  </span>
                  <div className="progress">
                    <div className="progress-fill" style={{ width: `${live.progress * 100}%` }} />
                  </div>
                  <span className="muted">A barra so avanca quando a pose esta correta</span>
                </div>
              )}
              {step === 'upload' && (
                <div className="camera-overlay">
                  <div className="spinner" />
                  <p>Processando biometria...</p>
                </div>
              )}
            </>
          }
        />

        {error && <p className="error-text">{error}</p>}

        {step === 'idle' && (
          <div className="card">
            <h2>Como funciona</h2>
            <ol className="steps">
              <li>Posicione o rosto dentro do oval, em um lugar bem iluminado.</li>
              <li>
                Siga as instrucoes na tela: olhar para frente, virar para a esquerda e para a
                direita. <strong>Cada pose e verificada em tempo real</strong> — a barra de
                progresso so avanca quando voce executa o movimento.
              </li>
              <li>Tudo roda 100% no seu servidor local.</li>
            </ol>
            <button type="button" className="btn primary big" onClick={start} disabled={!ready}>
              Iniciar captura
            </button>
          </div>
        )}

        {step === 'done' && result && (
          <div className="card result-ok">
            <h2>✅ Biometria cadastrada!</h2>
            <p className="muted">
              {result.framesUsed} de {result.framesReceived} frames aproveitados · consistencia{' '}
              {(result.consistency * 100).toFixed(1)}%
            </p>
            <div className="actions">
              <button type="button" className="btn primary" onClick={() => navigate('/verify')}>
                Testar validacao agora
              </button>
              <Link to="/" className="btn ghost">Voltar ao inicio</Link>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}
