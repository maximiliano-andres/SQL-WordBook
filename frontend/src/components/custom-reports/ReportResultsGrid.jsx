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
            <span className="cr-stat-pill cr-stat-pill--accent">
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
            aria-pressed={isDistinct}
            title={isDistinct ? 'Filtrado de duplicados activo. Haz clic para restaurar todas las filas.' : 'Eliminar todas las filas duplicadas de este reporte.'}
          >
            <Sparkles size={14} className={isDistinct ? '' : 'text-yellow'} />
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
            <FileSpreadsheet size={36} className="text-muted" />
            <p>No hay datos disponibles para mostrar.</p>
            <button
              className="excel-btn primary"
              onClick={() => onExecuteQuery(1, limit)}
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
                    <th
                      key={col.label}
                      className={typeof reportData[0]?.[col.label] === 'number' ? 'th-numeric' : undefined}
                      title={`${col.tableAlias}.${col.column}`}
                    >
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
                      // Solo presentación: cifras alineadas a la derecha y, en Modo Fácil,
                      // los vacíos se muestran como "—" en lugar de la etiqueta técnica NULL.
                      const cellClass = isNull ? 'cr-cell-null' : (typeof val === 'number' ? 'cell-numeric' : '');
                      return (
                        <td key={col.label} className={cellClass} title={isNull && uxMode === 'simple' ? 'Sin dato' : undefined}>
                          {isNull
                            ? (uxMode === 'simple' ? '—' : <span className="null-tag">NULL</span>)
                            : String(val)}
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
        <div className="cr-pagination-group">
          <label className="cr-pagination-label" htmlFor="cr-page-size">Mostrar:</label>
          <select
            id="cr-page-size"
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
            Mostrando registros <strong>{totalRows === 0 ? 0 : ((page - 1) * limit) + 1}</strong> - <strong>{Math.min(page * limit, totalRows)}</strong> de un total de <strong className="text-accent">{totalRows.toLocaleString()}</strong> filas
          </span>
        </div>

        <div className="cr-pagination-group cr-pagination-pager">
          <button
            className="excel-btn"
            onClick={() => onExecuteQuery(page - 1, limit)}
            disabled={page <= 1 || isExecuting}
            aria-label="Página anterior"
          >
            <ChevronLeft size={16} />
            <span className="btn-label">Anterior</span>
          </button>
          <span className="cr-pagination-label">
            Pág. <strong>{page}</strong> de <strong>{totalPages || 1}</strong>
          </span>
          <button
            className="excel-btn"
            onClick={() => onExecuteQuery(page + 1, limit)}
            disabled={page >= totalPages || isExecuting}
            aria-label="Página siguiente"
          >
            <span className="btn-label">Siguiente</span>
            <ChevronRight size={16} />
          </button>
        </div>
      </div>
    </div>
  );
}

export default React.memo(ReportResultsGrid);
