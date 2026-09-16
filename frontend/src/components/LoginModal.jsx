import React, { useState } from 'react';
import { Lock, User, Eye, EyeOff, ShieldCheck, AlertCircle, ArrowRight } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { useModalA11y } from '../hooks/useModalA11y';

export default function LoginModal() {
  const { showLoginModal, setShowLoginModal, login, isAuthenticated } = useAuth();
  const { success } = useToast();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');

  // Escape solo cierra el modal cuando ya hay una sesión activa (re-login opcional); si el
  // login es obligatorio (usuario aún no autenticado) no hay una forma válida de descartarlo.
  const modalRef = useModalA11y(showLoginModal, isAuthenticated ? () => setShowLoginModal(false) : null);

  if (!showLoginModal) return null;

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!username.trim()) {
      setErrorMessage('Por favor ingrese su nombre de usuario.');
      return;
    }

    setIsLoading(true);
    setErrorMessage('');

    try {
      await login(username, password);
      success('Sesión iniciada correctamente', `Bienvenido, ${username.trim()}`);
    } catch (err) {
      setErrorMessage(err.message || 'Credenciales no válidas. Verifique su usuario y contraseña.');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="login-modal-overlay">
      <div
        ref={modalRef}
        className="login-modal-card"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="login-modal-title"
        tabIndex={-1}
      >
        {/* Encabezado con Logo y Branding */}
        <div className="login-modal-header">
          <div className="login-logo-wrapper">
            <img src="/logo.png" alt="Logo" className="login-app-logo" />
            <div className="login-shield-badge">
              <ShieldCheck size={16} />
            </div>
          </div>
          <h2 className="login-title" id="login-modal-title">Acceso al Sistema</h2>
          <p className="login-subtitle">SQL & Multi-Database Workbook</p>
        </div>

        {/* Mensaje de Error / Feedback */}
        {errorMessage && (
          <div className="login-error-banner animate-fade-in">
            <AlertCircle size={16} className="login-error-icon" />
            <span>{errorMessage}</span>
          </div>
        )}

        {/* Formulario de Login */}
        <form onSubmit={handleSubmit} className="login-form">
          <div className="login-field-group">
            <label className="login-label">Usuario</label>
            <div className="login-input-wrapper">
              <User size={16} className="login-input-icon" />
              <input
                type="text"
                className="login-input"
                placeholder="Ej. admin o usuario_api"
                value={username}
                onChange={(e) => {
                  setUsername(e.target.value);
                  if (errorMessage) setErrorMessage('');
                }}
                disabled={isLoading}
                autoFocus
                required
              />
            </div>
          </div>

          <div className="login-field-group">
            <label className="login-label">Contraseña</label>
            <div className="login-input-wrapper">
              <Lock size={16} className="login-input-icon" />
              <input
                type={showPassword ? 'text' : 'password'}
                className="login-input"
                placeholder="••••••••••••"
                value={password}
                onChange={(e) => {
                  setPassword(e.target.value);
                  if (errorMessage) setErrorMessage('');
                }}
                disabled={isLoading}
                required
              />
              <button
                type="button"
                className="login-toggle-pwd-btn"
                onClick={() => setShowPassword(!showPassword)}
                title={showPassword ? 'Ocultar contraseña' : 'Ver contraseña'}
                tabIndex={-1}
              >
                {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
          </div>

          {isAuthenticated && (
            <div className="login-options-row">
              <button
                type="button"
                className="login-cancel-btn"
                onClick={() => setShowLoginModal(false)}
                disabled={isLoading}
              >
                Cancelar
              </button>
            </div>
          )}

          <button
            type="submit"
            className="login-submit-btn"
            disabled={isLoading}
          >
            {isLoading ? (
              <>
                <span className="login-spinner"></span>
                <span>Verificando credenciales...</span>
              </>
            ) : (
              <>
                <span>Acceder</span>
                <ArrowRight size={16} />
              </>
            )}
          </button>
        </form>

        <div className="login-modal-footer">
          <p className="login-footer-text">
            Protegido con autenticación de sesión del servidor.
          </p>
        </div>
      </div>
    </div>
  );
}
