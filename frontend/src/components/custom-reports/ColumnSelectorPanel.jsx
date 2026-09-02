import React from 'react';
import { Info } from 'lucide-react';

// PANEL CENTRAL del constructor de reportes: pestañas de tabla, buscador,
// checkboxes de columna y alias. Memoizado para no re-renderizar mientras se
// editan filtros/ordenamiento en el panel derecho.
function ColumnSelectorPanel({
  participatingTables,
  selectedColumns,
  columnsCache,
  activeColumnTable,
  setActiveColumnTable,
  columnSearchQuery,
  setColumnSearchQuery,
  onSelectAllColumnsOfTable,
  onDeselectAllColumnsOfTable,
  onToggleColumn,
  onUpdateColumnLabel,
  uxMode = 'simple'
}) {
  return (
    <div className="cr-panel-card cr-columns-panel">
      <div className="cr-card-header">
        <div className="cr-card-title">
          <span className="cr-step-number">2</span>
          <h3>{uxMode === 'simple' ? `Campos para tu Excel (${selectedColumns.length})` : `Columnas del Reporte (${selectedColumns.length})`}</h3>
        </div>
      </div>

      {participatingTables.length === 0 ? (
        <div className="cr-empty-placeholder">
          <Info size={24} style={{ color: 'var(--text-muted)' }} />
          <p>{uxMode === 'simple' ? 'Elige primero tu tabla de inicio en el Paso 1.' : 'Selecciona primero una tabla principal en el paso 1.'}</p>
        </div>
      ) : (
        <div className="cr-columns-container">
          {/* Selector de Tabla para examinar columnas */}
          <div className="cr-table-tabs-bar">
            {participatingTables.map(t => {
              const countSelected = selectedColumns.filter(c => c.tableAlias === t.alias).length;
              return (
                <button
                  key={t.alias}
                  className={`cr-table-tab ${activeColumnTable === t.alias ? 'active' : ''}`}
                  onClick={() => setActiveColumnTable(t.alias)}
                >
                  <span>{t.name}</span>
                  {countSelected > 0 && <span className="cr-col-badge">{countSelected}</span>}
                </button>
              );
            })}
          </div>

          {/* Barra de búsqueda y acciones rápidas */}
          <div className="cr-columns-filter-bar">
            <input
              type="text"
              className="excel-input cr-search-input"
              placeholder={uxMode === 'simple' ? 'Buscar campo (ej: rut, nombre, monto)...' : 'Buscar columna...'}
              value={columnSearchQuery}
              onChange={(e) => setColumnSearchQuery(e.target.value)}
            />
            <div style={{ display: 'flex', gap: '6px' }}>
              <button
                className="excel-btn excel-btn--xs"
                onClick={() => onSelectAllColumnsOfTable(activeColumnTable)}
              >
                Marcar todas
              </button>
              <button
                className="excel-btn excel-btn--xs"
                onClick={() => onDeselectAllColumnsOfTable(activeColumnTable)}
              >
                Desmarcar
              </button>
            </div>
          </div>

          {/* Lista de Columnas de la tabla seleccionada */}
          <div className="cr-columns-grid">
            {(() => {
              const curTable = participatingTables.find(t => t.alias === activeColumnTable);
              if (!curTable) return null;
              const cols = columnsCache[`${curTable.schema}.${curTable.name}`] || [];
              const filtered = cols.filter(c => c.name.toLowerCase().includes(columnSearchQuery.toLowerCase()));

              if (filtered.length === 0) {
                return <div style={{ padding: '16px', color: 'var(--text-muted)', fontSize: '12px' }}>No se encontraron columnas.</div>;
              }

              return filtered.map(col => {
                const selObj = selectedColumns.find(c => c.tableAlias === curTable.alias && c.column === col.name);
                const isSelected = Boolean(selObj);

                return (
                  <div key={col.name} className={`cr-column-item ${isSelected ? 'selected' : ''}`}>
                    <label className="cr-col-checkbox-label">
                      <input
                        type="checkbox"
                        checked={isSelected}
                        onChange={() => onToggleColumn(curTable.alias, col.name)}
                        style={{ accentColor: 'var(--excel-green-light)', cursor: 'pointer' }}
                      />
                      <span className="cr-col-orig-name">{col.name}</span>
                      <span className="cr-col-type-tag">{col.type}</span>
                    </label>

                    {isSelected && (
                      <div className="cr-col-alias-box">
                        <span className="cr-col-as-text">como:</span>
                        <input
                          type="text"
                          className="excel-input cr-alias-input"
                          value={selObj.label}
                          onChange={(e) => onUpdateColumnLabel(curTable.alias, col.name, e.target.value)}
                          placeholder={col.name}
                          title="Nombre de la columna en el encabezado del reporte"
                        />
                      </div>
                    )}
                  </div>
                );
              });
            })()}
          </div>
        </div>
      )}
    </div>
  );
}

export default React.memo(ColumnSelectorPanel);
