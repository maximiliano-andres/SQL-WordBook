import React, { createContext, useContext, useState, useCallback } from 'react';
import {
  CheckCircle2, AlertTriangle, XCircle, Info, HelpCircle, X
} from 'lucide-react';

const ToastContext = createContext(null);

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const [confirmModal, setConfirmModal] = useState(null); // { title, message, confirmText, cancelText, onConfirm, onCancel, type }

  // Remover un toast por ID
  const removeToast = useCallback((id) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  // Añadir un nuevo toast
  const addToast = useCallback((type, message, title = '', duration = 4000) => {
    const id = Date.now() + Math.random().toString(36).substring(2, 6);
    const newToast = { id, type, message, title, duration };

    setToasts((prev) => [...prev, newToast]);

    if (duration > 0) {
      setTimeout(() => {
        removeToast(id);
      }, duration);
    }
  }, [removeToast]);

  const success = useCallback((message, title = '¡Éxito!') => {
    addToast('success', message, title, 3500);
  }, [addToast]);

  const error = useCallback((message, title = 'Atención') => {
    addToast('error', message, title, 5000);
  }, [addToast]);

  const warning = useCallback((message, title = 'Aviso') => {
    addToast('warning', message, title, 4000);
  }, [addToast]);

  const info = useCallback((message, title = 'Información') => {
    addToast('info', message, title, 3500);
  }, [addToast]);

  // Modal de confirmación personalizado (reemplazo de window.confirm)
  const confirm = useCallback(({
    title = '¿Estás seguro?',
    message = 'Esta acción no se puede deshacer.',
    confirmText = 'Confirmar',
    cancelText = 'Cancelar',
    type = 'danger',
    onConfirm = () => {},
    onCancel = () => {}
  }) => {
    setConfirmModal({
      title,
      message,
      confirmText,
      cancelText,
      type,
      onConfirm: () => {
        setConfirmModal(null);
        onConfirm();
      },
      onCancel: () => {
        setConfirmModal(null);
        onCancel();
      }
    });
  }, []);

  return (
    <ToastContext.Provider value={{ success, error, warning, info, confirm }}>
      {children}

      {/* Contenedor de Toasts Flotantes */}
      <div className="toast-container" aria-live="polite">
        {toasts.map((toast) => {
          let Icon = Info;
          let iconClass = 'toast-icon-info';
          if (toast.type === 'success') {
            Icon = CheckCircle2;
            iconClass = 'toast-icon-success';
          } else if (toast.type === 'error') {
            Icon = XCircle;
            iconClass = 'toast-icon-error';
          } else if (toast.type === 'warning') {
            Icon = AlertTriangle;
            iconClass = 'toast-icon-warning';
          }

          return (
            <div key={toast.id} className={`toast-card toast-${toast.type}`}>
              <div className={`toast-icon-wrapper ${iconClass}`}>
                <Icon size={18} />
              </div>
              <div className="toast-content">
                {toast.title && <h4 className="toast-title">{toast.title}</h4>}
                <p className="toast-message">{toast.message}</p>
              </div>
              <button
                className="toast-close-btn"
                onClick={() => removeToast(toast.id)}
                title="Cerrar notificación"
              >
                <X size={14} />
              </button>
            </div>
          );
        })}
      </div>

      {/* Modal de Confirmación Corporativo */}
      {confirmModal && (
        <div className="modal-backdrop" onClick={confirmModal.onCancel}>
          <div
            className="modal-card confirm-modal-card"
            onClick={(e) => e.stopPropagation()}
            style={{ maxWidth: '440px' }}
          >
            <div className="modal-header">
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <HelpCircle size={18} style={{ color: 'var(--excel-green-light)' }} />
                <h3>{confirmModal.title}</h3>
              </div>
              <button className="modal-close" onClick={confirmModal.onCancel}>✕</button>
            </div>
            <div className="modal-body">
              <p style={{ fontSize: '13px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
                {confirmModal.message}
              </p>
            </div>
            <div className="modal-footer" style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
              <button className="excel-btn" onClick={confirmModal.onCancel}>
                {confirmModal.cancelText}
              </button>
              <button
                className={`excel-btn ${confirmModal.type === 'danger' ? 'danger-btn' : 'primary'}`}
                onClick={confirmModal.onConfirm}
                style={{
                  backgroundColor: confirmModal.type === 'danger' ? '#d9534f' : 'var(--excel-green)',
                  borderColor: confirmModal.type === 'danger' ? '#d9534f' : 'var(--excel-green)',
                  color: '#ffffff',
                  fontWeight: 600
                }}
              >
                {confirmModal.confirmText}
              </button>
            </div>
          </div>
        </div>
      )}
    </ToastContext.Provider>
  );
}

export function useToast() {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast debe ser usado dentro de un ToastProvider');
  }
  return context;
}
