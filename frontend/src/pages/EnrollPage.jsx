import { useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../api.js';
import { captureFrames, useCamera } from '../camera.js';
import CameraStage from '../components/CameraStage.jsx';

// Poses capturadas no cadastro: frente + leves giros para robustez a variacao de pose.
const PHASES = [
  { key: 'STRAIGHT', label: 'Olhe direto para a camera', hint: 'Mantenha o rosto no oval', frames: 8, intervalMs: 280 },
  { key: 'LEFT', label: 'Vire LEVEMENTE o rosto para a esquerda', hint: 'Giro suave, sem pressa', frames: 5, intervalMs: 300 },
  { key: 'RIGHT', label: 'Agora LEVEMENTE para a direita', hint: 'Giro suave, sem pressa', frames: 5, intervalMs: 300 },
];

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export default function EnrollPage() {
  const navigate = useNavigate();
  const { videoRef, ready, error: cameraError } = useCamera();
  const [step, setStep] = useState('idle'); // idle | countdown | capture | upload | done
  const [countdown, setCountdown] = useState(3);
  const [phaseIndex, setPhaseIndex] = useState(0);
  const [progress, setProgress] = useState(0);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const runningRef = useRef(false);

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
      const allFrames = [];
      const allPhases = [];
      for (let i = 0; i < PHASES.length; i++) {
        setPhaseIndex(i);
        setProgress(0);
        await sleep(700); // tempo para o usuario assumir a pose
        const frames = await captureFrames(videoRef.current, {
          count: PHASES[i].frames,
          intervalMs: PHASES[i].intervalMs,
          onProgress: setProgress,
        });
        frames.forEach((blob, index) => {
          allFrames.push(new File([blob], `${PHASES[i].key.toLowerCase()}-${index}.jpg`, { type: 'image/jpeg' }));
          allPhases.push(PHASES[i].key);
        });
      }

      setStep('upload');
      const form = new FormData();
      allFrames.forEach((file) => form.append('frames', file));
      allPhases.forEach((phase) => form.append('phases', phase));
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

  const phase = PHASES[phaseIndex];

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
          highlight={step === 'capture'}
          overlay={
            <>
              {step === 'countdown' && (
                <div className="camera-overlay">
                  <span className="countdown">{countdown}</span>
                </div>
              )}
              {step === 'capture' && (
                <div className="instruction-bar">
                  <strong>{phase.label}</strong>
                  <span>{phase.hint}</span>
                  <div className="progress">
                    <div className="progress-fill" style={{ width: `${progress * 100}%` }} />
                  </div>
                  <span className="muted">
                    Etapa {phaseIndex + 1} de {PHASES.length}
                  </span>
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
              <li>Siga as instrucoes na tela: olhar para frente e girar levemente a cabeca.</li>
              <li>A captura leva cerca de 10 segundos e roda 100% no seu servidor local.</li>
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
