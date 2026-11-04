import React from 'react';
import { Save, X } from 'lucide-react';
import { useModalA11y } from '../../hooks/useModalA11y';

function SaveTemplateModal({
  show,
  onClose,
  tempReportName,
  setTempReportName,
  tempReportDesc,
  setTempReportDesc,
  onSave
}) {
  const modalRef = useModalA11y(show, onClose);

  if (!show) return null;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        ref={modalRef}
        className="modal-card"
        onClick={(e) => e.stopPropagation()}
        style={{ '--modal-max-w': '460px' }}
        role="dialog"
        aria-modal="true"
        aria-labelledby="save-template-modal-title"
        tabIndex={-1}
      >
        <div className="modal-header">
          <div className="modal-title-group">
            <Save size={18} className="excel-icon" />
            <h3 id="save-template-modal-title">Guardar Plantilla de Reporte</h3>
          </div>
          <button className="modal-close-btn" onClick={onClose} aria-label="Cerrar"><X size={16} /></button>
        </div>
        <div className="modal-body modal-body--stack">
          <div>
            <label className="cr-label" htmlFor="save-template-name"><strong>Nombre del Reporte:</strong></label>
            <input
              id="save-template-name"
              type="text"
              className="excel-input form-control-block"
              value={tempReportName}
              onChange={(e) => setTempReportName(e.target.value)}
              placeholder="Ej. Ventas por Cliente y Ciudad"
            />
          </div>
          <div>
            <label className="cr-label" htmlFor="save-template-desc"><strong>Descripción (Opcional):</strong></label>
            <textarea
              id="save-template-desc"
              className="excel-input form-control-block form-textarea"
              value={tempReportDesc}
              onChange={(e) => setTempReportDesc(e.target.value)}
              placeholder="Descripción de qué tablas cruza y qué información entrega..."
            />
          </div>
        </div>
        <div className="modal-footer">
          <button className="excel-btn" onClick={onClose}>Cancelar</button>
          <button
            className="excel-btn primary"
            onClick={onSave}
          >
            Guardar en BD
          </button>
        </div>
      </div>
    </div>
  );
}

export default React.memo(SaveTemplateModal);
