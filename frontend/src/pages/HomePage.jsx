import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import {
  IconCamera,
  IconCheck,
  IconFaceScan,
  IconLogout,
  IconRefresh,
  IconShieldCheck,
  IconTrash,
  IconX,
} from '../components/Icons.jsx';

function formatDate(iso) {
  return new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' });
}

export default function HomePage() {
  const { user, logout } = useAuth();
  const [status, setStatus] = useState(null);
  const [history, setHistory] = useState([]);
  const [error, setError] = useState(null);

  async function refresh() {
    try {
      const [statusData, historyData] = await Promise.all([
        api('/api/face/status'),
        api('/api/face/history'),
      ]);
      setStatus(statusData);
      setHistory(historyData.attempts);
    } catch (e) {
      setError(e.message);
    }
  }

  useEffect(() => {
    refresh();
  }, []);

  async function handleDelete() {
    if (!window.confirm('Remover sua biometria cadastrada?')) return;
    try {
      const data = await api('/api/face/enrollment', { method: 'DELETE' });
      setStatus(data);
    } catch (e) {
      setError(e.message);
    }
  }

  const engineLoading = status && status.engineStatus === 'LOADING';

  return (
    <div className="page">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark">U</span>
          <h1>Ulisses</h1>
        </div>
        <div className="topbar-user">
          <span className="avatar">{user.name.trim().charAt(0).toUpperCase()}</span>
          <span className="muted">Ola, {user.name.split(' ')[0]}</span>
          <button type="button" className="btn ghost small" onClick={logout}>
            <IconLogout size={16} /> Sair
          </button>
        </div>
      </header>

      <main className="content">
        {error && <p className="error-text">{error}</p>}
        {engineLoading && (
          <div className="banner">
            Os modelos de reconhecimento facial estao sendo carregados no servidor (download unico no
            primeiro start). As acoes abaixo ficam disponiveis em instantes.
          </div>
        )}

        <section className="card">
          <div className="status-row">
            <div className="card-title">
              <span className={`icon-badge${status?.enrolled ? ' ok' : ''}`}>
                <IconShieldCheck size={22} />
              </span>
              <div>
                <h2>Biometria facial</h2>
                {status == null ? (
                  <p className="muted">Carregando...</p>
                ) : status.enrolled ? (
                  <p className="muted">
                    Cadastrada em {formatDate(status.enrolledAt)} · {status.templates} poses
                  </p>
                ) : (
                  <p className="muted">Nenhuma biometria cadastrada ainda.</p>
                )}
              </div>
            </div>
            {status && (
              <span className={`pill ${status.enrolled ? 'pill-ok' : 'pill-warn'}`}>
                {status.enrolled ? 'Ativa' : 'Pendente'}
              </span>
            )}
          </div>

          <div className="actions">
            <Link to="/enroll" className="btn primary">
              {status?.enrolled ? (
                <>
                  <IconRefresh size={18} /> Refazer cadastro facial
                </>
              ) : (
                <>
                  <IconCamera size={18} /> Cadastrar rosto por video
                </>
              )}
            </Link>
            {status?.enrolled && (
              <>
                <Link to="/verify" className="btn secondary">
                  <IconFaceScan size={18} /> Validar identidade
                </Link>
                <button type="button" className="btn danger-ghost" onClick={handleDelete}>
                  <IconTrash size={17} /> Remover biometria
                </button>
              </>
            )}
          </div>
        </section>

        {history.length > 0 && (
          <section className="card">
            <h2>Ultimas validacoes</h2>
            <ul className="history">
              {history.map((attempt, index) => (
                <li key={index} className="history-item">
                  <span className={`history-icon ${attempt.success ? 'ok' : 'fail'}`}>
                    {attempt.success ? <IconCheck size={18} /> : <IconX size={18} />}
                  </span>
                  <div className="history-info">
                    <strong>{attempt.success ? 'Identidade validada' : 'Validacao recusada'}</strong>
                    <span className="muted">{formatDate(attempt.createdAt)}</span>
                  </div>
                  <div className="history-metrics">
                    <span>similaridade {(attempt.similarity * 100).toFixed(1)}%</span>
                    <span>prova de vida {attempt.liveness ? 'ok' : 'falhou'}</span>
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
