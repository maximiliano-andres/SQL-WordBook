import React from 'react';
import { Plus, Trash2, ArrowUpDown } from 'lucide-react';
import { ALLOWED_OPERATORS } from './operators';

// PANEL DERECHO del constructor de reportes: condiciones de filtro y
// ordenamiento. Memoizado junto a los demás paneles del constructor.
function FilterSortPanel({
  participatingTables,
  filters,
  sorts,
  columnsCache,
  onAddFilter,
  onUpdateFilter,
  onDeleteFilter,
  onAddSort,
  onUpdateSort,
  onDeleteSort,
  uxMode = 'simple'
}) {
  return (
    <div className="cr-panel-card">
      <div className="cr-card-header">
        <div className="cr-card-title">
          <span className="cr-step-number">3</span>
          <h3>{uxMode === 'simple' ? 'Filtrar y Ordenar Datos' : 'Filtros y Ordenamiento'}</h3>
        </div>
        <div style={{ display: 'flex', gap: '6px' }}>
          <button
            className="excel-btn excel-btn--compact"
            onClick={onAddFilter}
            disabled={participatingTables.length === 0}
          >
            <Plus size={13} />
            <span>{uxMode === 'simple' ? '+ Filtrar' : 'Filtro'}</span>
          </button>
          <button
            className="excel-btn excel-btn--compact"
            onClick={onAddSort}
            disabled={participatingTables.length === 0}
          >
            <ArrowUpDown size={13} />
            <span>{uxMode === 'simple' ? '+ Ordenar' : 'Orden'}</span>
          </button>
        </div>
      </div>

      {/* Filtros */}
      <div className="cr-filters-section">
        <label className="cr-label"><strong>{uxMode === 'simple' ? 'Condiciones de Búsqueda:' : `Condiciones de Filtrado (${filters.length}):`}</strong></label>
        {filters.length === 0 ? (
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '16px' }}>
            {uxMode === 'simple' ? 'Sin filtros (se traerán todos los registros). Haz clic en "+ Filtrar" para buscar por fecha, empresa, rut, etc.' : 'Sin filtros (se consultarán todas las filas). Haz clic en "+ Filtro" para acotar datos.'}
          </div>
        ) : (
          <div className="cr-rules-list">
            {filters.map((f, idx) => {
              const curTable = participatingTables.find(t => t.alias === f.tableAlias);
              const cols = curTable ? (columnsCache[`${curTable.schema}.${curTable.name}`] || []) : [];
              const opMeta = ALLOWED_OPERATORS.find(o => o.value === f.operator) || ALLOWED_OPERATORS[0];

              return (
                <div key={f.id} className="cr-rule-item">
                  {idx > 0 && (
                    <select
                      className="excel-select cr-logic-select"
                      value={f.logic}
                      onChange={(e) => onUpdateFilter(f.id, 'logic', e.target.value)}
                    >
                      <option value="AND">{uxMode === 'simple' ? 'Y además' : 'Y (AND)'}</option>
                      <option value="OR">{uxMode === 'simple' ? 'O bien' : 'O (OR)'}</option>
                    </select>
                  )}

                  <div className="cr-rule-row">
                    <select
                      className="excel-select cr-rule-table-select"
                      value={f.tableAlias}
                      onChange={(e) => onUpdateFilter(f.id, 'tableAlias', e.target.value)}
                    >
                      {participatingTables.map(t => (
                        <option key={t.alias} value={t.alias}>{t.name}</option>
                      ))}
                    </select>

                    <select
                      className="excel-select cr-rule-col-select"
                      value={f.column}
                      onChange={(e) => onUpdateFilter(f.id, 'column', e.target.value)}
                    >
                      {cols.map(c => (
                        <option key={c.name} value={c.name}>{c.name}</option>
                      ))}
                    </select>

                    <select
                      className="excel-select cr-rule-op-select"
                      value={f.operator}
                      onChange={(e) => onUpdateFilter(f.id, 'operator', e.target.value)}
                    >
                      {ALLOWED_OPERATORS.map(op => (
                        <option key={op.value} value={op.value}>{op.label}</option>
                      ))}
                    </select>

                    {!opMeta.unary && (
                      <input
                        type="text"
                        className="excel-input cr-rule-val-input"
                        value={f.value}
                        onChange={(e) => onUpdateFilter(f.id, 'value', e.target.value)}
                        placeholder={opMeta.between ? "Desde" : "Valor..."}
                      />
                    )}

                    {opMeta.between && (
                      <input
                        type="text"
                        className="excel-input cr-rule-val-input"
                        value={f.value2}
                        onChange={(e) => onUpdateFilter(f.id, 'value2', e.target.value)}
                        placeholder="Hasta"
                      />
                    )}

                    <button
                      className="cr-icon-btn danger"
                      onClick={() => onDeleteFilter(f.id)}
                      title="Eliminar condición"
                    >
                      <Trash2 size={13} />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Ordenamiento */}
      <div className="cr-sorts-section">
        <label className="cr-label"><strong>Ordenamiento de Resultados ({sorts.length}):</strong></label>
        {sorts.length === 0 ? (
          <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            Orden por defecto del motor. Haz clic en <strong>"+ Orden"</strong> para ordenar por columnas.
          </div>
        ) : (
          <div className="cr-rules-list">
            {sorts.map(s => {
              const curTable = participatingTables.find(t => t.alias === s.tableAlias);
              const cols = curTable ? (columnsCache[`${curTable.schema}.${curTable.name}`] || []) : [];

              return (
                <div key={s.id} className="cr-rule-row">
                  <select
                    className="excel-select cr-rule-table-select"
                    value={s.tableAlias}
                    onChange={(e) => onUpdateSort(s.id, 'tableAlias', e.target.value)}
                  >
                    {participatingTables.map(t => (
                      <option key={t.alias} value={t.alias}>{t.name}</option>
                    ))}
                  </select>

                  <select
                    className="excel-select cr-rule-col-select"
                    value={s.column}
                    onChange={(e) => onUpdateSort(s.id, 'column', e.target.value)}
                  >
                    {cols.map(c => (
                      <option key={c.name} value={c.name}>{c.name}</option>
                    ))}
                  </select>

                  <select
                    className="excel-select cr-rule-op-select"
                    value={s.direction}
                    onChange={(e) => onUpdateSort(s.id, 'direction', e.target.value)}
                  >
                    <option value="ASC">Ascendente (A-Z, 0-9)</option>
                    <option value="DESC">Descendente (Z-A, 9-0)</option>
                  </select>

                  <button
                    className="cr-icon-btn danger"
                    onClick={() => onDeleteSort(s.id)}
                    title="Eliminar criterio de orden"
                  >
                    <Trash2 size={13} />
                  </button>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

export default React.memo(FilterSortPanel);
