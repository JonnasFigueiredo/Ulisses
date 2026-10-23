import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { useFaceCapture } from '../useFaceCapture.js';
import FaceCaptureStage from '../components/FaceCaptureStage.jsx';
import { IconArrowLeft, IconCheck, IconFaceScan, IconRefresh, IconX } from '../components/Icons.jsx';

export default function VerifyPage() {
  const capture = useFaceCapture();
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  async function start() {
    setError(null);
    setResult(null);
    try {
      const form = await capture.capture();
      const data = await api('/api/face/verify', { method: 'POST', form });
      setResult(data);
      capture.setStep('done');
    } catch (e) {
      setError(e.message);
      capture.setStep('idle');
    }
  }

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
        <FaceCaptureStage capture={capture} busyLabel="Validando identidade..." />

        {error && <p className="error-text">{error}</p>}

        {capture.step === 'idle' && (
          <div className="card">
            <h2>Validacao com prova de vida</h2>
            <p className="muted">
              Alem de comparar seu rosto com a biometria cadastrada, o sistema sorteia uma sequencia de
              dois movimentos de cabeca para voce executar na ordem indicada. A barra acompanha
              a pose detectada durante a captura. Esta demonstracao experimental ainda nao possui
              eficacia validada contra fotos ou videos gravados.
            </p>
            <button
              type="button"
              className="btn primary big"
              onClick={start}
              disabled={!capture.ready || !capture.positioned}
            >
              <IconFaceScan size={19} />
              {capture.positioned ? 'Iniciar validacao' : 'Posicione o rosto no oval para comecar'}
            </button>
          </div>
        )}

        {capture.step === 'done' && result && (
          <div className={`card ${result.success ? 'result-ok' : 'result-fail'}`}>
            <div className="card-title">
              <span className={`icon-badge ${result.success ? 'ok' : 'fail'}`}>
                {result.success ? <IconCheck size={22} /> : <IconX size={22} />}
              </span>
              <h2>{result.success ? 'Identidade validada' : 'Validacao recusada'}</h2>
            </div>
            <VerificationChecklist result={result} />
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

/** Checklist reutilizavel do resultado da validacao (identidade + prova de vida + anti-spoofing). */
export function VerificationChecklist({ result }) {
  return (
    <ul className="checklist">
      <li>
        <span className={`check-icon ${result.match ? 'ok' : 'fail'}`}>
          {result.match ? <IconCheck size={17} /> : <IconX size={17} />}
        </span>
        Reconhecimento facial: {(result.similarity * 100).toFixed(1)}% de similaridade (minimo{' '}
        {(result.threshold * 100).toFixed(0)}%)
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
  );
}
