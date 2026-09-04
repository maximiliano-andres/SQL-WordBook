import React, { createContext, useContext, useState, useEffect, useCallback, useMemo } from 'react';

const AuthContext = createContext(null);

// Helper para codificar en Base64 de forma segura con soporte completo de UTF-8 (tildes, ñ, símbolos)
function utf8ToBase64(str) {
  try {
    return btoa(encodeURIComponent(str).replace(/%([0-9A-F]{2})/g, (match, p1) => {
      return String.fromCharCode(parseInt(p1, 16));
    }));
  } catch {
    return btoa(str);
  }
}

export function AuthProvider({ children }) {
  const [credentials, setCredentials] = useState(() => {
    try {
      const saved = localStorage.getItem('pushdb_auth') || sessionStorage.getItem('pushdb_auth');
      return saved ? JSON.parse(saved) : null;
    } catch {
      return null;
    }
  });

  const [user, setUser] = useState(credentials ? { username: credentials.username } : null);
  const [isAuthenticated, setIsAuthenticated] = useState(!!credentials);
  const [showLoginModal, setShowLoginModal] = useState(!credentials);
  const [authChecking, setAuthChecking] = useState(true);

  // Helper para generar cabecera Authorization Basic
  const getAuthHeader = useCallback((creds = credentials) => {
    if (!creds || !creds.username) return {};
    const encoded = utf8ToBase64(`${creds.username}:${creds.password || ''}`);
    return { 'Authorization': `Basic ${encoded}` };
  }, [credentials]);

  // Wrapper universal de fetch que adjunta credenciales y captura 401
  const apiFetch = useCallback(async (url, options = {}) => {
    const headers = {
      ...getAuthHeader(),
      ...(options.headers || {})
    };

    try {
      const res = await fetch(url, { ...options, headers });
      if (res.status === 401) {
        setIsAuthenticated(false);
        setShowLoginModal(true);
      }
      return res;
    } catch (err) {
      throw err;
    }
  }, [getAuthHeader]);

  // Login
  const login = useCallback(async (username, password, remember = true) => {
    const creds = { username: username.trim(), password };
    const encoded = utf8ToBase64(`${creds.username}:${creds.password || ''}`);

    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: {
        'Authorization': `Basic ${encoded}`,
        'Content-Type': 'application/json'
      }
    });

    if (!res.ok) {
      const errData = await res.json().catch(() => ({}));
      throw new Error(errData.error || 'Usuario o contraseña incorrectos.');
    }

    const data = await res.json();
    setCredentials(creds);
    setUser({ username: creds.username });
    setIsAuthenticated(true);
    setShowLoginModal(false);

    if (remember) {
      localStorage.setItem('pushdb_auth', JSON.stringify(creds));
      sessionStorage.removeItem('pushdb_auth');
    } else {
      sessionStorage.setItem('pushdb_auth', JSON.stringify(creds));
      localStorage.removeItem('pushdb_auth');
    }

    return data;
  }, []);

  // Logout
  const logout = useCallback(() => {
    localStorage.removeItem('pushdb_auth');
    sessionStorage.removeItem('pushdb_auth');
    setCredentials(null);
    setUser(null);
    setIsAuthenticated(false);
    setShowLoginModal(true);
  }, []);

  // Verificar estado de sesión al inicio
  useEffect(() => {
    async function verifyAuth() {
      if (credentials) {
        try {
          const res = await apiFetch('/api/auth/status');
          if (res.ok) {
            const data = await res.json();
            if (data.authenticated) {
              setIsAuthenticated(true);
              setShowLoginModal(false);
            } else {
              setIsAuthenticated(false);
              setShowLoginModal(true);
            }
          } else {
            setIsAuthenticated(false);
            setShowLoginModal(true);
          }
        } catch {
          // Si el backend no responde de inmediato, dejamos el estado actual
        }
      } else {
        setShowLoginModal(true);
      }
      setAuthChecking(false);
    }
    verifyAuth();
  }, [credentials, apiFetch]);

  // Sin useMemo, un objeto value nuevo en cada render de AuthProvider forzaría
  // el re-render de todo consumidor de useAuth() (Ribbon, Spreadsheet,
  // CustomReports, etc.) aunque los campos que leen no hayan cambiado.
  const value = useMemo(() => ({
    user,
    isAuthenticated,
    showLoginModal,
    setShowLoginModal,
    authChecking,
    login,
    logout,
    apiFetch,
    getAuthHeader
  }), [user, isAuthenticated, showLoginModal, authChecking, login, logout, apiFetch, getAuthHeader]);

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth debe utilizarse dentro de un AuthProvider');
  }
  return context;
}
