import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import { AuthProvider, useAuth } from './AuthContext';

function Probe() {
  const { user, isAuthenticated, authChecking, login, logout } = useAuth();
  return (
    <div>
      <span data-testid="checking">{String(authChecking)}</span>
      <span data-testid="authenticated">{String(isAuthenticated)}</span>
      <span data-testid="username">{user?.username || ''}</span>
      <button onClick={() => login('admin', 'secret').catch(() => {})}>login</button>
      <button onClick={() => logout()}>logout</button>
    </div>
  );
}

describe('AuthContext', () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // Hallazgo crítico de auditoría: el cliente guardaba usuario/contraseña en localStorage
  // (Base64, no cifrado). Con sesión por cookie del servidor, el cliente no debe guardar
  // ningún secreto propio en ningún momento del ciclo de vida.
  it('nunca escribe credenciales en localStorage ni sessionStorage tras un login exitoso', async () => {
    fetch.mockResolvedValueOnce({ ok: true, json: async () => ({ authenticated: false }) }); // status check al montar
    fetch.mockResolvedValueOnce({ ok: true, json: async () => ({ success: true, authenticated: true, username: 'admin' }) }); // login

    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByTestId('checking').textContent).toBe('false'));

    await act(async () => {
      screen.getByText('login').click();
    });

    await waitFor(() => expect(screen.getByTestId('authenticated').textContent).toBe('true'));
    expect(screen.getByTestId('username').textContent).toBe('admin');

    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });

  it('un login fallido no autentica y no guarda nada', async () => {
    fetch.mockResolvedValueOnce({ ok: true, json: async () => ({ authenticated: false }) });
    fetch.mockResolvedValueOnce({ ok: false, json: async () => ({ error: 'Credenciales no válidas' }) });

    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByTestId('checking').textContent).toBe('false'));

    await act(async () => {
      screen.getByText('login').click();
    });

    await waitFor(() => expect(screen.getByTestId('authenticated').textContent).toBe('false'));
    expect(window.localStorage.length).toBe(0);
  });

  it('logout limpia el estado local y llama a /api/auth/logout', async () => {
    fetch.mockResolvedValueOnce({ ok: true, json: async () => ({ authenticated: true, username: 'admin' }) });
    fetch.mockResolvedValueOnce({ ok: true, json: async () => ({ success: true }) });

    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(screen.getByTestId('authenticated').textContent).toBe('true'));

    await act(async () => {
      screen.getByText('logout').click();
    });

    await waitFor(() => expect(screen.getByTestId('authenticated').textContent).toBe('false'));
    expect(fetch).toHaveBeenLastCalledWith('/api/auth/logout', { method: 'POST' });
  });

  it('al montar, consulta /api/auth/status y respeta si ya hay sesión activa por cookie', async () => {
    fetch.mockResolvedValueOnce({ ok: true, json: async () => ({ authenticated: true, username: 'ana' }) });

    render(<AuthProvider><Probe /></AuthProvider>);

    await waitFor(() => expect(screen.getByTestId('authenticated').textContent).toBe('true'));
    expect(screen.getByTestId('username').textContent).toBe('ana');
  });
});
