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
import {
  IconArrowLeft,
  IconCheck,
  IconFaceScan,
  IconRefresh,
  IconX,
} from '../components/Icons.jsx';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export default function VerifyPage() {
  const { videoRef, ready, error: cameraError } = useCamera();
  const [step, setStep] = useState('idle'); // idle | countdown | straight | turn | upload | done
  const [countdown, setCountdown] = useState(3);
  // Sequencia de DOIS movimentos sorteados pelo servidor (lado do proprio usuario)
  const [directions, setDirections] = useState([]);
  const [turnIndex, setTurnIndex] = useState(0);
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
      // O servidor sorteia o desafio de prova de vida: DOIS movimentos em sequencia
      // (uso unico, expira em 2 min) — um video gravado nao acerta a ordem sorteada
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
      const baseline = average(straight.ratios);

      // Desafios: cada giro so conta quando detectado na direcao sorteada, na ordem
      const allFrames = [...straight.frames];
      const allPhases = straight.frames.map(() => 'STRAIGHT');
      for (let i = 0; i < challenge.directions.length; i++) {
        const dir = challenge.directions[i];
        const dirLabel = dir === 'LEFT' ? 'ESQUERDA' : 'DIREITA';
        setTurnIndex(i);
        setStep('turn');
        setLive({ ok: false, message: 'Prepare-se...', progress: 0 });
        // pequena pausa para o usuario voltar ao centro entre um giro e outro
        if (i > 0) await sleep(900);
        const turn = await collectPosedFrames(videoRef.current, {
          count: 6,
          isPoseOk: (p) =>
            positionIssue(p, { strict: false }) == null &&
            (dir === 'LEFT'
              ? p.noseRatio - baseline >= TURN_DEVIATION
              : baseline - p.noseRatio >= TURN_DEVIATION),
          onUpdate: setLive,
          timeoutMs: 30000,
          messages: {
            noFace: 'Rosto nao detectado — mantenha o rosto visivel durante o giro',
            adjust: (p) =>
              positionIssue(p, { strict: false }) ||
              `Vire mais o rosto para a ${dirLabel} e segure`,
            holding: 'Isso! Mantenha o giro',
            timeout: 'O movimento de cabeca nao foi confirmado a tempo. Tente novamente.',
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

  const currentDirection = directions[turnIndex];
  const directionLabel = currentDirection === 'LEFT' ? 'ESQUERDA' : 'DIREITA';
  const directionArrow = currentDirection === 'LEFT' ? '←' : '→';

  return (
    <div className="page">
      <header className="topbar">
        <Link to="/" className="btn ghost small">
          <IconArrowLeft size={16} /> Voltar
        </Link>
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
                  <span className="muted">
                    Prova de vida — movimento {turnIndex + 1} de {directions.length}
                  </span>
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
              Alem de comparar seu rosto com a biometria cadastrada, o sistema sorteia uma sequencia de
              DOIS movimentos de cabeca que devem ser executados ao vivo, na ordem — fotos e videos
              gravados nao passam. Cada movimento e verificado em tempo real: a barra so avanca quando
              voce gira o rosto de verdade.
            </p>
            <button
              type="button"
              className="btn primary big"
              onClick={start}
              disabled={!ready || !positioned}
            >
              <IconFaceScan size={19} />
              {positioned ? 'Iniciar validacao' : 'Posicione o rosto no oval para comecar'}
            </button>
          </div>
        )}

        {step === 'done' && result && (
          <div className={`card ${result.success ? 'result-ok' : 'result-fail'}`}>
            <div className="card-title">
              <span className={`icon-badge ${result.success ? 'ok' : 'fail'}`}>
                {result.success ? <IconCheck size={22} /> : <IconX size={22} />}
              </span>
              <h2>{result.success ? 'Identidade validada' : 'Validacao recusada'}</h2>
            </div>
            <ul className="checklist">
              <li>
                <span className={`check-icon ${result.match ? 'ok' : 'fail'}`}>
                  {result.match ? <IconCheck size={17} /> : <IconX size={17} />}
                </span>
                Reconhecimento facial: {(result.similarity * 100).toFixed(1)}% de similaridade
                (minimo {(result.threshold * 100).toFixed(0)}%)
              </li>
              <li>
                <span className={`check-icon ${result.liveness ? 'ok' : 'fail'}`}>
                  {result.liveness ? <IconCheck size={17} /> : <IconX size={17} />}
                </span>
                Prova de vida (movimento): {result.livenessDetail}
              </li>
              {result.passiveEvaluated && (
                <li>
                  <span className={`check-icon ${result.passivePassed ? 'ok' : 'fail'}`}>
                    {result.passivePassed ? <IconCheck size={17} /> : <IconX size={17} />}
                  </span>
                  Anti-spoofing (textura): {(result.passiveScore * 100).toFixed(0)}% de liveness
                  {result.passivePassed ? '' : ' — possivel foto ou tela'}
                </li>
              )}
            </ul>
            <div className="actions">
              <button type="button" className="btn primary" onClick={start}>
                <IconRefresh size={18} /> Validar novamente
              </button>
              <Link to="/" className="btn ghost">Voltar ao inicio</Link>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}
