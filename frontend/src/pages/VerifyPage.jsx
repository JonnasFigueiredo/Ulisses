import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { captureFrames, useCamera } from '../camera.js';
import CameraStage from '../components/CameraStage.jsx';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export default function VerifyPage() {
  const { videoRef, ready, error: cameraError } = useCamera();
  const [step, setStep] = useState('idle'); // idle | countdown | straight | turn | upload | done
  const [countdown, setCountdown] = useState(3);
  const [direction, setDirection] = useState(null); // LEFT | RIGHT (lado do proprio usuario)
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
      // O servidor sorteia o desafio de prova de vida (uso unico, expira em 2 min)
      const challenge = await api('/api/face/challenge', { method: 'POST' });
      setDirection(challenge.direction);

      setStep('countdown');
      for (let c = 3; c >= 1; c--) {
        setCountdown(c);
        await sleep(800);
      }

      const allFrames = [];
      const allPhases = [];

      setStep('straight');
      setProgress(0);
      await sleep(500);
      const straightFrames = await captureFrames(videoRef.current, {
        count: 6,
        intervalMs: 300,
        onProgress: setProgress,
      });
      straightFrames.forEach((blob, index) => {
        allFrames.push(new File([blob], `straight-${index}.jpg`, { type: 'image/jpeg' }));
        allPhases.push('STRAIGHT');
      });

      setStep('turn');
      setProgress(0);
      await sleep(900); // tempo para o usuario iniciar o giro
      const turnFrames = await captureFrames(videoRef.current, {
        count: 8,
        intervalMs: 280,
        onProgress: setProgress,
      });
      turnFrames.forEach((blob, index) => {
        allFrames.push(new File([blob], `turn-${index}.jpg`, { type: 'image/jpeg' }));
        allPhases.push('TURN');
      });

      setStep('upload');
      const form = new FormData();
      form.append('challengeId', challenge.challengeId);
      allFrames.forEach((file) => form.append('frames', file));
      allPhases.forEach((phase) => form.append('phases', phase));
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
          highlight={step === 'straight' || step === 'turn'}
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
                  <span>Rosto centralizado no oval</span>
                  <div className="progress">
                    <div className="progress-fill" style={{ width: `${progress * 100}%` }} />
                  </div>
                </div>
              )}
              {step === 'turn' && (
                <div className="instruction-bar challenge">
                  <strong>
                    {directionArrow} Vire o rosto para a {directionLabel} {directionArrow}
                  </strong>
                  <span>Prova de vida: mantenha o giro ate a barra completar</span>
                  <div className="progress">
                    <div className="progress-fill" style={{ width: `${progress * 100}%` }} />
                  </div>
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
              cabeca que deve ser executado ao vivo — fotos e videos gravados nao passam.
            </p>
            <button type="button" className="btn primary big" onClick={start} disabled={!ready}>
              Iniciar validacao
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
