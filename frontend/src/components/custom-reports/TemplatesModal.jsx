import React from 'react';
import { FolderOpen, X, Trash2 } from 'lucide-react';
import { useModalA11y } from '../../hooks/useModalA11y';

function TemplatesModal({
  show,
  onClose,
  templates,
  onLoadTemplate,
  onDeleteTemplate
}) {
  const modalRef = useModalA11y(show, onClose);

  if (!show) return null;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        ref={modalRef}
        className="modal-card"
        onClick={(e) => e.stopPropagation()}
        style={{ '--modal-max-w': '650px' }}
        role="dialog"
        aria-modal="true"
        aria-labelledby="templates-modal-title"
        tabIndex={-1}
      >
        <div className="modal-header">
          <div className="modal-title-group">
            <FolderOpen size={18} className="excel-icon" />
            <h3 id="templates-modal-title">Plantillas de Reportes Guardadas</h3>
          </div>
          <button className="modal-close-btn" onClick={onClose} aria-label="Cerrar"><X size={16} /></button>
        </div>
        <div className="modal-body">
          {templates.length === 0 ? (
            <div className="modal-empty">
              No hay plantillas guardadas aún. Crea un reporte y guárdalo para reutilizarlo aquí.
            </div>
          ) : (
            <div className="cr-template-list">
              {templates.map(tpl => (
                <div
                  key={tpl.id}
                  className="cr-template-item"
                  onClick={() => onLoadTemplate(tpl)}
                >
                  <div className="cr-template-info">
                    <h4 className="cr-template-name">{tpl.name}</h4>
                    {tpl.description && (
                      <p className="cr-template-desc">{tpl.description}</p>
                    )}
                    <span className="cr-template-date">
                      Actualizado: {tpl.updatedAt ? new Date(tpl.updatedAt).toLocaleString() : 'N/A'}
                    </span>
                  </div>
                  <div className="cr-template-actions">
                    <button
                      className="excel-btn primary excel-btn--compact"
                      onClick={(e) => {
                        e.stopPropagation();
                        onLoadTemplate(tpl);
                      }}
                    >
                      Cargar
                    </button>
                    <button
                      className="cr-icon-btn danger"
                      onClick={(e) => onDeleteTemplate(tpl.id, e)}
                      title="Eliminar plantilla"
                      aria-label="Eliminar plantilla"
                    >
                      <Trash2 size={13} />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
        <div className="modal-footer">
          <button className="excel-btn" onClick={onClose}>Cerrar</button>
        </div>
      </div>
    </div>
  );
}

export default React.memo(TemplatesModal);
