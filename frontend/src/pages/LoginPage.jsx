import { useState } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../auth.jsx';
import { getServerUrl, isNativeApp, setServerUrl } from '../api.js';

export default function LoginPage() {
  const { user, login, register } = useAuth();
  const [mode, setMode] = useState('login');
  const [name, setName] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [server, setServer] = useState(getServerUrl());
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  if (user) return <Navigate to="/" replace />;

  async function handleSubmit(event) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      // No aplicativo, o endereco do servidor e salvo antes de autenticar
      if (isNativeApp()) {
        if (!/^https?:\/\/.+/.test(server.trim())) {
          throw new Error('Informe o endereco do servidor, ex.: http://192.168.0.10:8080');
        }
        setServerUrl(server);
      }
      if (mode === 'login') {
        await login(username, password);
      } else {
        await register(name, username, password);
      }
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page center">
      <div className="card auth-card">
        <div className="brand">
          <span className="brand-mark">U</span>
          <div>
            <h1>Ulisses</h1>
            <p className="auth-headline">Sua identidade, verificada pelo seu rosto.</p>
          </div>
        </div>

        <div className="tabs">
          <button
            type="button"
            className={mode === 'login' ? 'tab active' : 'tab'}
            onClick={() => setMode('login')}
          >
            Entrar
          </button>
          <button
            type="button"
            className={mode === 'register' ? 'tab active' : 'tab'}
            onClick={() => setMode('register')}
          >
            Criar conta
          </button>
        </div>

        <form onSubmit={handleSubmit} className="form">
          {isNativeApp() && (
            <label>
              Servidor Ulisses
              <input
                value={server}
                onChange={(e) => setServer(e.target.value)}
                placeholder="http://192.168.0.10:8080"
                required
                inputMode="url"
                autoCapitalize="none"
              />
            </label>
          )}
          {mode === 'register' && (
            <label>
              Nome
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Seu nome"
                required
                maxLength={120}
              />
            </label>
          )}
          <label>
            Usuario
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="ex: jonnas"
              required
              minLength={3}
              maxLength={60}
              autoCapitalize="none"
              autoComplete="username"
            />
          </label>
          <label>
            Senha
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="minimo 6 caracteres"
              required
              minLength={6}
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            />
          </label>

          {error && <p className="error-text">{error}</p>}

          <button type="submit" className="btn primary" disabled={busy}>
            {busy ? 'Aguarde...' : mode === 'login' ? 'Entrar' : 'Criar conta'}
          </button>
        </form>
      </div>
    </div>
  );
}
