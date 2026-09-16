import React, { useState } from 'react';
import {
  Info, ArrowUp, ArrowDown, RefreshCw, Trash2,
  ListOrdered, LayoutGrid, Check, X, ArrowRightLeft
} from 'lucide-react';

// PANEL CENTRAL del constructor de reportes:
// Vista dual: (1) "Orden y Reemplazos del Reporte" para ver/editar la secuencia 1..N,
// mover arriba/abajo y sustituir datos de cruces en cualquier posición.
// (2) "Explorar por Tablas" para marcar/desmarcar casillas tabla por tabla.
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
  onMoveColumn,
  onReplaceColumnByIndex,
  onRemoveSelectedColumn,
  uxMode = 'simple'
}) {
  // Pestaña activa del panel: 'order' (Secuencia y Reemplazo del Reporte) | 'tables' (Explorador por Tablas)
  const [panelTab, setPanelTab] = useState('order');
  
  // Estado para el popover de reemplazo de columna por índice: index o null
  const [replacingIndex, setReplacingIndex] = useState(null);
  const [replaceTargetTableAlias, setReplaceTargetTableAlias] = useState('');
  const [replaceTargetCol, setReplaceTargetCol] = useState('');

  const handleStartReplace = (index, currentTableAlias) => {
    setReplacingIndex(index);
    // Seleccionar por defecto la primera tabla unida disponible o la tabla actual
    const defaultTable = participatingTables.find(t => t.alias !== currentTableAlias) || participatingTables[0];
    const tableAlias = defaultTable ? defaultTable.alias : currentTableAlias;
    setReplaceTargetTableAlias(tableAlias);
    const cols = defaultTable ? (columnsCache[`${defaultTable.schema}.${defaultTable.name}`] || []) : [];
    setReplaceTargetCol(cols[0]?.name || '');
  };

  const handleConfirmReplace = (index) => {
    if (onReplaceColumnByIndex && replaceTargetTableAlias && replaceTargetCol) {
      onReplaceColumnByIndex(index, replaceTargetTableAlias, replaceTargetCol);
      setReplacingIndex(null);
    }
  };

  const handleTableChangeInReplace = (tableAlias) => {
    setReplaceTargetTableAlias(tableAlias);
    const targetTable = participatingTables.find(t => t.alias === tableAlias);
    if (targetTable) {
      const cols = columnsCache[`${targetTable.schema}.${targetTable.name}`] || [];
      setReplaceTargetCol(cols[0]?.name || '');
    }
  };

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
          {/* Sub-Navegación entre Orden del Reporte y Explorador por Tablas */}
          <div className="cr-subnav-bar">
            <button
              className={`cr-subnav-btn ${panelTab === 'order' ? 'active' : ''}`}
              onClick={() => setPanelTab('order')}
              title="Ver el orden exacto de las columnas en el Excel, mover arriba/abajo o reemplazar campos"
            >
              <ListOrdered size={13} />
              <span>Orden del Reporte ({selectedColumns.length})</span>
            </button>
            <button
              className={`cr-subnav-btn ${panelTab === 'tables' ? 'active' : ''}`}
              onClick={() => setPanelTab('tables')}
              title="Examinar y marcar o desmarcar campos por cada tabla participante"
            >
              <LayoutGrid size={13} />
              <span>Explorar por Tablas</span>
            </button>
          </div>

          {/* VISTA 1: ORDEN DEL REPORTE Y REEMPLAZO EN POSICIÓN */}
          {panelTab === 'order' && (
            <>
              <div className="cr-columns-filter-bar">
                <input
                  type="text"
                  className="excel-input cr-search-input"
                  placeholder="Filtrar columnas seleccionadas..."
                  value={columnSearchQuery}
                  onChange={(e) => setColumnSearchQuery(e.target.value)}
                />
                <span style={{ fontSize: '11px', color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                  {selectedColumns.length} campos activos
                </span>
              </div>

              <div className="cr-col-order-list">
                {selectedColumns.length === 0 ? (
                  <div style={{ padding: '24px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '12px' }}>
                    <p>No has seleccionado columnas para el reporte.</p>
                    <button
                      className="excel-btn primary"
                      style={{ marginTop: '8px' }}
                      onClick={() => setPanelTab('tables')}
                    >
                      + Seleccionar Columnas
                    </button>
                  </div>
                ) : (
                  selectedColumns.map((col, index) => {
                    const tableObj = participatingTables.find(t => t.alias === col.tableAlias);
                    const isBase = tableObj?.isBase;
                    const isMatchingSearch = !columnSearchQuery ||
                      col.column.toLowerCase().includes(columnSearchQuery.toLowerCase()) ||
                      col.label.toLowerCase().includes(columnSearchQuery.toLowerCase());

                    if (!isMatchingSearch) return null;

                    const isBeingReplaced = replacingIndex === index;

                    return (
                      <div key={`${col.tableAlias}-${col.column}-${index}`} className={`cr-col-order-card ${!isBase ? 'is-joined' : ''}`}>
                        <div className="cr-col-order-header">
                          <div className="cr-col-order-meta">
                            <span className="cr-col-order-badge">#{index + 1}</span>
                            <span className={`cr-col-order-origin ${isBase ? 'base' : ''}`} title={`Tabla: ${tableObj?.name || col.tableAlias}`}>
                              {isBase ? '🏠 ' : '🔗 '}{tableObj?.name || col.tableAlias}
                            </span>
                            <span className="cr-col-order-name" title={`Campo físico: ${col.column}`}>
                              {col.column}
                            </span>
                          </div>

                          <div className="cr-col-order-actions">
                            <button
                              className="cr-col-btn"
                              onClick={() => onMoveColumn && onMoveColumn(index, 'up')}
                              disabled={index === 0}
                              title="Subir posición en el reporte (Mover hacia la izquierda)"
                            >
                              <ArrowUp size={12} />
                            </button>
                            <button
                              className="cr-col-btn"
                              onClick={() => onMoveColumn && onMoveColumn(index, 'down')}
                              disabled={index === selectedColumns.length - 1}
                              title="Bajar posición en el reporte (Mover hacia la derecha)"
                            >
                              <ArrowDown size={12} />
                            </button>
                            <button
                              className="cr-col-btn replace-trigger"
                              onClick={() => isBeingReplaced ? setReplacingIndex(null) : handleStartReplace(index, col.tableAlias)}
                              title="Sustituir este campo en su misma posición por el dato de una tabla cruzada"
                            >
                              <RefreshCw size={11} />
                              <span>Reemplazar</span>
                            </button>
                            <button
                              className="cr-col-btn delete-col"
                              onClick={() => onRemoveSelectedColumn && onRemoveSelectedColumn(index)}
                              title="Quitar esta columna del reporte"
                            >
                              <Trash2 size={11} />
                            </button>
                          </div>
                        </div>

                        {/* Input para renombrar encabezado de columna */}
                        <div className="cr-col-alias-box" style={{ paddingLeft: 0, marginTop: '2px' }}>
                          <span className="cr-col-as-text" style={{ fontSize: '10px' }}>Encabezado Excel:</span>
                          <input
                            type="text"
                            className="excel-input cr-alias-input"
                            value={col.label}
                            onChange={(e) => onUpdateColumnLabel && onUpdateColumnLabel(col.tableAlias, col.column, e.target.value)}
                            placeholder={col.column}
                            title="Nombre con el que aparecerá en el encabezado del archivo Excel"
                          />
                        </div>

                        {/* Popover Inline para Reemplazar Columna en Posición Exacta */}
                        {isBeingReplaced && (
                          <div className="cr-replace-popover">
                            <div className="cr-replace-popover-title">
                              <ArrowRightLeft size={12} />
                              <span>Sustituir "#{index + 1} {col.column}" por dato de tabla:</span>
                            </div>
                            <div className="cr-replace-popover-row">
                              {/* Selector de Tabla */}
                              <select
                                className="excel-select"
                                style={{ flex: 1, height: '24px', fontSize: '11px' }}
                                value={replaceTargetTableAlias}
                                onChange={(e) => handleTableChangeInReplace(e.target.value)}
                              >
                                {participatingTables.map(t => (
                                  <option key={t.alias} value={t.alias}>
                                    {t.isBase ? '🏠 ' : '🔗 '}{t.name} ({t.alias})
                                  </option>
                                ))}
                              </select>

                              {/* Selector de Columna de esa tabla */}
                              <select
                                className="excel-select"
                                style={{ flex: 1, height: '24px', fontSize: '11px' }}
                                value={replaceTargetCol}
                                onChange={(e) => setReplaceTargetCol(e.target.value)}
                              >
                                {(() => {
                                  const targetTable = participatingTables.find(t => t.alias === replaceTargetTableAlias);
                                  const cols = targetTable ? (columnsCache[`${targetTable.schema}.${targetTable.name}`] || []) : [];
                                  return cols.map(c => (
                                    <option key={c.name} value={c.name}>{c.name} ({c.type})</option>
                                  ));
                                })()}
                              </select>

                              <button
                                type="button"
                                className="excel-btn primary"
                                style={{ height: '24px', padding: '0 8px', fontSize: '10.5px' }}
                                onClick={() => handleConfirmReplace(index)}
                                title="Confirmar reemplazo de columna"
                              >
                                <Check size={12} />
                                <span>Aplicar</span>
                              </button>
                              <button
                                type="button"
                                className="cr-icon-btn"
                                onClick={() => setReplacingIndex(null)}
                                title="Cancelar reemplazo"
                              >
                                <X size={12} />
                              </button>
                            </div>
                          </div>
                        )}
                      </div>
                    );
                  })
                )}
              </div>
            </>
          )}

          {/* VISTA 2: EXPLORAR Y MARCAR POR TABLAS */}
          {panelTab === 'tables' && (
            <>
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
                      <span>{t.isBase ? '🏠 ' : '🔗 '}{t.name}</span>
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
            </>
          )}
        </div>
      )}
    </div>
  );
}

export default React.memo(ColumnSelectorPanel);
