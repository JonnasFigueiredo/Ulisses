import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth.jsx';
import LoginPage from './pages/LoginPage.jsx';
import HomePage from './pages/HomePage.jsx';
import EnrollPage from './pages/EnrollPage.jsx';
import VerifyPage from './pages/VerifyPage.jsx';
import PontoPage from './pages/PontoPage.jsx';

function Protected({ children }) {
  const { user, loading } = useAuth();
  if (loading) {
    return (
      <div className="page center">
        <div className="spinner" />
      </div>
    );
  }
  return user ? children : <Navigate to="/login" replace />;
}

export default function App() {
  const { loading, sessionError, retrySession, logout } = useAuth();
  if (loading) {
    return <main className="page center" role="status"><div className="spinner" /><p>Restaurando sessao...</p></main>;
  }
  if (sessionError) {
    return (
      <main className="page center">
        <section className="card auth-card">
          <h1>Nao foi possivel restaurar a sessao</h1>
          <p role="alert">{sessionError}</p>
          <div className="actions">
            <button type="button" className="btn primary" onClick={retrySession}>Tentar novamente</button>
            <button type="button" className="btn ghost" onClick={logout}>Voltar ao login</button>
          </div>
        </section>
      </main>
    );
  }
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <Protected>
            <HomePage />
          </Protected>
        }
      />
      <Route
        path="/enroll"
        element={
          <Protected>
            <EnrollPage />
          </Protected>
        }
      />
      <Route
        path="/verify"
        element={
          <Protected>
            <VerifyPage />
          </Protected>
        }
      />
      <Route
        path="/ponto"
        element={
          <Protected>
            <PontoPage />
          </Protected>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
