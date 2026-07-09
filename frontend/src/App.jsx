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
