import React from 'react';

export default function StatusBar({ 
  activeTable, 
  totalRows, 
  limit, 
  offset, 
  responseTime 
}) {
  const startRow = totalRows === 0 ? 0 : offset + 1;
  const endRow = Math.min(offset + limit, totalRows);

  return (
    <footer className="status-bar">
      <div className="status-left">
        <span>Listo</span>
        {activeTable && (
          <>
            <span style={{ color: 'rgba(255, 255, 255, 0.5)' }}>|</span>
            <span>
              Hoja Activa: <strong>{activeTable.schema}.{activeTable.name}</strong>
            </span>
          </>
        )}
      </div>

      <div className="status-right">
        {activeTable && (
          <>
            <span>
              Filas: <strong className="status-metric">{startRow.toLocaleString()} - {endRow.toLocaleString()}</strong> de <strong className="status-metric">{totalRows.toLocaleString()}</strong>
            </span>
            {responseTime !== null && (
              <>
                <span style={{ color: 'rgba(255, 255, 255, 0.5)' }}>|</span>
                <span>
                  Tiempo de Consulta: <strong className="status-metric">{responseTime} ms</strong>
                </span>
              </>
            )}
          </>
        )}
        <span>Modo: Consulta Segura</span>
      </div>
    </footer>
  );
}
