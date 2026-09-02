import React from 'react';
import {
  Play, Download, Code2, Copy, Check, Sparkles, FileSpreadsheet,
  ChevronLeft, ChevronRight
} from 'lucide-react';

// Vista "preview": tabla de resultados + visor SQL + paginación. Memoizado
// para que solo se re-renderice cuando cambien los datos/estado de ejecución,
// no cuando el usuario edita el constructor (paneles izquierdo/central/derecho).
function ReportResultsGrid({
  totalRows,
  executionTime,
  page,
  totalPages,
  isDistinct,
  onToggleDistinct,
  isExecuting,
  showSqlViewer,
  setShowSqlViewer,
  onExportCsv,
  isExportingExcel,
  onExportExcel,
  generatedSql,
  copiedSql,
  setCopiedSql,
  reportData,
  reportResultColumns,
  limit,
  setLimit,
  onExecuteQuery,
  uxMode = 'simple'
}) {
  return (
    <div className="cr-results-layout">
      {/* Barra de Acciones de Resultados */}
      <div className="cr-results-toolbar">
        <div className="cr-results-stats">
          <div className="cr-stat-total-card" title="Cantidad total de registros encontrados por la consulta">
            <span className="cr-stat-total-label">TOTAL DE FILAS:</span>
            <span className="cr-stat-total-num">{totalRows.toLocaleString()}</span>
          </div>
          {executionTime !== null && uxMode === 'advanced' && (
            <span className="cr-stat-pill">
              ⏱️ <strong>{executionTime} ms</strong>
            </span>
          )}
          <span className="cr-stat-pill">
            Página <strong>{page}</strong> de <strong>{totalPages || 1}</strong>
          </span>
          {isDistinct && (
            <span
              className="cr-stat-pill"
              style={{
                backgroundColor: 'rgba(16, 124, 65, 0.2)',
                color: 'var(--excel-green-light)',
                borderColor: 'rgba(16, 124, 65, 0.4)',
                fontWeight: 600
              }}
            >
              ✨ Sin Duplicados
            </span>
          )}
        </div>

        <div className="cr-results-actions">
          {/* Botón para Eliminar Duplicados del reporte */}
          <button
            className={`excel-btn ${isDistinct ? 'primary' : ''}`}
            onClick={onToggleDistinct}
            disabled={isExecuting}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              backgroundColor: isDistinct ? 'var(--excel-green)' : 'var(--excel-bg-app)',
              borderColor: isDistinct ? 'var(--excel-green)' : 'var(--excel-border)',
              color: isDistinct ? '#ffffff' : 'var(--text-primary)',
              fontWeight: isDistinct ? 600 : 500
            }}
            title={isDistinct ? 'Filtrado de duplicados activo. Haz clic para restaurar todas las filas.' : 'Eliminar todas las filas duplicadas de este reporte.'}
          >
            <Sparkles size={14} style={{ color: isDistinct ? '#ffffff' : 'var(--excel-yellow)' }} />
            <span>{isDistinct ? '✓ Sin Duplicados' : '🧹 Eliminar Duplicados'}</span>
          </button>

          {uxMode === 'advanced' && (
            <button
              className="excel-btn"
              onClick={() => setShowSqlViewer(!showSqlViewer)}
              title="Ver la consulta SQL Server generada"
            >
              <Code2 size={14} />
              <span>{showSqlViewer ? 'Ocultar SQL' : 'Ver SQL'}</span>
            </button>
          )}

          <button
            className="excel-btn"
            onClick={onExportCsv}
            disabled={reportData.length === 0}
            title="Exportar la vista de página actual en formato CSV"
          >
            <Download size={14} />
            <span>Exportar Vista (CSV)</span>
          </button>

          <button
            className="excel-btn primary"
            onClick={onExportExcel}
            disabled={isExportingExcel || reportData.length === 0}
            title="Exportar todas las filas del reporte a un archivo Excel (.xlsx)"
          >
            <Download size={14} className={isExportingExcel ? 'animate-spin' : ''} />
            <span>{isExportingExcel ? 'Generando .xlsx...' : 'Descargar Excel (.xlsx)'}</span>
          </button>
        </div>
      </div>

      {/* Visor de Consulta SQL */}
      {showSqlViewer && generatedSql && (
        <div className="cr-sql-viewer">
          <div className="cr-sql-header">
            <span>Consulta SQL Server generada (Auditable):</span>
            <button
              className="excel-btn excel-btn--xs"
              onClick={() => {
                navigator.clipboard.writeText(generatedSql);
                setCopiedSql(true);
                setTimeout(() => setCopiedSql(false), 2000);
              }}
            >
              {copiedSql ? <Check size={12} /> : <Copy size={12} />}
              <span>{copiedSql ? 'Copiado' : 'Copiar SQL'}</span>
            </button>
          </div>
          <pre className="cr-sql-code">{generatedSql}</pre>
        </div>
      )}

      {/* Grilla de Resultados */}
      <div className="cr-spreadsheet-container">
        {reportData.length === 0 ? (
          <div className="cr-empty-results">
            <FileSpreadsheet size={36} style={{ color: 'var(--text-muted)' }} />
            <p>No hay datos disponibles para mostrar.</p>
            <button
              className="excel-btn primary"
              onClick={() => onExecuteQuery(1, limit)}
              style={{ marginTop: '12px' }}
            >
              <Play size={14} />
              <span>Ejecutar Consulta</span>
            </button>
          </div>
        ) : (
          <div className="cr-table-scroll-wrapper">
            <table className="cr-data-table">
              <thead>
                <tr>
                  <th className="cr-th-row-num">#</th>
                  {reportResultColumns.map(col => (
                    <th key={col.label} title={`${col.tableAlias}.${col.column}`}>
                      {col.label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {reportData.map((row, rIdx) => (
                  <tr key={rIdx}>
                    <td className="cr-td-row-num">{(page - 1) * limit + rIdx + 1}</td>
                    {reportResultColumns.map(col => {
                      const val = row[col.label];
                      const isNull = val === null || val === undefined;
                      return (
                        <td key={col.label} className={isNull ? 'cr-cell-null' : ''}>
                          {isNull ? <span className="null-tag">NULL</span> : String(val)}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Paginación Inferior */}
      <div className="cr-pagination-bar">
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>Mostrar:</span>
          <select
            className="excel-select"
            value={limit}
            onChange={(e) => {
              const newLimit = Number(e.target.value);
              setLimit(newLimit);
              onExecuteQuery(1, newLimit);
            }}
          >
            <option value={15}>15 filas</option>
            <option value={30}>30 filas</option>
            <option value={50}>50 filas</option>
            <option value={100}>100 filas</option>
          </select>
        </div>

        <div className="cr-pagination-range">
          <span>
            Mostrando registros <strong>{totalRows === 0 ? 0 : ((page - 1) * limit) + 1}</strong> - <strong>{Math.min(page * limit, totalRows)}</strong> de un total de <strong style={{ color: 'var(--excel-green-light)' }}>{totalRows.toLocaleString()}</strong> filas
          </span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <button
            className="excel-btn"
            onClick={() => onExecuteQuery(page - 1, limit)}
            disabled={page <= 1 || isExecuting}
          >
            <ChevronLeft size={16} />
            <span>Anterior</span>
          </button>
          <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
            Pág. <strong>{page}</strong> de <strong>{totalPages || 1}</strong>
          </span>
          <button
            className="excel-btn"
            onClick={() => onExecuteQuery(page + 1, limit)}
            disabled={page >= totalPages || isExecuting}
          >
            <span>Siguiente</span>
            <ChevronRight size={16} />
          </button>
        </div>
      </div>
    </div>
  );
}

export default React.memo(ReportResultsGrid);
