import React, { useState, useRef, useEffect } from 'react';
import {
  Lock, User, Eye, EyeOff, ShieldCheck, AlertCircle, ArrowRight,
  WifiOff, Clock, Info, KeyRound, MousePointerClick, LifeBuoy
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { useModalA11y } from '../hooks/useModalA11y';

// Indicación NO bloqueante: el campo es "usuario" (no correo), así que nunca se impide el envío;
// solo se orienta al usuario si escribió algo con "@" que no parece un correo válido.
const EMAIL_LIKE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// Traduce el resultado técnico del login a un mensaje comprensible, sin códigos HTTP ni
// detalles internos. El caso 401 es deliberadamente genérico: no revela si el usuario existe.
function describeLoginError(err) {
  const status = err?.status;
  if (status === undefined) {
    // fetch() rechaza (TypeError) cuando no hay respuesta: red caída o servidor apagado.
    return {
      kind: 'network',
      title: 'No pudimos conectar con el servidor',
      message: 'Revisa tu conexión a internet o vuelve a intentarlo en unos momentos.'
    };
  }
  if (status === 429) {
    return {
      kind: 'locked',
      title: 'Acceso bloqueado temporalmente',
      message: 'Se detectaron demasiados intentos fallidos. Por seguridad, espera unos minutos antes de volver a intentarlo.'
    };
  }
  if (status >= 500) {
    return {
      kind: 'network',
      title: 'El servicio no está disponible',
      message: 'El servidor no pudo procesar la solicitud. Inténtalo nuevamente en unos minutos.'
    };
  }
  return {
    kind: 'credentials',
    title: 'Usuario o contraseña incorrectos',
    message: 'Verifica tus datos e inténtalo de nuevo. Recuerda que la contraseña distingue mayúsculas y minúsculas.'
  };
}

const ALERT_ICONS = { network: WifiOff, locked: Clock, credentials: AlertCircle };

export default function LoginModal() {
  const { showLoginModal, setShowLoginModal, login, isAuthenticated, user } = useAuth();
  const { success } = useToast();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [alert, setAlert] = useState(null);           // { kind, title, message }
  const [fieldErrors, setFieldErrors] = useState({}); // { username?, password? }
  const [capsLockOn, setCapsLockOn] = useState(false);

  const usernameRef = useRef(null);
  const passwordRef = useRef(null);
  const focusPasswordAfterLoad = useRef(false);

  // Tras un intento fallido el foco vuelve a la contraseña, pero solo cuando el campo ya se
  // rehabilitó (un input deshabilitado no puede recibir foco).
  useEffect(() => {
    if (!isLoading && focusPasswordAfterLoad.current) {
      focusPasswordAfterLoad.current = false;
      passwordRef.current?.focus();
    }
  }, [isLoading]);

  // Escape solo cierra cuando ya hay una sesión activa (re-login opcional); si el login es
  // obligatorio (usuario aún no autenticado) no hay una forma válida de descartarlo.
  const modalRef = useModalA11y(showLoginModal, isAuthenticated ? () => setShowLoginModal(false) : null);

  // Sesión expirada con el usuario ya prellenado: llevar el foco directo a la contraseña
  // (se declara después de useModalA11y, así que prevalece sobre su foco inicial).
  const reopenedWithUser = showLoginModal && !isAuthenticated && !!user && !!username;
  useEffect(() => {
    if (reopenedWithUser) passwordRef.current?.focus();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [showLoginModal]);

  if (!showLoginModal) return null;

  // Hubo un usuario en esta sesión de la app pero el servidor dejó de reconocerla (401):
  // la sesión expiró. Tras un logout explícito `user` es null y este aviso no aparece.
  const sessionExpired = !isAuthenticated && !!user;

  const trimmedUser = username.trim();
  const showEmailHint = trimmedUser.includes('@') && !EMAIL_LIKE.test(trimmedUser);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (isLoading) return; // evita envíos duplicados

    const errors = {};
    if (!trimmedUser) errors.username = 'Ingresa tu nombre de usuario.';
    if (!password) errors.password = 'Ingresa tu contraseña.';
    setFieldErrors(errors);
    if (errors.username || errors.password) {
      setAlert(null);
      (errors.username ? usernameRef : passwordRef).current?.focus();
      return;
    }

    setIsLoading(true);
    setAlert(null);

    try {
      await login(username, password);
      setPassword('');
      setShowPassword(false);
      success('Sesión iniciada correctamente', `Bienvenido, ${trimmedUser}`);
    } catch (err) {
      setAlert(describeLoginError(err));
      setPassword('');
      focusPasswordAfterLoad.current = true;
    } finally {
      setIsLoading(false);
    }
  };

  const clearFieldError = (field) => {
    if (fieldErrors[field]) setFieldErrors(prev => ({ ...prev, [field]: undefined }));
    if (alert && alert.kind === 'credentials') setAlert(null);
  };

  const handlePasswordKey = (e) => {
    if (typeof e.getModifierState === 'function') setCapsLockOn(e.getModifierState('CapsLock'));
  };

  const AlertIcon = alert ? ALERT_ICONS[alert.kind] : null;

  return (
    <div className="login-screen">
      {/* Panel de marca e instructivo (lateral en escritorio, al pie en móvil) */}
      <aside className="login-aside" aria-label="Información de acceso">
        <div className="login-aside-brand">
          <img src="/logo_max_andres_160.webp" alt="" className="login-aside-logo" width="44" height="66" />
          <div>
            <p className="login-aside-name">Multi-DB <span>Workbook</span></p>
            <p className="login-aside-tagline">Consulta, cruza y exporta tus datos de forma segura.</p>
          </div>
        </div>

        <div className="login-steps">
          <h2 className="login-steps-title">¿Cómo ingresar?</h2>
          <ol className="login-steps-list">
            <li>
              <span className="login-step-icon" aria-hidden="true"><User size={15} /></span>
              <span><strong>Escribe tu usuario</strong> tal como te lo entregó el administrador.</span>
            </li>
            <li>
              <span className="login-step-icon" aria-hidden="true"><KeyRound size={15} /></span>
              <span><strong>Ingresa tu contraseña.</strong> Usa el ícono del ojo para verificar lo que escribiste.</span>
            </li>
            <li>
              <span className="login-step-icon" aria-hidden="true"><MousePointerClick size={15} /></span>
              <span><strong>Presiona «Iniciar sesión»</strong> o la tecla Enter para entrar.</span>
            </li>
          </ol>
        </div>

        <p className="login-aside-security">
          <ShieldCheck size={15} aria-hidden="true" />
          <span>Consulta en modo solo lectura sobre tus datos de negocio. Tus credenciales nunca se guardan en este navegador.</span>
        </p>
      </aside>

      {/* Formulario */}
      <main className="login-main">
        <div
          ref={modalRef}
          className="login-panel"
          role="dialog"
          aria-modal="true"
          aria-labelledby="login-title"
          aria-describedby="login-desc"
          tabIndex={-1}
        >
          <div className="login-brand-compact">
            <img src="/max_andres_head_256.webp" alt="" className="login-brand-compact-logo" width="40" height="40" />
            <span className="login-brand-compact-name">Multi-DB <span>Workbook</span></span>
          </div>

          <header className="login-header">
            <h1 className="login-title" id="login-title">
              {sessionExpired ? 'Tu sesión ha expirado' : 'Bienvenido de nuevo'}
            </h1>
            <p className="login-subtitle" id="login-desc">
              Ingresa tus credenciales para acceder a la plataforma.
            </p>
          </header>

          <div className="login-alert-slot" aria-live="assertive">
            {alert ? (
              <div className={`login-alert login-alert--${alert.kind === 'credentials' ? 'error' : 'warning'}`} role="alert">
                <AlertIcon size={18} className="login-alert-icon" aria-hidden="true" />
                <div>
                  <p className="login-alert-title">{alert.title}</p>
                  <p className="login-alert-text">{alert.message}</p>
                </div>
              </div>
            ) : sessionExpired ? (
              <div className="login-alert login-alert--info" role="status">
                <Info size={18} className="login-alert-icon" aria-hidden="true" />
                <div>
                  <p className="login-alert-title">Por seguridad, vuelve a iniciar sesión</p>
                  <p className="login-alert-text">Tu sesión dejó de ser válida por inactividad o por seguridad. Ingresa nuevamente para continuar.</p>
                </div>
              </div>
            ) : null}
          </div>

          <form onSubmit={handleSubmit} className="login-form" noValidate aria-busy={isLoading}>
            <div className="login-field">
              <label className="login-label" htmlFor="login-username">
                Usuario <span className="login-required" aria-hidden="true">*</span>
              </label>
              <div className={`login-input-wrap ${fieldErrors.username ? 'has-error' : ''}`}>
                <User size={16} className="login-input-icon" aria-hidden="true" />
                <input
                  ref={usernameRef}
                  id="login-username"
                  name="username"
                  type="text"
                  className="login-input"
                  placeholder="Ej. nombre.apellido"
                  value={username}
                  onChange={(e) => { setUsername(e.target.value); clearFieldError('username'); }}
                  disabled={isLoading}
                  autoComplete="username"
                  autoCapitalize="none"
                  autoCorrect="off"
                  spellCheck={false}
                  required
                  aria-required="true"
                  aria-invalid={!!fieldErrors.username}
                  aria-describedby={fieldErrors.username ? 'login-username-error' : (showEmailHint ? 'login-username-hint' : undefined)}
                />
              </div>
              {fieldErrors.username ? (
                <p className="login-field-error" id="login-username-error">
                  <AlertCircle size={13} aria-hidden="true" /> {fieldErrors.username}
                </p>
              ) : showEmailHint ? (
                <p className="login-field-hint" id="login-username-hint">
                  Si ingresas un correo, revisa el formato (ej. nombre@empresa.cl).
                </p>
              ) : null}
            </div>

            <div className="login-field">
              <label className="login-label" htmlFor="login-password">
                Contraseña <span className="login-required" aria-hidden="true">*</span>
              </label>
              <div className={`login-input-wrap ${fieldErrors.password ? 'has-error' : ''}`}>
                <Lock size={16} className="login-input-icon" aria-hidden="true" />
                <input
                  ref={passwordRef}
                  id="login-password"
                  name="password"
                  type={showPassword ? 'text' : 'password'}
                  className="login-input login-input--with-action"
                  placeholder="Tu contraseña"
                  value={password}
                  onChange={(e) => { setPassword(e.target.value); clearFieldError('password'); }}
                  onKeyUp={handlePasswordKey}
                  onKeyDown={handlePasswordKey}
                  onBlur={() => setCapsLockOn(false)}
                  disabled={isLoading}
                  autoComplete="current-password"
                  required
                  aria-required="true"
                  aria-invalid={!!fieldErrors.password}
                  aria-describedby={fieldErrors.password ? 'login-password-error' : (capsLockOn ? 'login-caps-hint' : undefined)}
                />
                <button
                  type="button"
                  className="login-pwd-toggle"
                  onClick={() => setShowPassword(v => !v)}
                  disabled={isLoading}
                  aria-label={showPassword ? 'Ocultar contraseña' : 'Mostrar contraseña'}
                  aria-pressed={showPassword}
                  aria-controls="login-password"
                  title={showPassword ? 'Ocultar contraseña' : 'Mostrar contraseña'}
                >
                  {showPassword ? <EyeOff size={17} /> : <Eye size={17} />}
                </button>
              </div>
              {fieldErrors.password ? (
                <p className="login-field-error" id="login-password-error">
                  <AlertCircle size={13} aria-hidden="true" /> {fieldErrors.password}
                </p>
              ) : capsLockOn ? (
                <p className="login-field-hint login-field-hint--warn" id="login-caps-hint">
                  Bloq Mayús está activado.
                </p>
              ) : null}
            </div>

            <button type="submit" className="login-submit" disabled={isLoading}>
              {isLoading ? (
                <>
                  <span className="login-spinner" aria-hidden="true"></span>
                  <span>Verificando…</span>
                </>
              ) : (
                <>
                  <span>Iniciar sesión</span>
                  <ArrowRight size={17} aria-hidden="true" />
                </>
              )}
            </button>

            {isAuthenticated && (
              <button
                type="button"
                className="login-secondary"
                onClick={() => setShowLoginModal(false)}
                disabled={isLoading}
              >
                Cancelar
              </button>
            )}
          </form>

          <p className="login-help">
            <LifeBuoy size={14} aria-hidden="true" />
            <span>¿Olvidaste tu contraseña o no puedes ingresar? Contacta al administrador de la plataforma.</span>
          </p>
        </div>

        <p className="login-legal">
          Uso exclusivo para personal autorizado.
        </p>
      </main>
    </div>
  );
}
