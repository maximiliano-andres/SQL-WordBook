import React, { useState, useEffect, useMemo } from 'react';
import { Table, FileSpreadsheet, Link2, Check, X, Edit2, Trash2, Filter } from 'lucide-react';
import { fkStatusText } from '../utils/fk';
import { useAuth } from '../context/AuthContext';

// Extraído de Spreadsheet: el panel de filtros tiene su propio estado local que
// cambia con cada tecla escrita. Si viviera dentro de Spreadsheet, cada tecla
// re-renderizaría también la grilla completa (hasta 100 filas sin virtualizar).
// Al ser un componente aparte, escribir en el filtro solo re-renderiza esto.
function FilterBar({ active, columns, appliedFilter, activeTable, onApplyFilter, dataLength, totalRows }) {
  const [showFilterPanel, setShowFilterPanel] = useState(false);
  const [tempFilterColumn, setTempFilterColumn] = useState("");
  const [tempFilterOperator, setTempFilterOperator] = useState("LIKE");
  const [tempFilterValue, setTempFilterValue] = useState("");
  const [tempFilterValue2, setTempFilterValue2] = useState("");

  useEffect(() => {
    if (columns.length > 0) {
      setTempFilterColumn(columns[0].name);
    }
  }, [columns]);

  // Al cambiar de tabla se resetea también el resto del panel (operador/valores/visibilidad):
  // de lo contrario quedaban valores de la tabla anterior aplicables por error a la nueva.
  useEffect(() => {
    setTempFilterOperator('LIKE');
    setTempFilterValue('');
    setTempFilterValue2('');
    setShowFilterPanel(false);
  }, [activeTable]);

  const isUnaryOperator = (op) => {
    return op === 'IS NULL' || op === 'IS NOT NULL';
  };

  const getOperatorLabel = (op) => {
    switch (op) {
      case 'LIKE': return 'contiene';
      case '=': return 'igual a';
      case '>': return 'mayor que';
      case '<': return 'menor que';
      case '>=': return 'mayor o igual que';
      case '<=': return 'menor o igual que';
      case 'IS NULL': return 'está vacío';
      case 'IS NOT NULL': return 'no está vacío';
      case 'BETWEEN': return 'está entre';
      default: return op;
    }
  };

  const handleApplyFilter = () => {
    if (!tempFilterColumn) return;
    onApplyFilter({
      column: tempFilterColumn,
      operator: tempFilterOperator,
      value: isUnaryOperator(tempFilterOperator) ? '' : tempFilterValue,
      value2: tempFilterOperator === 'BETWEEN' ? tempFilterValue2 : ''
    });
  };

  const handleClearFilter = () => {
    setTempFilterValue('');
    setTempFilterValue2('');
    onApplyFilter(null);
  };

  // FilterBar se mantiene siempre montado (ver uso en Spreadsheet) para no perder
  // su estado local (panel abierto, valores en curso de edición) cada vez que se
  // cambia a la pestaña "Estructura de Columnas" y se vuelve a "Registros".
  if (!active) return null;

  return (
    <div className="filter-section">

      <div className="filter-section-bar">
        <div className="filter-section-main">
          <button
            className={`excel-btn ${showFilterPanel ? 'primary' : ''}`}
            onClick={() => {
              setShowFilterPanel(!showFilterPanel);
              if (!tempFilterColumn && columns.length > 0) {
                setTempFilterColumn(columns[0].name);
              }
            }}
            title="Abrir o cerrar el panel de filtros de fila"
            aria-expanded={showFilterPanel}
          >
            <Filter size={14} />
            {showFilterPanel ? 'Ocultar Filtros' : 'Filtrar Registros'}
          </button>

          {appliedFilter && appliedFilter.column && (
            <div className="filter-active-pill">
              <span>
                Filtro activo: <strong>{appliedFilter.column}</strong> {getOperatorLabel(appliedFilter.operator)}{' '}
                {appliedFilter.operator === 'BETWEEN'
                  ? `"${appliedFilter.value}" y "${appliedFilter.value2}"`
                  : !isUnaryOperator(appliedFilter.operator) && `"${appliedFilter.value}"`}
              </span>
              <button
                onClick={handleClearFilter}
                className="filter-clear-btn"
                title="Eliminar filtro activo y mostrar todos los registros"
              >
                <X size={13} />
              </button>
            </div>
          )}
        </div>

        {appliedFilter && appliedFilter.column && (
          <span className="filter-result-count">
            Mostrando {dataLength} de {totalRows} registros coincidentes.
          </span>
        )}
      </div>

      {showFilterPanel && (
        <div className="filter-panel">

          <label className="filter-field">
            <span className="filter-field-label">Columna</span>
            <select
              className="excel-input filter-control"
              value={tempFilterColumn}
              onChange={(e) => setTempFilterColumn(e.target.value)}
            >
              {columns.map(col => (
                <option key={col.name} value={col.name}>{col.name}</option>
              ))}
            </select>
          </label>

          <label className="filter-field">
            <span className="filter-field-label">Condición</span>
            <select
              className="excel-input filter-control"
              value={tempFilterOperator}
              onChange={(e) => setTempFilterOperator(e.target.value)}
            >
              <option value="LIKE">Contiene (texto)</option>
              <option value="=">Igual a (=)</option>
              <option value="BETWEEN">Entre (rango)</option>
              <option value=">">Mayor que (&gt;)</option>
              <option value="<">Menor que (&lt;)</option>
              <option value=">=">Mayor o igual (&ge;)</option>
              <option value="<=">Menor o igual (&le;)</option>
              <option value="IS NULL">Vacío (NULL)</option>
              <option value="IS NOT NULL">No Vacío (NOT NULL)</option>
            </select>
          </label>

          {tempFilterOperator === 'BETWEEN' ? (
            <>
              <label className="filter-field">
                <span className="filter-field-label">Desde</span>
                <input
                  type="text"
                  className="excel-input filter-control"
                  placeholder="Valor inicial..."
                  value={tempFilterValue}
                  onChange={(e) => setTempFilterValue(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') handleApplyFilter();
                  }}
                />
              </label>
              <label className="filter-field">
                <span className="filter-field-label">Hasta</span>
                <input
                  type="text"
                  className="excel-input filter-control"
                  placeholder="Valor final..."
                  value={tempFilterValue2}
                  onChange={(e) => setTempFilterValue2(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') handleApplyFilter();
                  }}
                />
              </label>
            </>
          ) : !isUnaryOperator(tempFilterOperator) && (
            <label className="filter-field">
              <span className="filter-field-label">Valor</span>
              <input
                type="text"
                className="excel-input filter-control"
                placeholder="Ej: valor de búsqueda..."
                value={tempFilterValue}
                onChange={(e) => setTempFilterValue(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') handleApplyFilter();
                }}
              />
            </label>
          )}

          <div className="filter-actions">
            <button
              className="excel-btn primary"
              onClick={handleApplyFilter}
              disabled={
                !tempFilterColumn ||
                (tempFilterOperator === 'BETWEEN' && (tempFilterValue.trim() === '' || tempFilterValue2.trim() === '')) ||
                (!isUnaryOperator(tempFilterOperator) && tempFilterOperator !== 'BETWEEN' && tempFilterValue.trim() === '')
              }
              title="Aplicar la condición de filtro activa sobre los datos"
            >
              Aplicar Filtro
            </button>
            <button
              className="excel-btn"
              onClick={handleClearFilter}
              title="Restablecer filtros y mostrar todos los registros"
            >
              Limpiar
            </button>
          </div>

        </div>
      )}

    </div>
  );
}

export default function Spreadsheet({
  activeTable,
  data,
  columns,
  isDataLoading,
  error,
  totalRows,
  offset,
  fkColumns = [],
  fkResolutions = {},
  fkDisplayMode = 'both',
  tables = [],
  onSaveCustomFk = () => {},
  onToggleFk = () => {},
  onDeleteCustomFk = () => {},
  appliedFilter = null,
  onApplyFilter = () => {},
  uxMode = 'simple'
}) {
  const { apiFetch } = useAuth();
  const [activeTab, setActiveTab] = useState('data'); // 'data' o 'schema'
  const [editingColName, setEditingColName] = useState(null);
  const [selectedTargetTableKey, setSelectedTargetTableKey] = useState("");
  const [selectedTargetColumn, setSelectedTargetColumn] = useState("");
  const [columnsOfTargetTable, setColumnsOfTargetTable] = useState([]);
  const [selectedDisplayColumns, setSelectedDisplayColumns] = useState([]);
  const [selectedFilterColumn, setSelectedFilterColumn] = useState("");
  const [filterValueText, setFilterValueText] = useState("");
  const [schemaSearchQuery, setSchemaSearchQuery] = useState("");

  const normalizeString = (str) => {
    if (!str) return "";
    return str.toLowerCase().replace(/[ _]/g, "");
  };

  const filteredColumns = columns.filter(col => {
    if (!schemaSearchQuery) return true;
    const q = normalizeString(schemaSearchQuery);
    const name = normalizeString(col.name);
    return name.includes(q);
  });

  const loadColumnsForTargetTable = async (tableKey) => {
    if (!tableKey) return;
    const [schema, name] = tableKey.split('.');
    try {
      const res = await apiFetch(`/api/db/tables/${encodeURIComponent(schema)}/${encodeURIComponent(name)}/columns`);
      if (res.ok) {
        const cols = await res.json();
        setColumnsOfTargetTable(cols);
      }
    } catch (e) {
      console.error("Error loading columns for target table", e);
    }
  };

  const formatFkName = (fk) => {
    if (!fk || !fk.referencedTable || !fk.referencedColumn) return "Relación rota";
    const schema = fk.referencedSchema || 'dbo';
    const tablePart = schema.toLowerCase() === 'dbo' 
      ? fk.referencedTable 
      : `${schema}.${fk.referencedTable}`;
    const displayPart = fk.displayColumn ? fk.displayColumn : fk.referencedColumn;
    let desc = `Vinculado a: ${tablePart} (Clave: ${fk.referencedColumn} ➔ Valor: ${displayPart})`;
    if (fk.filterColumn && fk.filterValue) {
      desc += ` [Filtro: ${fk.filterColumn} = '${fk.filterValue}']`;
    }
    return desc;
  };

  const fkByColumn = useMemo(() => new Map(fkColumns.map(fk => [fk.column, fk])), [fkColumns]);

  if (!activeTable) {
    return (
      <div className="welcome-workbook-view">
        <div className="welcome-sheet">
          <h2>Libro de Trabajo <span>{uxMode === 'simple' ? 'Excel / Datos' : 'SQL Server'}</span></h2>
          <p>{uxMode === 'simple' ? 'Selecciona una de las hojas de datos en la barra inferior para consultar sus registros y aplicar filtros.' : 'Selecciona una de las tablas disponibles en las pestañas inferiores ("Hojas de cálculo") para cargar su información de forma segura y optimizada.'}</p>
          <div className="sheet-guide-grid">
            <div className="guide-box">
              <h4>{uxMode === 'simple' ? 'Exploración Rápida' : 'Conexión Segura'}</h4>
              <p>{uxMode === 'simple' ? 'Visualiza miles de registros de forma inmediata con paginación fluida y filtros instantáneos.' : 'Las consultas dinámicas de tablas son previamente verificadas contra el esquema para evitar Inyecciones SQL.'}</p>
            </div>
            <div className="guide-box">
              <h4>{uxMode === 'simple' ? 'Exportación a Excel' : 'Rendimiento Máximo'}</h4>
              <p>{uxMode === 'simple' ? 'Descarga la tabla completa o reportes personalizados directamente en formato Microsoft Excel (.xlsx).' : 'Los datos son paginados nativamente en SQL Server y servidos a través de un pool de conexiones optimizado con HikariCP.'}</p>
            </div>
          </div>
        </div>
      </div>
    );
  }

  // Helper para generar la letra de columna estilo Excel (A, B, C... Z, AA, AB...)
  const getColumnLabel = (index) => {
    let label = "";
    let temp = index;
    while (temp >= 0) {
      label = String.fromCharCode((temp % 26) + 65) + label;
      temp = Math.floor(temp / 26) - 1;
    }
    return label;
  };

  return (
    <div className="spreadsheet-container">
      {/* Vista de cabecera de la hoja */}
      <div className="sheet-header">
        <div className="sheet-header-title">
          {uxMode === 'advanced' && <span className="schema-badge">{activeTable.schema}</span>}
          <div className="sheet-header-text">
            <h2 title={`${activeTable.schema}.${activeTable.name}`}>{activeTable.name}</h2>
            <p className="sheet-header-meta">
              {isDataLoading
                ? 'Cargando registros…'
                : `${(totalRows || 0).toLocaleString('es-CL')} ${totalRows === 1 ? 'registro' : 'registros'}${appliedFilter && appliedFilter.column ? ' · con filtro aplicado' : ''}`}
            </p>
          </div>
        </div>
        
        {/* Pestañas de Vista: Registros / Estructura */}
        <div className="sheet-view-toggle" role="tablist">
          <button 
            role="tab"
            aria-selected={activeTab === 'data'}
            className={`excel-btn ${activeTab === 'data' ? 'primary' : ''}`}
            onClick={() => setActiveTab('data')}
            title="Ver y explorar los registros de la tabla actual estilo Excel"
          >
            <FileSpreadsheet size={14} />
            <span className="label-long">Hojas de Registros</span>
            <span className="label-short">Registros</span>
          </button>
          <button 
            role="tab"
            aria-selected={activeTab === 'schema'}
            className={`excel-btn ${activeTab === 'schema' ? 'primary' : ''}`}
            onClick={() => setActiveTab('schema')}
            title="Administrar la estructura de columnas y configurar traducciones de Claves Foráneas (FK)"
          >
            <Table size={14} />
            <span className="label-long">Estructura de Columnas</span>
            <span className="label-short">Estructura</span>
          </button>
        </div>
      </div>

      <FilterBar
        active={activeTab === 'data'}
        columns={columns}
        appliedFilter={appliedFilter}
        activeTable={activeTable}
        onApplyFilter={onApplyFilter}
        dataLength={data.length}
        totalRows={totalRows}
      />

      {/* Grid Principal */}
      <div className="grid-wrapper">
        {isDataLoading && (
          <div className="view-state-screen">
            <div className="excel-spinner"></div>
            <p>Calculando hoja y cargando registros...</p>
          </div>
        )}

        {error && (
          <div className="view-state-screen">
            <div className="error-card">
              <h3>Error de Conexión o Lectura</h3>
              <p>{error}</p>
            </div>
          </div>
        )}

        {!isDataLoading && !error && activeTab === 'data' && (
          <>
            {data.length === 0 ? (
              <div className="data-empty">
                <Table className="empty-icon" />
                <p>Esta tabla no contiene ningún registro en la base de datos.</p>
              </div>
            ) : (
              <table className="excel-grid">
                <thead>
                  <tr>
                    {/* Celda de esquina superior izquierda de Excel */}
                    <th aria-label="Número de fila"></th>
                    {Object.keys(data[0]).map((colName, index) => {
                      const fk = fkByColumn.get(colName);
                      let thTitle = `Columna física: ${colName}`;
                      if (fk) {
                        thTitle = `Clave Foránea (FK) traducida → Apunta a: ${fk.referencedSchema}.${fk.referencedTable}(${fk.referencedColumn})`;
                        if (fk.filterColumn && fk.filterValue) {
                          thTitle += ` filtrado por: ${fk.filterColumn} = '${fk.filterValue}'`;
                        }
                      }
                      // Solo presentación: el encabezado de una columna numérica se alinea a la
                      // derecha igual que sus cifras (misma regla que .cell-numeric de las celdas).
                      const isNumericCol = !fk && typeof data[0][colName] === 'number';
                      return (
                        <th
                          key={colName}
                          className={isNumericCol ? 'th-numeric' : undefined}
                          title={thTitle}
                        >
                          <span className="col-letter">{getColumnLabel(index)}</span>
                          {colName}
                          {fk && <Link2 size={11} className="fk-badge" title="Esta columna tiene una traducción de relación activa" />}
                        </th>
                      );
                    })}
                  </tr>
                </thead>
                <tbody>
                  {data.map((row, rowIndex) => {
                    const rowNum = offset + rowIndex + 1;
                    return (
                      <tr key={rowIndex}>
                        {/* Indicador de número de fila estilo Excel */}
                        <td className="row-number">{rowNum}</td>
                        {Object.keys(row).map((colName) => {
                          const val = row[colName];
                          const isNull = val === null;
                          const isNum = typeof val === 'number';
                          const fk = fkByColumn.get(colName);

                          let cellContent;
                          let cellTitle;
                          if (fk && fkDisplayMode !== 'id') {
                            const idText = isNull ? (uxMode === 'simple' ? '—' : 'NULL') : String(val);
                            const realText = fkStatusText(fkResolutions[colName]?.[rowIndex]);
                            if (fkDisplayMode === 'real') {
                              cellContent = realText ?? (isNull ? '—' : idText);
                            } else {
                              cellContent = realText ? `${idText} · ${realText}` : idText;
                            }
                            cellTitle = cellContent;
                          } else {
                            // Solo presentación: en Modo Fácil un vacío se muestra como "—"
                            // (más comprensible que NULL); el Modo DBA conserva NULL.
                            const nullText = uxMode === 'simple' ? '—' : 'NULL';
                            cellContent = isNull ? nullText : String(val);
                            cellTitle = isNull && uxMode === 'simple' ? 'Sin dato' : cellContent;
                          }

                          return (
                            <td
                              key={colName}
                              className={`${isNull ? 'cell-null' : ''} ${isNum && !fk ? 'cell-numeric' : ''}`}
                              title={cellTitle}
                            >
                              {cellContent}
                            </td>
                          );
                        })}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </>
        )}

        {!isDataLoading && !error && activeTab === 'schema' && (
          <div className="schema-view">
            {/* Buscador de Columnas Resiliente a Espacios y Guiones Bajos */}
            <div className="schema-toolbar">
              <div className="excel-search-bar">
                <label className="schema-search-label" htmlFor="schema-search-input">
                  Buscar Columna:
                </label>
                <input
                  id="schema-search-input"
                  type="text"
                  className="excel-input schema-search-input"
                  placeholder="Ej: depto id o nombre_cliente..."
                  value={schemaSearchQuery}
                  onChange={(e) => setSchemaSearchQuery(e.target.value)}
                  title="Filtra la lista inferior para encontrar una columna física por su nombre (ignora espacios y guiones bajos)"
                />
                {schemaSearchQuery && (
                  <button 
                    className="excel-btn"
                    onClick={() => setSchemaSearchQuery('')}
                    title="Limpiar el término de búsqueda actual para mostrar todas las columnas"
                  >
                    Limpiar
                  </button>
                )}
              </div>
              <span className="schema-tip">
                💡 <strong>Consejo:</strong> Vincula columnas de códigos/IDs a otras tablas auxiliares para traducirlos a nombres descriptivos reales en la grilla y en el reporte de Excel.
              </span>
            </div>

            <div className="schema-table-wrapper">
              <table className="schema-table">
                <thead>
                  <tr>
                    <th aria-label="Número"></th>
                    <th title="Nombre técnico de la columna en la base de datos SQL Server">Nombre de la Columna</th>
                    <th title="Tipo de dato físico de la columna (ej: int, varchar, datetime)">Tipo de Dato</th>
                    <th title="Longitud de caracteres máxima de la columna (si aplica)">Longitud Máxima</th>
                    <th title="Determina si la columna permite almacenar valores nulos o vacíos">Acepta Nulos (Nullable)</th>
                    <th title="Muestra el estado de la traducción y permite vincular o editar la relación con otra tabla">Relación (Foreign Key)</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredColumns.map((col, index) => {
                    const fk = fkByColumn.get(col.name);
                    return (
                      <tr key={col.name}>
                        <td className="row-number">{index + 1}</td>
                        <td className="schema-col-name">{col.name}</td>
                        <td><span className="schema-type">{col.type}</span></td>
                        <td>{col.size > 0 ? col.size : '-'}</td>
                        <td>
                          <span className={col.nullable ? 'nullable-yes' : 'nullable-no'}>
                            {col.nullable ? 'Sí' : 'No Nulo'}
                          </span>
                        </td>
                        <td>
                          {editingColName === col.name ? (
                            <div className="fk-inline-form">
                              <div className="fk-inline-row">
                                {/* Dropdown de Tablas Reales */}
                                <span className="fk-inline-label">Tabla:</span>
                                <select
                                  className="fk-select"
                                  value={selectedTargetTableKey}
                                  onChange={(e) => {
                                    const val = e.target.value;
                                    setSelectedTargetTableKey(val);
                                    setSelectedDisplayColumns([]); // Reset display columns
                                    setSelectedFilterColumn("");
                                    setFilterValueText("");
                                    loadColumnsForTargetTable(val);
                                  }}
                                  title="Selecciona la tabla auxiliar o relacionada que contiene los valores que quieres mostrar"
                                >
                                  <option value="">-- Seleccionar --</option>
                                  {tables.map(t => (
                                    <option key={`${t.schema}.${t.name}`} value={`${t.schema}.${t.name}`}>
                                      {t.schema.toLowerCase() === 'dbo' ? t.name : `${t.schema}.${t.name}`}
                                    </option>
                                  ))}
                                </select>

                                {/* Dropdown de PK de Vinculación */}
                                <span className="fk-inline-label">Columna ID:</span>
                                <select
                                  className="fk-select"
                                  value={selectedTargetColumn}
                                  onChange={(e) => setSelectedTargetColumn(e.target.value)}
                                  disabled={!selectedTargetTableKey || columnsOfTargetTable.length === 0}
                                  title="Selecciona la columna clave (ID/Código) en la tabla destino que coincide con el ID de la columna origen"
                                >
                                  <option value="">-- Seleccionar --</option>
                                  {columnsOfTargetTable.map(c => (
                                    <option key={c.name} value={c.name}>
                                      {c.name} ({c.type})
                                    </option>
                                  ))}
                                </select>

                                {/* Guardar / Cancelar */}
                                <button
                                  className="fk-action-btn save"
                                  aria-label="Guardar relación"
                                  title="Guardar esta relación y aplicarla inmediatamente a los datos de la grilla"
                                  onClick={() => {
                                    if (!selectedTargetTableKey || !selectedTargetColumn) return;
                                    const [schema, name] = selectedTargetTableKey.split('.');
                                    const displayColsString = selectedDisplayColumns.join(', ');
                                    onSaveCustomFk(
                                      col.name, 
                                      schema, 
                                      name, 
                                      selectedTargetColumn, 
                                      displayColsString,
                                      selectedFilterColumn,
                                      filterValueText
                                    );
                                    setEditingColName(null);
                                  }}
                                  disabled={!selectedTargetTableKey || !selectedTargetColumn}
                                >
                                  <Check size={14} />
                                </button>
                                <button
                                  className="fk-action-btn cancel"
                                  aria-label="Cancelar"
                                  title="Cancelar los cambios y cerrar el formulario de vinculación"
                                  onClick={() => setEditingColName(null)}
                                >
                                  <X size={14} />
                                </button>
                              </div>

                              {/* Selección de Valores a Mostrar */}
                              {columnsOfTargetTable.length > 0 && (
                                <div className="fk-inline-block">
                                  <span className="fk-inline-label fk-inline-label--sm">Valores a Mostrar (Varios para Concatenar):</span>
                                  <div 
                                    className="fk-display-cols"
                                    title="Marca una o más columnas descriptivas para mostrar en la grilla y el reporte final (ej. si marcas 'nombre' y 'jornal', se verá 'Jefe - $1200')"
                                  >
                                    {columnsOfTargetTable.map(c => {
                                      const isChecked = selectedDisplayColumns.includes(c.name);
                                      return (
                                        <label key={c.name} className={`fk-display-col ${isChecked ? 'checked' : ''}`}>
                                          <input
                                            type="checkbox"
                                            checked={isChecked}
                                            onChange={(e) => {
                                              if (e.target.checked) {
                                                setSelectedDisplayColumns([...selectedDisplayColumns, c.name]);
                                              } else {
                                                setSelectedDisplayColumns(selectedDisplayColumns.filter(item => item !== c.name));
                                              }
                                            }}
                                          />
                                          {c.name}
                                        </label>
                                      );
                                    })}
                                  </div>
                                </div>
                              )}

                              {/* Filtro Discriminador (Opcional) */}
                              {columnsOfTargetTable.length > 0 && (
                                <div className="fk-inline-block fk-inline-block--divider">
                                  <span className="fk-inline-label fk-inline-label--sm">Filtro Discriminador (Opcional - para IDs duplicados en tablas compartidas):</span>
                                  <div className="fk-inline-row">
                                    <span className="fk-inline-hint">Columna Filtro:</span>
                                    <select
                                      className="fk-select"
                                      value={selectedFilterColumn}
                                      onChange={(e) => setSelectedFilterColumn(e.target.value)}
                                      title="Columna usada como filtro (ej: la columna que indica si el registro es Cargo, Departamento, etc.)"
                                    >
                                      <option value="">-- Sin Filtro --</option>
                                      {columnsOfTargetTable.map(c => (
                                        <option key={c.name} value={c.name}>
                                          {c.name}
                                        </option>
                                      ))}
                                    </select>
                                    
                                    <span className="fk-inline-hint">Valor Filtro:</span>
                                    <input
                                      type="text"
                                      className="excel-input fk-filter-input"
                                      placeholder="Ej: CARGOS (3)"
                                      value={filterValueText}
                                      onChange={(e) => setFilterValueText(e.target.value)}
                                      disabled={!selectedFilterColumn}
                                      title="Escribe el valor exacto por el cual filtrar (ej: CARGOS (3)). Solo se habilitará si elegiste una columna de filtro."
                                    />
                                  </div>
                                </div>
                              )}
                            </div>
                          ) : (
                            <div className="fk-relation-cell">
                              {fk ? (
                                <>
                                  <span className={`fk-info-text ${fk.enabled ? '' : 'is-disabled'}`} title="Detalles completos de la relación guardada">
                                    <Link2 size={12} className="text-green" /> 
                                    <span>{formatFkName(fk)}</span>
                                  </span>
                                  <div className="fk-relation-actions">
                                    {/* Switch Toggle con Etiqueta Descriptiva */}
                                    <div className="fk-toggle-container" title="Activar/desactivar la traducción en la grilla y reportes. Si se desactiva, volverás a ver el ID original.">
                                      <span className={`fk-toggle-label ${fk.enabled ? 'active' : 'inactive'}`}>
                                        {fk.enabled ? '✓ Traducir' : '✗ ID Crudo'}
                                      </span>
                                      <label className="excel-switch">
                                        <input 
                                          type="checkbox" 
                                          aria-label={`Traducir relación de ${col.name}`}
                                          checked={fk.enabled} 
                                          onChange={(e) => onToggleFk(col.name, fk, e.target.checked)} 
                                        />
                                        <span className="excel-switch-slider"></span>
                                      </label>
                                    </div>
                                    {/* Editar */}
                                    <button 
                                      className="fk-action-btn"
                                      aria-label="Editar relación"
                                      title="Editar la configuración de la relación (cambiar tabla, columnas de visualización o filtros)"
                                      onClick={() => {
                                        setEditingColName(col.name);
                                        const tableKey = `${fk.referencedSchema}.${fk.referencedTable}`;
                                        setSelectedTargetTableKey(tableKey);
                                        setSelectedTargetColumn(fk.referencedColumn);
                                        if (fk.displayColumn) {
                                          setSelectedDisplayColumns(fk.displayColumn.split(',').map(s => s.trim()));
                                        } else {
                                          setSelectedDisplayColumns([]);
                                        }
                                        setSelectedFilterColumn(fk.filterColumn || "");
                                        setFilterValueText(fk.filterValue || "");
                                        loadColumnsForTargetTable(tableKey);
                                      }}
                                    >
                                      <Edit2 size={12} />
                                    </button>
                                    {/* Eliminar (Quitar override) */}
                                    <button 
                                      className="fk-action-btn"
                                      aria-label="Eliminar relación"
                                      title="Eliminar permanentemente esta relación y volver a mostrar el ID crudo original"
                                      onClick={() => onDeleteCustomFk(col.name, fk)}
                                    >
                                      <Trash2 size={12} />
                                    </button>
                                  </div>
                                </>
                              ) : (
                                <button 
                                  className="excel-btn excel-btn--compact"
                                  onClick={() => {
                                    setEditingColName(col.name);
                                    setSelectedTargetTableKey("");
                                    setSelectedTargetColumn("");
                                    setSelectedDisplayColumns([]);
                                    setSelectedFilterColumn("");
                                    setFilterValueText("");
                                    setColumnsOfTargetTable([]);
                                  }}
                                  title="Crear una nueva relación para esta columna para traducir automáticamente sus IDs a valores reales de otra tabla"
                                >
                                  <Link2 size={11} />
                                  Vincular Tabla
                                </button>
                              )}
                            </div>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
