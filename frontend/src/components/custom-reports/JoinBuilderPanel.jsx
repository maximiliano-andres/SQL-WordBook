import React from 'react';
import { Plus, Trash2, Sparkles, Check } from 'lucide-react';

// PANEL IZQUIERDO del constructor de reportes: selector de tabla base, cadena
// visual de cruces, sugerencias de FK y lista de joins configurados.
// Envuelto en React.memo: mientras el usuario edita filtros/ordenamiento (panel
// derecho) o renombra columnas (panel central), este panel no necesita
// re-renderizarse porque sus props (tables, baseTable, joins, columnsCache, ...)
// mantienen la misma identidad de referencia entre esos renders.
function JoinBuilderPanel({
  tables,
  baseTable,
  joins,
  suggestedJoins,
  isLoadingSuggestions,
  columnsCache,
  onSelectBaseTable,
  onAddManualJoin,
  onApplySuggestedJoin,
  onUpdateJoin,
  onDeleteJoin,
  uxMode = 'simple'
}) {
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
                    <button
                      className="excel-btn"
                      style={{ fontSize: '10.5px', padding: '2px 8px', height: '22px' }}
                      onClick={() => onApplySuggestedJoin(sug)}
                      disabled={isAlreadyAdded}
                    >
                      {isAlreadyAdded ? <Check size={12} /> : <Plus size={12} />}
                      <span>{isAlreadyAdded ? 'Agregado' : 'Unir'}</span>
                    </button>
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
                      {/* Selector de Tabla Izquierda (Permite encadenar con base o joins anteriores) */}
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

                      {/* Columna Derecha de la tabla que se está uniendo */}
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
