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

// El backend autentica con HTTP Basic solo en el POST de login (ver SecurityConfig): una vez
// válido, crea una sesión de servidor (cookie JSESSIONID httpOnly) y las peticiones siguientes
// viajan autenticadas por esa cookie automáticamente. Por eso el cliente NUNCA guarda usuario ni
// contraseña -ni en memoria más allá del propio submit, ni en localStorage/sessionStorage- (ver
// auditoría, hallazgos críticos F1/F2 del frontend).
export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [showLoginModal, setShowLoginModal] = useState(true);
  const [authChecking, setAuthChecking] = useState(true);

  // Wrapper universal de fetch: la cookie de sesión viaja automáticamente en same-origin,
  // solo nos encargamos de reaccionar a un 401 (sesión vencida o nunca iniciada).
  const apiFetch = useCallback(async (url, options = {}) => {
    const res = await fetch(url, options);
    if (res.status === 401) {
      setIsAuthenticated(false);
      setShowLoginModal(true);
    }
    return res;
  }, []);

  // Login: envía credenciales UNA sola vez vía Basic; el backend responde con Set-Cookie de
  // sesión y desde ahí no hace falta volver a enviarlas.
  const login = useCallback(async (username, password) => {
    const encoded = utf8ToBase64(`${username.trim()}:${password || ''}`);

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
    setUser({ username: username.trim() });
    setIsAuthenticated(true);
    setShowLoginModal(false);
    return data;
  }, []);

  // Logout: invalida la sesión en el servidor; el cliente no tiene ningún secreto propio que borrar.
  const logout = useCallback(async () => {
    try {
      await fetch('/api/auth/logout', { method: 'POST' });
    } catch {
      // Si la petición de logout falla por red, igual limpiamos el estado local.
    }
    setUser(null);
    setIsAuthenticated(false);
    setShowLoginModal(true);
  }, []);

  // Verificar estado de sesión al inicio (la cookie, si existe, viaja sola).
  useEffect(() => {
    async function verifyAuth() {
      try {
        const res = await fetch('/api/auth/status');
        if (res.ok) {
          const data = await res.json();
          if (data.authenticated) {
            setUser({ username: data.username });
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
        // Si el backend no responde de inmediato, dejamos el estado actual y mostramos el login.
        setShowLoginModal(true);
      }
      setAuthChecking(false);
    }
    verifyAuth();
  }, []);

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
    apiFetch
  }), [user, isAuthenticated, showLoginModal, authChecking, login, logout, apiFetch]);

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
