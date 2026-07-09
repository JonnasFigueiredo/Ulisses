import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { useFaceCapture } from '../useFaceCapture.js';
import FaceCaptureStage from '../components/FaceCaptureStage.jsx';
import { VerificationChecklist } from './VerifyPage.jsx';
import {
  IconArrowLeft,
  IconCheck,
  IconClock,
  IconFaceScan,
  IconRefresh,
  IconX,
} from '../components/Icons.jsx';

const hhmm = (iso) =>
  new Date(iso).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });

const typeLabel = (t) => (t === 'ENTRADA' ? 'Entrada' : 'Saida');

export default function PontoPage() {
  const { user } = useAuth();
  const capture = useFaceCapture();
  const [nextType, setNextType] = useState(null);
  const [today, setToday] = useState([]);
  const [punch, setPunch] = useState(null); // resultado do ultimo registro
  const [error, setError] = useState(null);

  async function refresh() {
    try {
      const data = await api('/api/timeclock/today');
      setNextType(data.nextType);
      setToday(data.today);
    } catch (e) {
      setError(e.message);
    }
  }

  useEffect(() => {
    refresh();
  }, []);

  async function baterPonto() {
    setError(null);
    setPunch(null);
    try {
      const form = await capture.capture();
      const data = await api('/api/timeclock/punch', { method: 'POST', form });
      setPunch(data);
      setToday(data.today);
      if (data.registered) {
        setNextType(data.type === 'ENTRADA' ? 'SAIDA' : 'ENTRADA');
      }
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
        <h1>Ponto eletronico</h1>
        <span />
      </header>

      <main className="content narrow">
        <FaceCaptureStage capture={capture} busyLabel="Confirmando quem esta batendo o ponto..." />

        {error && <p className="error-text">{error}</p>}

        {capture.step === 'idle' && (
          <div className="card">
            <div className="card-title">
              <span className="icon-badge">
                <IconClock size={22} />
              </span>
              <div>
                <h2>Bater ponto por reconhecimento facial</h2>
                <p className="muted">
                  {user.name} · proximo registro:{' '}
                  <strong>{nextType ? typeLabel(nextType) : '...'}</strong>
                </p>
              </div>
            </div>
            <p className="muted">
              O ponto so e registrado se a validacao facial completa passar — com a mesma prova de
              vida (movimentos ao vivo) e anti-spoofing. Ninguem bate o ponto por voce.
            </p>
            <button
              type="button"
              className="btn primary big"
              onClick={baterPonto}
              disabled={!capture.ready || !capture.positioned}
            >
              <IconFaceScan size={19} />
              {capture.positioned
                ? `Bater ponto — ${nextType ? typeLabel(nextType) : ''}`
                : 'Posicione o rosto no oval para comecar'}
            </button>
          </div>
        )}

        {capture.step === 'done' && punch && (
          <div className={`card ${punch.registered ? 'result-ok' : 'result-fail'}`}>
            {punch.registered ? (
              <>
                <div className="card-title">
                  <span className="icon-badge ok">
                    <IconCheck size={22} />
                  </span>
                  <div>
                    <h2>Ponto registrado!</h2>
                    <p className="muted">
                      {typeLabel(punch.type)} as <strong>{hhmm(punch.at)}</strong> ·{' '}
                      {(punch.verification.similarity * 100).toFixed(1)}% de similaridade
                    </p>
                  </div>
                </div>
              </>
            ) : (
              <>
                <div className="card-title">
                  <span className="icon-badge fail">
                    <IconX size={22} />
                  </span>
                  <h2>Ponto nao registrado</h2>
                </div>
                <p className="muted">A validacao facial nao passou:</p>
                <VerificationChecklist result={punch.verification} />
              </>
            )}
            <div className="actions">
              <button type="button" className="btn primary" onClick={baterPonto}>
                <IconRefresh size={18} /> Bater ponto novamente
              </button>
              <Link to="/" className="btn ghost">Voltar ao inicio</Link>
            </div>
          </div>
        )}

        {today.length > 0 && (
          <section className="card">
            <h2>Registros de hoje</h2>
            <ul className="history">
              {today.map((entry, index) => (
                <li key={index} className="history-item">
                  <span className={`history-icon ${entry.type === 'ENTRADA' ? 'ok' : 'info'}`}>
                    <IconClock size={17} />
                  </span>
                  <div className="history-info">
                    <strong>{typeLabel(entry.type)}</strong>
                    <span className="muted">{hhmm(entry.at)}</span>
                  </div>
                  <div className="history-metrics">
                    <span>{(entry.similarity * 100).toFixed(0)}% similaridade</span>
                  </div>
                </li>
              ))}
            </ul>
          </section>
        )}
      </main>
    </div>
  );
}
