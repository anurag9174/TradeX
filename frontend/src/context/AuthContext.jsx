import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { authApi } from '../api/auth.js';
import { apiError, TOKEN_KEY, UNAUTHORIZED_EVENT } from '../api/client.js';
import { useToast } from './ToastContext.jsx';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const toast = useToast();
  const [token, setToken] = useState(() => localStorage.getItem(TOKEN_KEY));
  const [user, setUser] = useState(null);
  const [initializing, setInitializing] = useState(Boolean(localStorage.getItem(TOKEN_KEY)));

  // Restore the session on load: the token is validated against /api/auth/me.
  useEffect(() => {
    let cancelled = false;
    if (!token) {
      setInitializing(false);
      setUser(null);
      return () => {
        cancelled = true;
      };
    }
    setInitializing(true);
    authApi
      .me()
      .then((profile) => {
        if (!cancelled) setUser(profile);
      })
      .catch(() => {
        if (!cancelled) {
          localStorage.removeItem(TOKEN_KEY);
          setToken(null);
          setUser(null);
        }
      })
      .finally(() => {
        if (!cancelled) setInitializing(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  // The API client fires this when any request is rejected with 401.
  useEffect(() => {
    const onUnauthorized = () => {
      setToken(null);
      setUser(null);
      toast.error('Your session expired. Please sign in again.', 'Signed out');
    };
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
  }, [toast]);

  const applySession = useCallback((response) => {
    localStorage.setItem(TOKEN_KEY, response.accessToken);
    setToken(response.accessToken);
    setUser(response.user || null);
    return response;
  }, []);

  const login = useCallback(
    async (identifier, password) => {
      try {
        const response = await authApi.login({ identifier, password });
        return applySession(response);
      } catch (error) {
        throw apiError(error);
      }
    },
    [applySession],
  );

  const register = useCallback(
    async (payload) => {
      try {
        const response = await authApi.register(payload);
        return applySession(response);
      } catch (error) {
        throw apiError(error);
      }
    },
    [applySession],
  );

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch {
      // Logging out locally is what matters; the token simply expires server-side.
    }
    localStorage.removeItem(TOKEN_KEY);
    setToken(null);
    setUser(null);
  }, []);

  const value = useMemo(
    () => ({ token, user, initializing, isAuthenticated: Boolean(token && user), login, register, logout }),
    [token, user, initializing, login, register, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
}
