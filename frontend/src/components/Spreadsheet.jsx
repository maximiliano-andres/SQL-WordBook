import React, { useState, useEffect } from 'react';
import { Table, FileSpreadsheet, Link2, Check, X, Edit2, Trash2, Filter } from 'lucide-react';
import { fkStatusText } from '../utils/fk';

export default function Spreadsheet({
  activeTable,
  data,
  columns,
  isDataLoading,
  error,
  totalRows,
  offset,
  limit,
  fkColumns = [],
  fkResolutions = {},
  fkDisplayMode = 'both',
  tables = [],
  onSaveCustomFk = () => {},
  onToggleFk = () => {},
  onDeleteCustomFk = () => {},
  appliedFilter = null,
  onApplyFilter = () => {}
}) {
  const [activeTab, setActiveTab] = useState('data'); // 'data' o 'schema'
  const [editingColName, setEditingColName] = useState(null);
  const [selectedTargetTableKey, setSelectedTargetTableKey] = useState("");
  const [selectedTargetColumn, setSelectedTargetColumn] = useState("");
  const [columnsOfTargetTable, setColumnsOfTargetTable] = useState([]);
  const [selectedDisplayColumns, setSelectedDisplayColumns] = useState([]);
  const [selectedFilterColumn, setSelectedFilterColumn] = useState("");
  const [filterValueText, setFilterValueText] = useState("");
  const [schemaSearchQuery, setSchemaSearchQuery] = useState("");

  // Estados locales para el panel de filtrado de filas
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
      const res = await fetch(`/api/db/tables/${encodeURIComponent(schema)}/${encodeURIComponent(name)}/columns`);
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

  const fkByColumn = new Map(fkColumns.map(fk => [fk.column, fk]));

  if (!activeTable) {
    return (
      <div className="welcome-workbook-view">
        <div className="welcome-sheet">
          <h2>Libro de Trabajo <span>SQL Server</span></h2>
          <p>Selecciona una de las tablas disponibles en las pestañas inferiores ("Hojas de cálculo") para cargar su información de forma segura y optimizada.</p>
          <div className="sheet-guide-grid">
            <div className="guide-box">
              <h4>Conexión Segura</h4>
              <p>Las consultas dinámicas de tablas son previamente verificadas contra el esquema para evitar Inyecciones SQL.</p>
            </div>
            <div className="guide-box">
              <h4>Rendimiento Máximo</h4>
              <p>Los datos son paginados nativamente en SQL Server y servidos a través de un pool de conexiones optimizado con HikariCP.</p>
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
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 16px', borderBottom: '1px solid var(--excel-border)', backgroundColor: 'var(--excel-bg-sidebar)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <span className="schema-badge">{activeTable.schema}</span>
          <h2 style={{ fontSize: '18px', fontWeight: 600 }}>{activeTable.name}</h2>
        </div>
        
        {/* Pestañas de Vista: Registros / Estructura */}
        <div style={{ display: 'flex', gap: '8px' }}>
          <button 
            className={`excel-btn ${activeTab === 'data' ? 'primary' : ''}`}
            onClick={() => setActiveTab('data')}
            title="Ver y explorar los registros de la tabla actual estilo Excel"
          >
            <FileSpreadsheet size={14} />
            Hojas de Registros
          </button>
          <button 
            className={`excel-btn ${activeTab === 'schema' ? 'primary' : ''}`}
            onClick={() => setActiveTab('schema')}
            title="Administrar la estructura de columnas y configurar traducciones de Claves Foráneas (FK)"
          >
            <Table size={14} />
            Estructura de Columnas
          </button>
        </div>
      </div>

      {activeTab === 'data' && (
        <div className="filter-section" style={{ borderBottom: '1px solid var(--excel-border)', padding: '10px 16px', backgroundColor: 'var(--excel-bg-sidebar)', display: 'flex', flexDirection: 'column', gap: '10px' }}>
          
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '10px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
              <button 
                className={`excel-btn ${showFilterPanel ? 'primary' : ''}`}
                onClick={() => {
                  setShowFilterPanel(!showFilterPanel);
                  if (!tempFilterColumn && columns.length > 0) {
                    setTempFilterColumn(columns[0].name);
                  }
                }}
                title="Abrir o cerrar el panel de filtros de fila"
              >
                <Filter size={14} />
                {showFilterPanel ? 'Ocultar Filtros' : 'Filtrar Registros'}
              </button>

              {appliedFilter && appliedFilter.column && (
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px', backgroundColor: '#e2f0d9', border: '1px solid #a9d08e', borderRadius: '4px', padding: '4px 10px', fontSize: '12px', color: '#385723', fontWeight: '500' }}>
                  <span>
                    Filtro activo: <strong>{appliedFilter.column}</strong> {getOperatorLabel(appliedFilter.operator)}{' '}
                    {appliedFilter.operator === 'BETWEEN'
                      ? `"${appliedFilter.value}" y "${appliedFilter.value2}"`
                      : !isUnaryOperator(appliedFilter.operator) && `"${appliedFilter.value}"`}
                  </span>
                  <button 
                    onClick={handleClearFilter}
                    style={{ background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', padding: '2px', color: '#c00000' }}
                    title="Eliminar filtro activo"
                  >
                    <X size={14} />
                  </button>
                </div>
              )}
            </div>

            {appliedFilter && appliedFilter.column && (
              <span style={{ fontSize: '11.5px', color: 'var(--text-secondary)', fontStyle: 'italic' }}>
                Mostrando {data.length} de {totalRows} registros coincidentes.
              </span>
            )}
          </div>

          {showFilterPanel && (
            <div style={{ display: 'flex', alignItems: 'center', gap: '12px', backgroundColor: 'var(--excel-border)', padding: '12px', borderRadius: '4px', flexWrap: 'wrap', border: '1px solid var(--excel-border)' }}>
              
              <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                <span style={{ fontSize: '11px', fontWeight: '600', color: 'var(--text-secondary)' }}>Columna:</span>
                <select 
                  className="excel-input"
                  style={{ height: '30px', padding: '0 8px', fontSize: '12px', minWidth: '150px' }}
                  value={tempFilterColumn}
                  onChange={(e) => setTempFilterColumn(e.target.value)}
                >
                  {columns.map(col => (
                    <option key={col.name} value={col.name}>{col.name}</option>
                  ))}
                </select>
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                <span style={{ fontSize: '11px', fontWeight: '600', color: 'var(--text-secondary)' }}>Condición:</span>
                <select 
                  className="excel-input"
                  style={{ height: '30px', padding: '0 8px', fontSize: '12px', minWidth: '150px' }}
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
              </div>

              {tempFilterOperator === 'BETWEEN' ? (
                <>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                    <span style={{ fontSize: '11px', fontWeight: '600', color: 'var(--text-secondary)' }}>Desde:</span>
                    <input 
                      type="text" 
                      className="excel-input"
                      style={{ height: '30px', padding: '0 8px', fontSize: '12px', minWidth: '120px', cursor: 'text' }}
                      placeholder="Valor inicial..."
                      value={tempFilterValue}
                      onChange={(e) => setTempFilterValue(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') handleApplyFilter();
                      }}
                    />
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                    <span style={{ fontSize: '11px', fontWeight: '600', color: 'var(--text-secondary)' }}>Hasta:</span>
                    <input 
                      type="text" 
                      className="excel-input"
                      style={{ height: '30px', padding: '0 8px', fontSize: '12px', minWidth: '120px', cursor: 'text' }}
                      placeholder="Valor final..."
                      value={tempFilterValue2}
                      onChange={(e) => setTempFilterValue2(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') handleApplyFilter();
                      }}
                    />
                  </div>
                </>
              ) : !isUnaryOperator(tempFilterOperator) && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                  <span style={{ fontSize: '11px', fontWeight: '600', color: 'var(--text-secondary)' }}>Valor:</span>
                  <input 
                    type="text" 
                    className="excel-input"
                    style={{ height: '30px', padding: '0 8px', fontSize: '12px', minWidth: '180px', cursor: 'text' }}
                    placeholder="Ej: valor de búsqueda..."
                    value={tempFilterValue}
                    onChange={(e) => setTempFilterValue(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') handleApplyFilter();
                    }}
                  />
                </div>
              )}

              <div style={{ display: 'flex', gap: '8px', alignSelf: 'flex-end', marginTop: '16px' }}>
                <button 
                  className="excel-btn primary"
                  style={{ height: '30px', padding: '0 16px', fontSize: '12px', backgroundColor: 'var(--excel-green)', borderColor: 'var(--excel-green)' }}
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
                  style={{ height: '30px', padding: '0 12px', fontSize: '12px' }}
                  onClick={handleClearFilter}
                  title="Restablecer filtros y mostrar todos los registros"
                >
                  Limpiar
                </button>
              </div>

            </div>
          )}

        </div>
      )}

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
                    <th style={{ width: '50px' }}></th>
                    {Object.keys(data[0]).map((colName, index) => {
                      const fk = fkByColumn.get(colName);
                      let thTitle = `Columna física: ${colName}`;
                      if (fk) {
                        thTitle = `Clave Foránea (FK) traducida → Apunta a: ${fk.referencedSchema}.${fk.referencedTable}(${fk.referencedColumn})`;
                        if (fk.filterColumn && fk.filterValue) {
                          thTitle += ` filtrado por: ${fk.filterColumn} = '${fk.filterValue}'`;
                        }
                      }
                      return (
                        <th
                          key={colName}
                          style={{ minWidth: '150px' }}
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
                            const idText = isNull ? 'NULL' : String(val);
                            const realText = fkStatusText(fkResolutions[colName]?.[rowIndex]);
                            if (fkDisplayMode === 'real') {
                              cellContent = realText ?? (isNull ? '—' : idText);
                            } else {
                              cellContent = realText ? `${idText} · ${realText}` : idText;
                            }
                            cellTitle = cellContent;
                          } else {
                            cellContent = isNull ? 'NULL' : String(val);
                            cellTitle = cellContent;
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
          <div style={{ padding: '24px' }}>
            {/* Buscador de Columnas Resiliente a Espacios y Guiones Bajos */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px', flexWrap: 'wrap', gap: '12px' }}>
              <div className="excel-search-bar" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <span style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)' }}>
                  Buscar Columna:
                </span>
                <input
                  type="text"
                  className="excel-input"
                  style={{ width: '250px', height: '30px', padding: '4px 8px', fontSize: '12px', cursor: 'text' }}
                  placeholder="Ej: depto id o nombre_cliente..."
                  value={schemaSearchQuery}
                  onChange={(e) => setSchemaSearchQuery(e.target.value)}
                  title="Filtra la lista inferior para encontrar una columna física por su nombre (ignora espacios y guiones bajos)"
                />
                {schemaSearchQuery && (
                  <button 
                    className="excel-btn" 
                    style={{ height: '30px', padding: '0 12px', fontSize: '11px', backgroundColor: 'var(--excel-border)', color: 'var(--text-primary)' }}
                    onClick={() => setSchemaSearchQuery('')}
                    title="Limpiar el término de búsqueda actual para mostrar todas las columnas"
                  >
                    Limpiar
                  </button>
                )}
              </div>
              <span style={{ fontSize: '11.5px', color: 'var(--text-secondary)', fontStyle: 'italic', backgroundColor: 'var(--excel-bg-sidebar)', padding: '6px 12px', borderRadius: '4px', border: '1px solid var(--excel-border)' }}>
                💡 <strong>Consejo:</strong> Vincula columnas de códigos/IDs a otras tablas auxiliares para traducirlos a nombres descriptivos reales en la grilla y en el reporte de Excel.
              </span>
            </div>

            <div className="schema-table-wrapper">
              <table className="schema-table">
                <thead>
                  <tr>
                    <th style={{ width: '50px' }}></th>
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
                        <td style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{col.name}</td>
                        <td><span className="schema-type">{col.type}</span></td>
                        <td>{col.size > 0 ? col.size : '-'}</td>
                        <td>
                          <span className={col.nullable ? 'nullable-yes' : 'nullable-no'}>
                            {col.nullable ? 'Sí' : 'No Nulo'}
                          </span>
                        </td>
                        <td>
                          {editingColName === col.name ? (
                            <div className="fk-inline-form" style={{ flexDirection: 'column', alignItems: 'flex-start', gap: '10px' }}>
                              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
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
                                  title="Cancelar los cambios y cerrar el formulario de vinculación"
                                  onClick={() => setEditingColName(null)}
                                >
                                  <X size={14} />
                                </button>
                              </div>

                              {/* Selección de Valores a Mostrar */}
                              {columnsOfTargetTable.length > 0 && (
                                <div style={{ display: 'flex', flexDirection: 'column', gap: '4px', width: '100%' }}>
                                  <span className="fk-inline-label" style={{ fontSize: '10px', color: 'var(--text-secondary)' }}>Valores a Mostrar (Varios para Concatenar):</span>
                                  <div 
                                    style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', backgroundColor: 'var(--excel-bg-app)', padding: '6px 10px', borderRadius: '4px', border: '1px solid var(--excel-border)', maxWidth: '500px' }}
                                    title="Marca una o más columnas descriptivas para mostrar en la grilla y el reporte final (ej. si marcas 'nombre' y 'jornal', se verá 'Jefe - $1200')"
                                  >
                                    {columnsOfTargetTable.map(c => {
                                      const isChecked = selectedDisplayColumns.includes(c.name);
                                      return (
                                        <label key={c.name} style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '11px', cursor: 'pointer', color: isChecked ? 'var(--text-primary)' : 'var(--text-secondary)', userSelect: 'none' }}>
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
                                            style={{ cursor: 'pointer' }}
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
                                <div style={{ display: 'flex', flexDirection: 'column', gap: '4px', width: '100%', borderTop: '1px dashed var(--excel-border)', paddingTop: '8px', marginTop: '4px' }}>
                                  <span className="fk-inline-label" style={{ fontSize: '10px', color: 'var(--text-secondary)' }}>Filtro Discriminador (Opcional - para IDs duplicados en tablas compartidas):</span>
                                  <div style={{ display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' }}>
                                    <span style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>Columna Filtro:</span>
                                    <select
                                      className="fk-select"
                                      value={selectedFilterColumn}
                                      onChange={(e) => setSelectedFilterColumn(e.target.value)}
                                      style={{ maxWidth: '140px' }}
                                      title="Columna usada como filtro (ej: la columna que indica si el registro es Cargo, Departamento, etc.)"
                                    >
                                      <option value="">-- Sin Filtro --</option>
                                      {columnsOfTargetTable.map(c => (
                                        <option key={c.name} value={c.name}>
                                          {c.name}
                                        </option>
                                      ))}
                                    </select>
                                    
                                    <span style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>Valor Filtro:</span>
                                    <input
                                      type="text"
                                      className="excel-input"
                                      style={{ width: '180px', height: '26px', padding: '2px 8px', fontSize: '11px', cursor: 'text' }}
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
                                  <span className="fk-info-text" style={{ opacity: fk.enabled ? 1 : 0.5 }} title="Detalles completos de la relación guardada">
                                    <Link2 size={12} className="text-green" /> 
                                    <span style={{ fontSize: '12px', fontWeight: 500 }}>{formatFkName(fk)}</span>
                                  </span>
                                  <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                                    {/* Switch Toggle con Etiqueta Descriptiva */}
                                    <div className="fk-toggle-container" title="Activar/desactivar la traducción en la grilla y reportes. Si se desactiva, volverás a ver el ID original.">
                                      <span className={`fk-toggle-label ${fk.enabled ? 'active' : 'inactive'}`}>
                                        {fk.enabled ? '✓ Traducir' : '✗ ID Crudo'}
                                      </span>
                                      <label className="excel-switch">
                                        <input 
                                          type="checkbox" 
                                          checked={fk.enabled} 
                                          onChange={(e) => onToggleFk(col.name, fk, e.target.checked)} 
                                        />
                                        <span className="excel-switch-slider"></span>
                                      </label>
                                    </div>
                                    {/* Editar */}
                                    <button 
                                      className="fk-action-btn"
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
                                      title="Eliminar permanentemente esta relación y volver a mostrar el ID crudo original"
                                      onClick={() => onDeleteCustomFk(col.name, fk)}
                                    >
                                      <Trash2 size={12} />
                                    </button>
                                  </div>
                                </>
                              ) : (
                                <button 
                                  className="excel-btn" 
                                  style={{ padding: '4px 10px', fontSize: '11px', height: 'auto', display: 'flex', alignItems: 'center', gap: '4px' }}
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
