import React from 'react';
import { FolderOpen, X, Trash2 } from 'lucide-react';

function TemplatesModal({
  show,
  onClose,
  templates,
  onLoadTemplate,
  onDeleteTemplate
}) {
  if (!show) return null;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-card" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '650px' }}>
        <div className="modal-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <FolderOpen size={18} style={{ color: 'var(--excel-green-light)' }} />
            <h3>Plantillas de Reportes Guardadas</h3>
          </div>
          <button className="modal-close-btn" onClick={onClose}><X size={16} /></button>
        </div>
        <div className="modal-body" style={{ maxHeight: '400px', overflowY: 'auto' }}>
          {templates.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '30px', color: 'var(--text-muted)' }}>
              No hay plantillas guardadas aún. Crea un reporte y guárdalo para reutilizarlo aquí.
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {templates.map(tpl => (
                <div
                  key={tpl.id}
                  className="cr-template-item"
                  onClick={() => onLoadTemplate(tpl)}
                >
                  <div style={{ flexGrow: 1 }}>
                    <h4 style={{ fontSize: '13px', color: 'var(--text-primary)', margin: 0 }}>{tpl.name}</h4>
                    {tpl.description && (
                      <p style={{ fontSize: '11.5px', color: 'var(--text-secondary)', margin: '3px 0 0 0' }}>{tpl.description}</p>
                    )}
                    <span style={{ fontSize: '10.5px', color: 'var(--text-muted)', marginTop: '4px', display: 'inline-block' }}>
                      Actualizado: {tpl.updatedAt ? new Date(tpl.updatedAt).toLocaleString() : 'N/A'}
                    </span>
                  </div>
                  <div style={{ display: 'flex', gap: '6px', alignItems: 'center' }}>
                    <button
                      className="excel-btn primary"
                      style={{ fontSize: '11px', padding: '3px 10px' }}
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
