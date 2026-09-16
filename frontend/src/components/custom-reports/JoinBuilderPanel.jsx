import React, { useState } from 'react';
import { Plus, Trash2, Sparkles, Check, RefreshCw, ArrowRightLeft } from 'lucide-react';
import { pickDescriptiveColumn } from '../../utils/reportBuilder';

// PANEL IZQUIERDO del constructor de reportes: selector de tabla base, cadena
// visual de cruces, sugerencias de FK, lista de joins configurados y herramienta
// de sustitución de columnas en posición.
function JoinBuilderPanel({
  tables,
  baseTable,
  joins,
  suggestedJoins,
  isLoadingSuggestions,
  columnsCache,
  selectedColumns = [],
  onSelectBaseTable,
  onAddManualJoin,
  onApplySuggestedJoin,
  onUpdateJoin,
  onDeleteJoin,
  onReplaceBaseColumnWithJoined,
  uxMode = 'simple'
}) {
  // Estado local para los selectores de reemplazo por cada join: { [joinId]: { baseCol, targetCol } }
  const [replaceSelections, setReplaceSelections] = useState({});
  const [lastReplacedBadge, setLastReplacedBadge] = useState(null);

  const handleSetReplaceField = (joinId, field, value) => {
    setReplaceSelections(prev => ({
      ...prev,
      [joinId]: {
        ...(prev[joinId] || {}),
        [field]: value
      }
    }));
  };

  const handleExecuteReplace = (join, baseCol, targetCol) => {
    if (!baseCol || !targetCol) return;
    if (onReplaceBaseColumnWithJoined) {
      onReplaceBaseColumnWithJoined(baseCol, join.table.alias, targetCol);
      setLastReplacedBadge(`${join.id}-${baseCol}`);
      setTimeout(() => setLastReplacedBadge(null), 3000);
    }
  };

  return (
    <div className="cr-panel-card">
      <div className="cr-card-header">
        <div className="cr-card-title">
          <span className="cr-step-number">1</span>
          <h3>{uxMode === 'simple' ? 'Tabla de Partida y Cruces' : 'Tabla Principal y Cruces (Joins)'}</h3>
        </div>
        <button
          className="excel-btn excel-btn--compact"
          onClick={onAddManualJoin}
          disabled={!baseTable}
          title="Agregar un cruce manual con otra tabla"
        >
          <Plus size={13} />
          <span>{uxMode === 'simple' ? '+ Unir otra tabla' : 'Cruce Manual'}</span>
        </button>
      </div>

      {/* Selector de Tabla Base */}
      <div className="cr-section-block">
        <label className="cr-label">
          <strong>{uxMode === 'simple' ? '1. Elige tu tabla de inicio (Ej: Empleados, Proceso):' : 'Tabla Principal (Origen de datos):'}</strong>
        </label>
        <select
          className="excel-select cr-wide-select"
          value={baseTable ? `${baseTable.schema}.${baseTable.name}` : ''}
          onChange={(e) => onSelectBaseTable(e.target.value)}
        >
          <option value="">-- {uxMode === 'simple' ? 'Seleccionar hoja o tabla de inicio' : 'Seleccionar tabla principal'} --</option>
          {tables.map(t => (
            <option key={`${t.schema}.${t.name}`} value={`${t.schema}.${t.name}`}>
              {uxMode === 'simple' ? t.name : `${t.schema}.${t.name}`}
            </option>
          ))}
        </select>
      </div>

      {/* Mapa / Cadena Visual de Tablas Cruzadas */}
      {baseTable && (
        <div className="cr-chain-container">
          <span className="cr-chain-title">Esquema de Cruces ({joins.length + 1} tablas):</span>
          <div className="cr-tables-chain">
            <span className="cr-chain-badge base" title="Tabla principal de origen">
              🏠 {baseTable.name} <small>(Base)</small>
            </span>
            {joins.map((j) => (
              <React.Fragment key={j.id}>
                <span className="cr-chain-arrow">➔</span>
                <span className="cr-chain-badge join" title={`${j.type} JOIN con ${j.onLeft.tableAlias}.${j.onLeft.column} = ${j.table.alias}.${j.onRight.column}`}>
                  🔗 {j.type} {j.table.name}
                </span>
              </React.Fragment>
            ))}
          </div>
        </div>
      )}

      {/* Sugerencias Inteligentes de Cruces basadas en FKs */}
      {baseTable && (
        <div className="cr-suggestions-container">
          <div className="cr-suggestions-header">
            <Sparkles size={14} style={{ color: 'var(--excel-yellow)' }} />
            <span>Relaciones FK detectadas ({suggestedJoins.length})</span>
          </div>
          {isLoadingSuggestions ? (
            <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>Analizando claves foráneas...</div>
          ) : suggestedJoins.length > 0 ? (
            <div className="cr-suggestions-list">
              {suggestedJoins.map((sug, idx) => {
                const isAlreadyAdded = joins.some(j =>
                  (j.table.schema === sug.targetSchema && j.table.name === sug.targetTable) ||
                  (j.table.schema === sug.sourceSchema && j.table.name === sug.sourceTable)
                );
                return (
                  <div key={idx} className={`cr-suggestion-item ${isAlreadyAdded ? 'added' : ''}`}>
                    <div className="cr-sug-info">
                      <span className="cr-sug-desc">
                        <strong>[{sug.originTableName || baseTable.name}]</strong> ➔ {sug.description}
                      </span>
                    </div>
                    <div style={{ display: 'flex', gap: '4px' }}>
                      <button
                        className="excel-btn primary"
                        style={{ fontSize: '10px', padding: '2px 7px', height: '22px' }}
                        onClick={() => onApplySuggestedJoin(sug, true)}
                        disabled={isAlreadyAdded}
                        title="Une la tabla y reemplaza el ID original en su misma posición por el dato descriptivo"
                      >
                        <RefreshCw size={11} />
                        <span>{isAlreadyAdded ? 'Agregado' : 'Unir y Reemplazar'}</span>
                      </button>
                      {!isAlreadyAdded && (
                        <button
                          className="excel-btn"
                          style={{ fontSize: '10px', padding: '2px 6px', height: '22px' }}
                          onClick={() => onApplySuggestedJoin(sug, false)}
                          title="Une la tabla agregando los campos al final del reporte"
                        >
                          <Plus size={11} />
                          <span>Al final</span>
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <div style={{ fontSize: '11.5px', color: 'var(--text-muted)' }}>
              No se detectaron FKs directas. Puedes usar el botón <strong>"+ Cruce Manual"</strong> arriba.
            </div>
          )}
        </div>
      )}

      {/* Lista de Cruces Configurados */}
      {joins.length > 0 && (
        <div className="cr-joins-list">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <label className="cr-label" style={{ margin: 0 }}><strong>Cruces configurados ({joins.length}):</strong></label>
          </div>

          {joins.map((j, idx) => {
            const availableLeftTables = [baseTable, ...joins.slice(0, idx).map(item => item.table)].filter(Boolean);
            const leftTableObj = availableLeftTables.find(t => t.alias === j.onLeft.tableAlias) || availableLeftTables[0];
            const leftCols = leftTableObj ? (columnsCache[`${leftTableObj.schema}.${leftTableObj.name}`] || []) : [];
            const rightCols = columnsCache[`${j.table.schema}.${j.table.name}`] || [];

            // Valor seleccionado por defecto para reemplazo
            const currentReplaceState = replaceSelections[j.id] || {};
            const effectiveBaseCol = currentReplaceState.baseCol || j.onLeft.column || (leftCols[0]?.name || '');
            
            // Sugerir la mejor columna descriptiva (no ID)
            const defaultTargetCol = pickDescriptiveColumn(rightCols)?.name || '';
            
            const effectiveTargetCol = currentReplaceState.targetCol || defaultTargetCol;
            const isBadgeActive = lastReplacedBadge === `${j.id}-${effectiveBaseCol}`;

            return (
              <div key={j.id} className="cr-join-card">
                <div className="cr-join-header">
                  <span className="cr-join-idx">Cruce #{idx + 1}</span>
                  <select
                    className="excel-select cr-join-type-select"
                    value={j.type}
                    onChange={(e) => onUpdateJoin(j.id, 'type', e.target.value)}
                  >
                    <option value="LEFT">{uxMode === 'simple' ? 'Traer todos los registros principales' : 'LEFT JOIN (Izquierda)'}</option>
                    <option value="INNER">{uxMode === 'simple' ? 'Solo coincidencias exactas' : 'INNER JOIN (Intersección)'}</option>
                    <option value="RIGHT">RIGHT JOIN (Derecha)</option>
                    <option value="FULL">FULL JOIN (Todos)</option>
                  </select>
                  <button
                    className="cr-icon-btn danger"
                    onClick={() => onDeleteJoin(j.id)}
                    title="Eliminar este cruce"
                  >
                    <Trash2 size={13} />
                  </button>
                </div>

                <div className="cr-join-body">
                  <div className="cr-field-row">
                    <span className="cr-field-label">{uxMode === 'simple' ? 'Tabla a cruzar:' : 'Unir tabla:'}</span>
                    <select
                      className="excel-select cr-join-target-select"
                      value={`${j.table.schema}.${j.table.name}`}
                      onChange={(e) => onUpdateJoin(j.id, 'tableKey', e.target.value)}
                    >
                      {tables.map(t => (
                        <option key={`${t.schema}.${t.name}`} value={`${t.schema}.${t.name}`}>
                          {uxMode === 'simple' ? t.name : `${t.schema}.${t.name}`}
                        </option>
                      ))}
                    </select>
                  </div>

                  <div className="cr-join-condition-block">
                    <span className="cr-on-title">{uxMode === 'simple' ? 'Unir por los campos coincidentes:' : 'Condición de enlace (ON):'}</span>
                    <div className="cr-join-condition-row">
                      {/* Selector de Tabla Izquierda */}
                      <select
                        className="excel-select cr-on-table-select"
                        value={j.onLeft.tableAlias || (leftTableObj?.alias || 't0')}
                        onChange={(e) => onUpdateJoin(j.id, 'onLeftTableAlias', e.target.value)}
                        title="Selecciona con qué tabla se relaciona este cruce"
                      >
                        {availableLeftTables.map(t => (
                          <option key={t.alias} value={t.alias}>
                            {uxMode === 'simple' ? t.name : `${t.name} (${t.alias})`}
                          </option>
                        ))}
                      </select>

                      {/* Selector de Columna Izquierda */}
                      <select
                        className="excel-select cr-on-col-select"
                        value={j.onLeft.column}
                        onChange={(e) => onUpdateJoin(j.id, 'onLeftColumn', e.target.value)}
                      >
                        <option value="">-- Columna ({leftTableObj?.name || 'Izquierda'}) --</option>
                        {leftCols.map(c => (
                          <option key={c.name} value={c.name}>{c.name}</option>
                        ))}
                      </select>

                      <span className="cr-equal-sign">=</span>

                      {/* Columna Derecha de la tabla unida */}
                      <select
                        className="excel-select cr-on-col-select"
                        value={j.onRight.column}
                        onChange={(e) => onUpdateJoin(j.id, 'onRightColumn', e.target.value)}
                      >
                        <option value="">-- Columna ({j.table.name}) --</option>
                        {rightCols.map(c => (
                          <option key={c.name} value={c.name}>{c.name}</option>
                        ))}
                      </select>
                    </div>
                  </div>

                  {/* Herramienta de Sustitución Directa de Columna en Posición */}
                  {leftCols.length > 0 && rightCols.length > 0 && (
                    <div className="cr-join-replace-box">
                      <div className="cr-join-replace-header">
                        <ArrowRightLeft size={13} style={{ color: 'var(--excel-yellow)' }} />
                        <span>Reemplazar dato en la misma columna del reporte:</span>
                      </div>
                      <div className="cr-join-replace-controls">
                        <div className="cr-replace-field">
                          <label>Reemplazar campo:</label>
                          <select
                            className="excel-select cr-replace-select"
                            value={effectiveBaseCol}
                            onChange={(e) => handleSetReplaceField(j.id, 'baseCol', e.target.value)}
                            title="Selecciona la columna de la tabla base que deseas sustituir"
                          >
                            {leftCols.map(c => (
                              <option key={c.name} value={c.name}>{leftTableObj?.name} ➔ {c.name}</option>
                            ))}
                          </select>
                        </div>

                        <div className="cr-replace-field">
                          <label>Mostrar en su lugar:</label>
                          <select
                            className="excel-select cr-replace-select"
                            value={effectiveTargetCol}
                            onChange={(e) => handleSetReplaceField(j.id, 'targetCol', e.target.value)}
                            title="Selecciona qué dato de la tabla unida deseas ver en esa columna"
                          >
                            {rightCols.map(c => (
                              <option key={c.name} value={c.name}>{j.table.name} ➔ {c.name} ({c.type})</option>
                            ))}
                          </select>
                        </div>

                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '2px' }}>
                          {isBadgeActive ? (
                            <span style={{ fontSize: '11px', color: 'var(--excel-green-light)', fontWeight: 600 }}>
                              ✓ ¡Columna reemplazada en su posición!
                            </span>
                          ) : (
                            <span style={{ fontSize: '10.5px', color: 'var(--text-muted)' }}>
                              Mantiene el orden exacto en la tabla.
                            </span>
                          )}
                          <button
                            type="button"
                            className="excel-btn primary"
                            style={{ fontSize: '11px', padding: '3px 10px', height: '26px' }}
                            onClick={() => handleExecuteReplace(j, effectiveBaseCol, effectiveTargetCol)}
                            title={`Reemplaza la columna ${effectiveBaseCol} por ${effectiveTargetCol} en su posición exacta`}
                          >
                            <RefreshCw size={12} />
                            <span>Aplicar Reemplazo</span>
                          </button>
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            );
          })}

          {/* Botón para agregar más cruces */}
          <button
            className="excel-btn"
            style={{
              width: '100%',
              padding: '8px',
              marginTop: '4px',
              justifyContent: 'center',
              backgroundColor: 'rgba(16, 124, 65, 0.08)',
              borderColor: 'var(--excel-green)',
              color: 'var(--excel-green-light)',
              fontWeight: 600,
              gap: '6px'
            }}
            onClick={onAddManualJoin}
            disabled={!baseTable}
          >
            <Plus size={14} />
            <span>+ Agregar Otro Cruce (Unir otra tabla)</span>
          </button>
        </div>
      )}
    </div>
  );
}

export default React.memo(JoinBuilderPanel);
