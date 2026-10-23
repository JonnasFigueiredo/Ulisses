import { createContext, useContext, useEffect, useState, useCallback } from 'react';
import { api, getToken, setToken, clearToken, AUTH_EXPIRED_EVENT } from './api.js';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);
  const [sessionError, setSessionError] = useState(null);

  const restoreSession = useCallback(async (signal) => {
    setLoading(true);
    setSessionError(null);
    const token = getToken();
    try {
      if (!token) return;
      const restoredUser = await api('/api/auth/me', { signal });
      if (!signal?.aborted && token === getToken()) setUser(restoredUser);
    } catch (error) {
      if (signal?.aborted || token !== getToken()) return;
      setSessionError(error.message);
    } finally {
      if (!signal?.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const expireSession = () => {
      setUser(null);
      setSessionError(null);
    };
    window.addEventListener(AUTH_EXPIRED_EVENT, expireSession);
    restoreSession(controller.signal);
    return () => {
      controller.abort();
      window.removeEventListener(AUTH_EXPIRED_EVENT, expireSession);
    };
  }, [restoreSession]);

  const login = useCallback(async (username, password) => {
    const data = await api('/api/auth/login', { method: 'POST', body: { username, password }, authenticated: false });
    setToken(data.token);
    setUser(data.user);
  }, []);

  const register = useCallback(async (name, username, password) => {
    const data = await api('/api/auth/register', {
      method: 'POST',
      body: { name, username, password },
      authenticated: false,
    });
    setToken(data.token);
    setUser(data.user);
  }, []);

  const logout = useCallback(() => {
    clearToken();
    setUser(null);
    setSessionError(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, loading, sessionError, retrySession: () => restoreSession(), login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
