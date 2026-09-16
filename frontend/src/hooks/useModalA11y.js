import { useEffect, useRef } from 'react';

const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

function getFocusableElements(container) {
  if (!container) return [];
  return Array.from(container.querySelectorAll(FOCUSABLE_SELECTOR));
}

/**
 * Accesibilidad de teclado para modales (ver auditoría frontend, hallazgo: ningún modal tenía
 * role/aria, manejo de Escape ni foco atrapado). Al abrir: enfoca el primer elemento
 * interactivo; mientras está abierto: Escape cierra (si se pasa onClose) y Tab/Shift+Tab
 * quedan atrapados dentro del modal en vez de escapar hacia la página de fondo.
 *
 * @param {boolean} isOpen
 * @param {(() => void) | null | undefined} onClose - si es null/undefined, Escape no hace nada
 *   (para modales sin una forma válida de descartarse, como el login obligatorio inicial).
 */
export function useModalA11y(isOpen, onClose) {
  const containerRef = useRef(null);

  useEffect(() => {
    if (!isOpen) return undefined;
    const container = containerRef.current;
    if (!container) return undefined;

    const focusable = getFocusableElements(container);
    (focusable[0] || container).focus();

    function handleKeyDown(e) {
      if (e.key === 'Escape') {
        if (onClose) onClose();
        return;
      }
      if (e.key === 'Tab') {
        const items = getFocusableElements(container);
        if (items.length === 0) return;
        const first = items[0];
        const last = items[items.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    }

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  return containerRef;
}
