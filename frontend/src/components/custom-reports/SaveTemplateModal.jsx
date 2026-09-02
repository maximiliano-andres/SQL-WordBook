import React from 'react';
import { Save, X } from 'lucide-react';

function SaveTemplateModal({
  show,
  onClose,
  tempReportName,
  setTempReportName,
  tempReportDesc,
  setTempReportDesc,
  onSave
}) {
  if (!show) return null;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-card" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '460px' }}>
        <div className="modal-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Save size={18} style={{ color: 'var(--excel-green-light)' }} />
            <h3>Guardar Plantilla de Reporte</h3>
          </div>
          <button className="modal-close-btn" onClick={onClose}><X size={16} /></button>
        </div>
        <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
          <div>
            <label className="cr-label"><strong>Nombre del Reporte:</strong></label>
            <input
              type="text"
              className="excel-input"
              style={{ width: '100%', marginTop: '4px' }}
              value={tempReportName}
              onChange={(e) => setTempReportName(e.target.value)}
              placeholder="Ej. Ventas por Cliente y Ciudad"
            />
          </div>
          <div>
            <label className="cr-label"><strong>Descripción (Opcional):</strong></label>
            <textarea
              className="excel-input"
              style={{ width: '100%', marginTop: '4px', minHeight: '70px', resize: 'vertical' }}
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
