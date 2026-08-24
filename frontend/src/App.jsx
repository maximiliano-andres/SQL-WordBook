import React, { useState, useEffect, useCallback } from 'react';
import Ribbon from './components/Ribbon';
import Spreadsheet from './components/Spreadsheet';
import SheetTabs from './components/SheetTabs';
import StatusBar from './components/StatusBar';
import { fkStatusText } from './utils/fk';
import { Server, X, BookOpen, Layers, Link2, Download, Lightbulb } from 'lucide-react';

// Caracteres que Excel/Sheets pueden interpretar como inicio de fórmula al
// abrir un CSV exportado ("CSV/Formula Injection"). Se neutralizan con un
// apóstrofe inicial, igual que en el exportador Excel del backend.
const RISKY_SPREADSHEET_PREFIXES = ['=', '+', '-', '@', '\t', '\r'];
function sanitizeForSpreadsheet(value) {
  const str = String(value);
  return RISKY_SPREADSHEET_PREFIXES.includes(str.charAt(0)) ? `'${str}` : str;
}

export default function App() {
  // Estados de Base de Datos
  const [dbInfo, setDbInfo] = useState(null);
  const [tables, setTables] = useState([]);
  const [activeTable, setActiveTable] = useState(null); // { schema, name }

  // Estados de Datos de la Tabla Activa
  const [data, setData] = useState([]);
  const [columns, setColumns] = useState([]);
  const [selectedColumns, setSelectedColumns] = useState([]);
  const [loadedTableKey, setLoadedTableKey] = useState("");
  // Esquema de columnas cacheado por tabla ("schema.nombre") para no
  // refetchear /columns en cada cambio de página o límite de filas.
  const [columnsCache, setColumnsCache] = useState({});

  // Foreign Keys detectadas y resueltas para la página actual (llegan ya calculadas
  // en /data, ver DatabaseService.resolveForeignKeys). fkDisplayMode: 'id' | 'real' | 'both'.
  const [fkColumns, setFkColumns] = useState([]);
  const [fkResolutions, setFkResolutions] = useState({});
  const [fkDisplayMode, setFkDisplayMode] = useState('both');

  // Estados de Paginación
  const [limit, setLimit] = useState(15);
  const [page, setPage] = useState(1);
  const [totalRows, setTotalRows] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  
  // Estados de Control
  const [isDataLoading, setIsDataLoading] = useState(false);
  const [error, setError] = useState(null);
  const [responseTime, setResponseTime] = useState(null); // en ms
  const [showDbaConsole, setShowDbaConsole] = useState(false);
  const [showManual, setShowManual] = useState(false);
  const [manualTab, setManualTab] = useState('general');
  const [appliedFilter, setAppliedFilter] = useState(null); // { column, operator, value }

  // --- LLAMADA API: INFORMACIÓN CONEXIÓN ---
  const fetchDbInfo = useCallback(async () => {
    try {
      const res = await fetch('/api/db/info');
      if (!res.ok) throw new Error('Error al conectar con la API de base de datos.');
      const info = await res.json();
      setDbInfo(info);
    } catch (err) {
      console.error(err);
      setError('Error al obtener la información de conexión.');
    }
  }, []);

  // --- LLAMADA API: LISTA DE TABLAS (HOJAS DE TRABAJO) ---
  const fetchTablesList = useCallback(async () => {
    try {
      const res = await fetch('/api/db/tables');
      if (!res.ok) throw new Error('Error al recuperar las hojas de trabajo.');
      const list = await res.json();
      setTables(list);
    } catch (err) {
      console.error(err);
      setError('Error al recuperar las tablas del servidor SQL.');
    }
  }, []);

  // --- LLAMADA API: DATOS Y ESTRUCTURA (COMBINADOS) ---
  // El esquema de columnas de una tabla no cambia entre páginas, así que se
  // cachea por tabla (columnsCache) y solo se vuelve a pedir al cambiar de tabla.
  const fetchTableDataAndSchema = useCallback(async () => {
    if (!activeTable) return;

    setIsDataLoading(true);
    setError(null);
    const startTime = performance.now();

    const offset = (page - 1) * limit;
    const tableKey = `${activeTable.schema}.${activeTable.name}`;
    let dataUrl = `/api/db/tables/${encodeURIComponent(activeTable.schema)}/${encodeURIComponent(activeTable.name)}/data?limit=${limit}&offset=${offset}`;
    
    if (appliedFilter && appliedFilter.column) {
      dataUrl += `&filterColumn=${encodeURIComponent(appliedFilter.column)}&filterOperator=${encodeURIComponent(appliedFilter.operator)}&filterValue=${encodeURIComponent(appliedFilter.value)}`;
      if (appliedFilter.value2) {
        dataUrl += `&filterValue2=${encodeURIComponent(appliedFilter.value2)}`;
      }
    }
    
    const schemaUrl = `/api/db/tables/${encodeURIComponent(activeTable.schema)}/${encodeURIComponent(activeTable.name)}/columns`;
    // Solo se necesita pedir el esquema si no está cacheado para esta tabla; se lanza en
    // paralelo con los datos (en vez de esperar a que termine /data) para no sumar su
    // latencia a la primera carga de cada hoja.
    const needsSchema = !columnsCache[tableKey];

    try {
      const [dataRes, schemaRes] = await Promise.all([
        fetch(dataUrl),
        needsSchema ? fetch(schemaUrl) : Promise.resolve(null)
      ]);

      if (!dataRes.ok) {
        const errJson = await dataRes.json();
        throw new Error(errJson.error || 'Error al leer registros.');
      }
      const pageResult = await dataRes.json();

      let columnsList = columnsCache[tableKey];
      if (needsSchema) {
        if (!schemaRes.ok) throw new Error('Error al recuperar esquema de columnas.');
        columnsList = await schemaRes.json();
        setColumnsCache(prev => ({ ...prev, [tableKey]: columnsList }));
      }

      // Guardar estados
      setData(pageResult.data);
      setTotalRows(pageResult.totalRows);
      setTotalPages(pageResult.totalPages || 1);
      setColumns(columnsList);
      setFkColumns(pageResult.fkColumns || []);
      setFkResolutions(pageResult.fkResolutions || {});

      if (loadedTableKey !== tableKey) {
        setSelectedColumns(columnsList.map(c => c.name));
        setLoadedTableKey(tableKey);
      }

      // Calcular tiempo de respuesta
      const endTime = performance.now();
      setResponseTime(Math.round(endTime - startTime));
    } catch (err) {
      console.error(err);
      setError(err.message);
      setData([]);
    } finally {
      setIsDataLoading(false);
    }
  }, [activeTable, limit, page, columnsCache, loadedTableKey, appliedFilter]);

  // 1. Cargar metadatos iniciales de conexión y tablas al montar
  useEffect(() => {
    fetchDbInfo();
    fetchTablesList();
  }, [fetchDbInfo, fetchTablesList]);

  // 2. Recargar datos cuando cambia la tabla activa, el límite de filas o la página
  useEffect(() => {
    if (activeTable) {
      fetchTableDataAndSchema();
    }
  }, [activeTable, limit, page, fetchTableDataAndSchema]);

  // --- ACCIÓN: SELECCIONAR NUEVA HOJA (TABLA) ---
  const handleSelectTable = (table) => {
    setActiveTable(table);
    setPage(1); // Reiniciar a la primera página siempre
    setAppliedFilter(null); // Resetear el filtro al cambiar de tabla
  };

  // --- ACCIONES DE PAGINACIÓN ---
  const handlePrevPage = () => {
    if (page > 1) setPage(page - 1);
  };

  const handleNextPage = () => {
    if (page < totalPages) setPage(page + 1);
  };

  // --- ACCIÓN: RECARGAR HOJA ---
  const handleRefresh = () => {
    fetchTableDataAndSchema();
  };

  // --- ACCIÓN: EXPORTAR A CSV (CLIENT-SIDE ULTRA RÁPIDO) ---
  // Exporta las columnas FK según el modo de visualización activo (ID / real / ambos),
  // igual que se ven en la grilla en ese momento.
  const handleExportCsv = () => {
    if (data.length === 0 || !activeTable) return;

    const headers = Object.keys(data[0]);
    const fkColumnNames = new Set(fkColumns.map(fk => fk.column));

    const csvRows = [
      headers.join(','), // Cabeceras
      ...data.map((row, rowIndex) =>
        headers.map(header => {
          const val = row[header];
          let cellText;

          if (fkColumnNames.has(header) && fkDisplayMode !== 'id') {
            const idText = val === null ? '' : String(val);
            const realText = fkStatusText(fkResolutions[header]?.[rowIndex]) ?? '';
            cellText = fkDisplayMode === 'real'
              ? realText
              : (realText ? `${idText} · ${realText}` : idText);
          } else {
            cellText = val === null ? '' : String(val);
          }

          // Neutralizar posible fórmula (=, +, -, @) antes de escapar comillas
          const escaped = sanitizeForSpreadsheet(cellText).replace(/"/g, '""');
          return `"${escaped}"`;
        }).join(',')
      )
    ];

    const csvContent = "data:text/csv;charset=utf-8,\uFEFF" + csvRows.join("\n");
    const encodedUri = encodeURI(csvContent);
    const link = document.createElement("a");
    link.setAttribute("href", encodedUri);
    link.setAttribute("download", `${activeTable.schema}_${activeTable.name}_export.csv`);
    document.body.appendChild(link);
    
    link.click();
    document.body.removeChild(link);
  };

  // --- ACCIÓN: EXPORTAR REPORTE COMPLETO A EXCEL (.XLS) POR STREAMING ---
  const handleExportExcel = async () => {
    if (!activeTable || selectedColumns.length === 0) return;
    
    setIsDataLoading(true);
    setError(null);
    
    const columnsParam = selectedColumns.map(col => encodeURIComponent(col)).join(',');
    let url = `/api/db/tables/${encodeURIComponent(activeTable.schema)}/${encodeURIComponent(activeTable.name)}/export?columns=${columnsParam}`;

    if (appliedFilter && appliedFilter.column) {
      url += `&filterColumn=${encodeURIComponent(appliedFilter.column)}&filterOperator=${encodeURIComponent(appliedFilter.operator)}&filterValue=${encodeURIComponent(appliedFilter.value)}`;
      if (appliedFilter.value2) {
        url += `&filterValue2=${encodeURIComponent(appliedFilter.value2)}`;
      }
    }

    try {
      const response = await fetch(url);
      if (!response.ok) {
        let errorMessage = 'Error al exportar el archivo.';
        try {
          const errJson = await response.json();
          errorMessage = errJson.error || errorMessage;
        } catch (e) {
          // Si no es JSON, mantenemos el mensaje por defecto
        }
        throw new Error(errorMessage);
      }
      
      const blob = await response.blob();
      const blobUrl = window.URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = blobUrl;
      link.download = `${activeTable.schema}_${activeTable.name}_report.xlsx`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.URL.revokeObjectURL(blobUrl);
    } catch (err) {
      console.error(err);
      setError(err.message);
    } finally {
      setIsDataLoading(false);
    }
  };

  const handleSaveCustomFk = async (columnName, referencedSchema, referencedTable, referencedColumn, displayColumn, filterColumn, filterValue) => {
    if (!activeTable) return;

    const existingCustomFks = fkColumns
      .filter(fk => fk.custom && fk.column !== columnName)
      .map(fk => ({
        fkColumn: fk.column,
        referencedSchema: fk.referencedSchema,
        referencedTable: fk.referencedTable,
        referencedColumn: fk.referencedColumn,
        displayColumn: fk.displayColumn,
        filterColumn: fk.filterColumn,
        filterValue: fk.filterValue,
        enabled: fk.enabled
      }));
      
    const newCustomFk = {
      fkColumn: columnName,
      referencedSchema,
      referencedTable,
      referencedColumn,
      displayColumn,
      filterColumn,
      filterValue,
      enabled: true
    };
    
    const payload = [...existingCustomFks, newCustomFk];
    await submitCustomFks(payload);
  };

  const handleToggleFk = async (columnName, fk, enabled) => {
    if (!activeTable) return;

    const existingCustomFks = fkColumns
      .filter(fkCol => fkCol.custom && fkCol.column !== columnName)
      .map(fkCol => ({
        fkColumn: fkCol.column,
        referencedSchema: fkCol.referencedSchema,
        referencedTable: fkCol.referencedTable,
        referencedColumn: fkCol.referencedColumn,
        displayColumn: fkCol.displayColumn,
        filterColumn: fkCol.filterColumn,
        filterValue: fkCol.filterValue,
        enabled: fkCol.enabled
      }));
      
    const toggledFk = {
      fkColumn: columnName,
      referencedSchema: fk.referencedSchema,
      referencedTable: fk.referencedTable,
      referencedColumn: fk.referencedColumn,
      displayColumn: fk.displayColumn,
      filterColumn: fk.filterColumn,
      filterValue: fk.filterValue,
      enabled: enabled
    };
    
    const payload = [...existingCustomFks, toggledFk];
    await submitCustomFks(payload);
  };

  const handleDeleteCustomFk = async (columnName, fk) => {
    if (!activeTable) return;

    const payload = fkColumns
      .filter(fkCol => fkCol.custom && fkCol.column !== columnName)
      .map(fkCol => ({
        fkColumn: fkCol.column,
        referencedSchema: fkCol.referencedSchema,
        referencedTable: fkCol.referencedTable,
        referencedColumn: fkCol.referencedColumn,
        displayColumn: fkCol.displayColumn,
        filterColumn: fkCol.filterColumn,
        filterValue: fkCol.filterValue,
        enabled: fkCol.enabled
      }));
      
    await submitCustomFks(payload);
  };

  const submitCustomFks = async (payload) => {
    try {
      const url = `/api/db/tables/${encodeURIComponent(activeTable.schema)}/${encodeURIComponent(activeTable.name)}/custom-fks`;
      const res = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(payload)
      });
      
      if (!res.ok) {
        throw new Error('Error al guardar la clave foránea personalizada.');
      }
      
      // Refrescar los datos para recalcular resoluciones
      handleRefresh();
    } catch (err) {
      console.error(err);
      alert(err.message);
    }
  };

  return (
    <div className="app-layout">
      {/* Cinta de opciones superior */}
      <Ribbon 
        activeTable={activeTable}
        dbInfo={dbInfo}
        limit={limit}
        setLimit={setLimit}
        page={page}
        totalPages={totalPages}
        onPrevPage={handlePrevPage}
        onNextPage={handleNextPage}
        onRefresh={handleRefresh}
        onExportCsv={handleExportCsv}
        onExportExcel={handleExportExcel}
        isDataLoading={isDataLoading}
        tablesCount={tables.length}
        columns={columns}
        selectedColumns={selectedColumns}
        setSelectedColumns={setSelectedColumns}
        fkColumns={fkColumns}
        fkDisplayMode={fkDisplayMode}
        setFkDisplayMode={setFkDisplayMode}
        onOpenDbaConsole={() => setShowDbaConsole(true)}
        onOpenManual={() => setShowManual(true)}
      />

      {/* Grid de hoja de cálculo principal */}
      <Spreadsheet
        activeTable={activeTable}
        data={data}
        columns={columns}
        isDataLoading={isDataLoading}
        error={error}
        totalRows={totalRows}
        offset={(page - 1) * limit}
        limit={limit}
        fkColumns={fkColumns}
        fkResolutions={fkResolutions}
        fkDisplayMode={fkDisplayMode}
        tables={tables}
        onSaveCustomFk={handleSaveCustomFk}
        onToggleFk={handleToggleFk}
        onDeleteCustomFk={handleDeleteCustomFk}
        appliedFilter={appliedFilter}
        onApplyFilter={(filter) => { setAppliedFilter(filter); setPage(1); }}
      />

      {/* Pestañas de Hojas Inferiores (Lista de Tablas) */}
      <SheetTabs 
        tables={tables}
        activeTable={activeTable}
        onSelectTable={handleSelectTable}
      />

      {/* Barra de estado inferior de Excel */}
      <StatusBar 
        activeTable={activeTable}
        totalRows={totalRows}
        limit={limit}
        offset={(page - 1) * limit}
        responseTime={responseTime}
      />

      {/* Modal de Consola DBA (Rendimiento y Seguridad Experto) */}
      {showDbaConsole && dbInfo && (
        <div className="dba-modal-overlay" onClick={() => setShowDbaConsole(false)}>
          <div className="dba-modal" onClick={(e) => e.stopPropagation()}>
            <div className="dba-modal-header">
              <h3 className="dba-modal-title">
                <Server size={18} className="excel-icon" />
                Consola de Diagnóstico de Base de Datos <span>DBA</span>
              </h3>
              <button 
                className="dba-close-btn" 
                onClick={() => setShowDbaConsole(false)}
                title="Cerrar consola"
              >
                <X size={18} />
              </button>
            </div>

            <div className="dba-modal-body">
              {/* Grid de 2 columnas con tarjetas de métricas */}
              <div className="dba-grid">
                
                {/* Tarjeta 1: Motor y Conexión */}
                <div className="dba-card">
                  <h4 className="dba-card-title">Motor de Base de Datos</h4>
                  <div className="dba-metric-row">
                    <span className="dba-metric-label">Producto:</span>
                    <span className="dba-metric-value">{dbInfo.databaseProduct || 'SQL Server'}</span>
                  </div>
                  <div className="dba-metric-row">
                    <span className="dba-metric-label">Versión de Instancia:</span>
                    <span className="dba-metric-value" style={{ fontSize: '11px' }}>{dbInfo.databaseVersion || 'N/A'}</span>
                  </div>
                  <div className="dba-metric-row">
                    <span className="dba-metric-label">Driver JDBC:</span>
                    <span className="dba-metric-value">{dbInfo.driverName || 'N/A'}</span>
                  </div>
                  <div className="dba-metric-row">
                    <span className="dba-metric-label">Versión Driver:</span>
                    <span className="dba-metric-value">{dbInfo.driverVersion || 'N/A'}</span>
                  </div>
                  <div className="dba-metric-row" style={{ flexDirection: 'column', alignItems: 'flex-start', gap: '4px', borderTop: '1px dashed var(--excel-border)', paddingTop: '8px', marginTop: '4px' }}>
                    <span className="dba-metric-label">Cadena JDBC de Conexión:</span>
                    <span className="dba-metric-value" style={{ fontSize: '10px', wordBreak: 'break-all', color: 'var(--text-secondary)' }}>{dbInfo.jdbcUrl || 'N/A'}</span>
                  </div>
                </div>

                {/* Tarjeta 2: Métricas de Sesiones y Configuración */}
                <div className="dba-card">
                  <h4 className="dba-card-title">Rendimiento e Instancia</h4>
                  <div className="dba-metric-row">
                    <span className="dba-metric-label">Estado de la BD:</span>
                    <span className="dba-metric-value" style={{ color: dbInfo.dbState === 'ONLINE' ? 'var(--excel-green-light)' : '#ffb74d' }}>
                      ● {dbInfo.dbState || 'ONLINE'}
                    </span>
                  </div>
                  <div className="dba-metric-row">
                    <span className="dba-metric-label">Conexiones/Procesos Activos:</span>
                    <span className="dba-metric-value" style={{ color: 'var(--excel-green-light)', fontWeight: 'bold' }}>
                      {dbInfo.activeConnections !== null ? dbInfo.activeConnections : 'N/A'}
                    </span>
                  </div>
                  <div className="dba-metric-row">
                    <span className="dba-metric-label">Modelo de Recuperación:</span>
                    <span className="dba-metric-value">{dbInfo.dbRecoveryModel || 'SIMPLE'}</span>
                  </div>
                  <div className="dba-metric-row">
                    <span className="dba-metric-label">Colación (Collation):</span>
                    <span className="dba-metric-value" style={{ fontSize: '11px' }}>{dbInfo.dbCollation || 'N/A'}</span>
                  </div>
                  <div className="dba-metric-row" style={{ borderTop: '1px dashed var(--excel-border)', paddingTop: '8px', marginTop: '4px' }}>
                    <span className="dba-metric-label">Pool de Conexión Local:</span>
                    <span className="dba-metric-value" style={{ color: 'var(--excel-green-light)' }}>HikariCP (Activo)</span>
                  </div>
                </div>

                {/* Tarjeta 3: Estadísticas del Esquema de Datos */}
                <div className="dba-card">
                  <h4 className="dba-card-title">Estadísticas del Esquema</h4>
                  <div className="dba-metric-row">
                    <span className="dba-metric-label">Total Tablas Físicas:</span>
                    <span className="dba-metric-value">{dbInfo.totalTables !== null ? dbInfo.totalTables : 'N/A'}</span>
                  </div>
                  <div className="dba-metric-row">
                    <span className="dba-metric-label">Total Vistas Registradas:</span>
                    <span className="dba-metric-value">{dbInfo.totalViews !== null ? dbInfo.totalViews : 'N/A'}</span>
                  </div>
                  <div className="dba-metric-row">
                    <span className="dba-metric-label">Claves Foráneas Virtuales (Custom FKs):</span>
                    <span className="dba-metric-value" style={{ color: 'var(--excel-green-light)' }}>
                      {dbInfo.customFksCount !== null ? dbInfo.customFksCount : '0'} activas
                    </span>
                  </div>
                </div>

                {/* Tarjeta 4: Almacenamiento y Archivos de Base de Datos */}
                <div className="dba-card">
                  <h4 className="dba-card-title">Almacenamiento Físico</h4>
                  <div className="dba-metric-row">
                    <span className="dba-metric-label">Tamaño Total en Disco:</span>
                    <span className="dba-metric-value" style={{ fontWeight: 'bold', color: '#ffb74d' }}>
                      {dbInfo.totalSizeMb !== null ? `${dbInfo.totalSizeMb} MB` : 'N/A'}
                    </span>
                  </div>
                  
                  {dbInfo.dbFiles && dbInfo.dbFiles.length > 0 ? (
                    <table className="dba-file-table">
                      <thead>
                        <tr>
                          <th>Archivo Lógico</th>
                          <th>Tipo</th>
                          <th style={{ textAlign: 'right' }}>Tamaño</th>
                        </tr>
                      </thead>
                      <tbody>
                        {dbInfo.dbFiles.map(file => (
                          <tr key={file.name}>
                            <td style={{ maxWidth: '120px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={file.name}>
                              {file.name}
                            </td>
                            <td>
                              <span className={`dba-file-type-badge ${file.type.toLowerCase()}`}>
                                {file.type}
                              </span>
                            </td>
                            <td style={{ textAlign: 'right', fontFamily: 'var(--font-mono)' }}>
                              {file.sizeMb} MB
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  ) : (
                    <span style={{ fontSize: '11px', color: 'var(--text-muted)', fontStyle: 'italic' }}>
                      Sin acceso al tamaño de archivos individuales
                    </span>
                  )}
                </div>

              </div>
            </div>

            <div className="dba-footer">
              <button 
                className="excel-btn primary" 
                onClick={() => setShowDbaConsole(false)}
                style={{ backgroundColor: 'var(--excel-green)', borderColor: 'var(--excel-green)' }}
              >
                Cerrar Panel
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Modal de Guía de Uso Interactivo */}
      {showManual && (
        <div className="dba-modal-overlay" onClick={() => setShowManual(false)}>
          <div className="dba-modal" style={{ maxWidth: '850px', width: '90%' }} onClick={(e) => e.stopPropagation()}>
            <div className="dba-modal-header" style={{ backgroundColor: 'var(--excel-green)', color: '#fff' }}>
              <h3 className="dba-modal-title" style={{ color: '#fff' }}>
                <BookOpen size={18} style={{ color: '#fff' }} />
                Guía de Uso Interactivo <span>Manual</span>
              </h3>
              <button 
                className="dba-close-btn" 
                onClick={() => setShowManual(false)}
                style={{ color: '#fff' }}
                title="Cerrar guía"
              >
                <X size={18} />
              </button>
            </div>

            <div className="dba-modal-body" style={{ padding: '0px', display: 'flex', flexDirection: 'row', minHeight: '450px', overflow: 'hidden' }}>
              {/* Sidebar del manual */}
              <div style={{
                width: '200px',
                backgroundColor: 'var(--excel-bg-sidebar)',
                borderRight: '1px solid var(--excel-border)',
                padding: '16px 0',
                display: 'flex',
                flexDirection: 'column',
                gap: '4px',
                flexShrink: 0
              }}>
                <button
                  onClick={() => setManualTab('general')}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '10px',
                    padding: '12px 20px',
                    border: 'none',
                    background: manualTab === 'general' ? 'var(--excel-bg-active)' : 'none',
                    color: manualTab === 'general' ? 'var(--excel-green-light)' : 'var(--text-secondary)',
                    textAlign: 'left',
                    cursor: 'pointer',
                    fontSize: '12.5px',
                    fontWeight: manualTab === 'general' ? '700' : '500',
                    borderLeft: manualTab === 'general' ? '3px solid var(--excel-green)' : '3px solid transparent',
                    outline: 'none',
                    width: '100%'
                  }}
                >
                  <Layers size={15} />
                  Navegación
                </button>
                <button
                  onClick={() => setManualTab('fk')}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '10px',
                    padding: '12px 20px',
                    border: 'none',
                    background: manualTab === 'fk' ? 'var(--excel-bg-active)' : 'none',
                    color: manualTab === 'fk' ? 'var(--excel-green-light)' : 'var(--text-secondary)',
                    textAlign: 'left',
                    cursor: 'pointer',
                    fontSize: '12.5px',
                    fontWeight: manualTab === 'fk' ? '700' : '500',
                    borderLeft: manualTab === 'fk' ? '3px solid var(--excel-green)' : '3px solid transparent',
                    outline: 'none',
                    width: '100%'
                  }}
                >
                  <Link2 size={15} />
                  Relaciones (FK)
                </button>
                <button
                  onClick={() => setManualTab('export')}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '10px',
                    padding: '12px 20px',
                    border: 'none',
                    background: manualTab === 'export' ? 'var(--excel-bg-active)' : 'none',
                    color: manualTab === 'export' ? 'var(--excel-green-light)' : 'var(--text-secondary)',
                    textAlign: 'left',
                    cursor: 'pointer',
                    fontSize: '12.5px',
                    fontWeight: manualTab === 'export' ? '700' : '500',
                    borderLeft: manualTab === 'export' ? '3px solid var(--excel-green)' : '3px solid transparent',
                    outline: 'none',
                    width: '100%'
                  }}
                >
                  <Download size={15} />
                  Exportaciones
                </button>
                <button
                  onClick={() => setManualTab('tips')}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '10px',
                    padding: '12px 20px',
                    border: 'none',
                    background: manualTab === 'tips' ? 'var(--excel-bg-active)' : 'none',
                    color: manualTab === 'tips' ? 'var(--excel-green-light)' : 'var(--text-secondary)',
                    textAlign: 'left',
                    cursor: 'pointer',
                    fontSize: '12.5px',
                    fontWeight: manualTab === 'tips' ? '700' : '500',
                    borderLeft: manualTab === 'tips' ? '3px solid var(--excel-green)' : '3px solid transparent',
                    outline: 'none',
                    width: '100%'
                  }}
                >
                  <Lightbulb size={15} />
                  Tips y Atajos
                </button>
              </div>

              {/* Contenido del manual */}
              <div style={{ flex: 1, padding: '24px', overflowY: 'auto' }}>
                {manualTab === 'general' && (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                      <span style={{ fontSize: '24px' }}>🏁</span>
                      <h4 style={{ margin: 0, fontSize: '18px', color: '#fff' }}>¡Bienvenido a tu SQL Server Workbook!</h4>
                    </div>
                    <p style={{ fontSize: '13px', color: 'var(--text-secondary)', margin: 0, lineHeight: '1.6' }}>
                      Esta aplicación te permite explorar tus bases de datos SQL Server mediante una interfaz idéntica a <strong>Microsoft Excel</strong>. Aquí tienes los aspectos clave para empezar:
                    </p>

                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '16px', marginTop: '10px' }}>
                      <div style={{ backgroundColor: 'var(--excel-bg-app)', border: '1px solid var(--excel-border)', padding: '16px', borderRadius: '8px' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px', color: 'var(--excel-green-light)', fontWeight: '600', fontSize: '13px' }}>
                          <span>📄 Hojas de Cálculo</span>
                        </div>
                        <p style={{ margin: 0, fontSize: '11.5px', color: 'var(--text-muted)', lineHeight: '1.5' }}>
                          En la parte inferior de la pantalla verás las tablas de tu base de datos listadas como pestañas de hojas. Haz clic en cualquiera para cargar sus registros al instante.
                        </p>
                      </div>

                      <div style={{ backgroundColor: 'var(--excel-bg-app)', border: '1px solid var(--excel-border)', padding: '16px', borderRadius: '8px' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px', color: 'var(--excel-green-light)', fontWeight: '600', fontSize: '13px' }}>
                          <span>🔢 Paginación y Límites</span>
                        </div>
                        <p style={{ margin: 0, fontSize: '11.5px', color: 'var(--text-muted)', lineHeight: '1.5' }}>
                          En la pestaña <strong>Inicio</strong> del Ribbon superior, puedes cambiar el número de filas por página (10, 15, 30, etc.) y paginar de forma ultra fluida.
                        </p>
                      </div>
                    </div>

                    <div style={{ display: 'flex', gap: '12px', backgroundColor: '#21a36612', border: '1px dashed rgba(33,163,102,0.25)', padding: '12px', borderRadius: '6px', marginTop: '8px' }}>
                      <span style={{ fontSize: '16px' }}>💡</span>
                      <span style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: '1.5' }}>
                        <strong>Tip Pro:</strong> El alineamiento de las celdas se comporta igual que en Excel. Los números se alinean a la derecha en azul y los valores nulos (<code>NULL</code>) se muestran en cursiva gris para facilitar la lectura rápida.
                      </span>
                    </div>
                  </div>
                )}

                {manualTab === 'fk' && (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                      <span style={{ fontSize: '24px' }}>🔗</span>
                      <h4 style={{ margin: 0, fontSize: '18px', color: '#fff' }}>Resolución Inteligente de Relaciones (FK)</h4>
                    </div>
                    <p style={{ fontSize: '13px', color: 'var(--text-secondary)', margin: 0, lineHeight: '1.6' }}>
                      El sistema detecta automáticamente las relaciones físicas de clave foránea y te permite definir <strong>relaciones virtuales (Custom FKs)</strong>. Esto asocia IDs numéricos con nombres descriptivos de forma automática.
                    </p>

                    <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', marginTop: '10px' }}>
                      <div style={{ backgroundColor: 'var(--excel-bg-app)', border: '1px solid var(--excel-border)', padding: '14px', borderRadius: '8px', display: 'flex', gap: '12px', alignItems: 'flex-start' }}>
                        <div style={{ background: '#21a36626', padding: '6px', borderRadius: '6px', color: 'var(--excel-green-light)', flexShrink: 0 }}>
                          <Link2 size={16} />
                        </div>
                        <div>
                          <h5 style={{ margin: '0 0 4px 0', fontSize: '13px', color: '#fff' }}>Relaciones Virtuales (Creación Directa)</h5>
                          <p style={{ margin: 0, fontSize: '11.5px', color: 'var(--text-muted)', lineHeight: '1.5' }}>
                            ¿Falta una FK física en tu base de datos? Haz doble clic en el encabezado de la columna de un ID (ej: <code>ClienteId</code>), selecciona la tabla origen y columna descriptiva (ej: <code>Nombre</code>) y ¡listo! Se resolverá automáticamente.
                          </p>
                        </div>
                      </div>

                      <div style={{ backgroundColor: 'var(--excel-bg-app)', border: '1px solid var(--excel-border)', padding: '14px', borderRadius: '8px', display: 'flex', gap: '12px', alignItems: 'flex-start' }}>
                        <div style={{ background: '#ffa0001c', padding: '6px', borderRadius: '6px', color: '#ffb74d', flexShrink: 0 }}>
                          <Layers size={16} />
                        </div>
                        <div>
                          <h5 style={{ margin: '0 0 4px 0', fontSize: '13px', color: '#fff' }}>Visualización Flexible</h5>
                          <p style={{ margin: 0, fontSize: '11.5px', color: 'var(--text-muted)', lineHeight: '1.5' }}>
                            En el Ribbon, usa el control "Mostrar FK" para alternar la visualización entre: <strong>Código ID</strong>, el <strong>Valor Descriptivo</strong>, o <strong>Ambos combinados</strong>.
                          </p>
                        </div>
                      </div>
                    </div>
                  </div>
                )}

                {manualTab === 'export' && (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                      <span style={{ fontSize: '24px' }}>📥</span>
                      <h4 style={{ margin: 0, fontSize: '18px', color: '#fff' }}>Exportación de Reportes a Excel y CSV</h4>
                    </div>
                    <p style={{ fontSize: '13px', color: 'var(--text-secondary)', margin: 0, lineHeight: '1.6' }}>
                      Puedes descargar la información de cualquier tabla a tu disco local con un solo clic. El sistema genera archivos nativos y optimizados de manera segura.
                    </p>

                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '16px', marginTop: '10px' }}>
                      <div style={{ backgroundColor: 'var(--excel-bg-app)', border: '1px solid var(--excel-border)', padding: '16px', borderRadius: '8px' }}>
                        <h5 style={{ margin: '0 0 6px 0', fontSize: '13px', color: 'var(--excel-green-light)' }}>📁 Reporte Excel (.xlsx)</h5>
                        <p style={{ margin: 0, fontSize: '11.5px', color: 'var(--text-muted)', lineHeight: '1.5' }}>
                          Genera un archivo compatible con Excel utilizando la API nativa de Apache POI por streaming (SXSSF). Esto comprime el tamaño del archivo y reduce la carga en memoria del servidor.
                        </p>
                      </div>

                      <div style={{ backgroundColor: 'var(--excel-bg-app)', border: '1px solid var(--excel-border)', padding: '16px', borderRadius: '8px' }}>
                        <h5 style={{ margin: '0 0 6px 0', fontSize: '13px', color: 'var(--excel-green-light)' }}>📄 Reporte CSV</h5>
                        <p style={{ margin: 0, fontSize: '11.5px', color: 'var(--text-muted)', lineHeight: '1.5' }}>
                          Exporta los datos delimitados por comas para su lectura por sistemas automatizados. Incluye protección automática contra <em>Inyección de Fórmulas CSV</em>.
                        </p>
                      </div>
                    </div>

                    <div style={{ backgroundColor: 'var(--excel-bg-sidebar)', border: '1px solid var(--excel-border)', padding: '12px', borderRadius: '6px', fontSize: '11.5px', color: 'var(--text-muted)', display: 'flex', gap: '8px', alignItems: 'center' }}>
                      <span>🛡️</span>
                      <span>
                        <strong>Seguridad:</strong> La exportación cuenta con límites concurrentes para evitar la saturación de conexiones y salvaguardar el rendimiento de tu base de datos productiva.
                      </span>
                    </div>
                  </div>
                )}

                {manualTab === 'tips' && (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                      <span style={{ fontSize: '24px' }}>💡</span>
                      <h4 style={{ margin: 0, fontSize: '18px', color: '#fff' }}>Consejos Pro y Diagnóstico (DBA)</h4>
                    </div>
                    <p style={{ fontSize: '13px', color: 'var(--text-secondary)', margin: 0, lineHeight: '1.6' }}>
                      Sácale el máximo provecho a la aplicación con estos atajos y funcionalidades avanzadas de rendimiento:
                    </p>

                    <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', marginTop: '10px' }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', padding: '10px 14px', backgroundColor: 'var(--excel-bg-app)', border: '1px solid var(--excel-border)', borderRadius: '6px', fontSize: '12.5px', alignItems: 'center' }}>
                        <span style={{ color: 'var(--text-secondary)', fontWeight: '500' }}>Recargar Datos Actuales</span>
                        <kbd style={{ background: 'var(--excel-bg-active)', border: '1px solid var(--excel-border)', padding: '2px 8px', borderRadius: '4px', fontSize: '11px', color: '#fff', fontFamily: 'var(--font-mono)' }}>F5 o Botón Refrescar</kbd>
                      </div>
                      
                      <div style={{ display: 'flex', justifyContent: 'space-between', padding: '10px 14px', backgroundColor: 'var(--excel-bg-app)', border: '1px solid var(--excel-border)', borderRadius: '6px', fontSize: '12.5px', alignItems: 'center' }}>
                        <span style={{ color: 'var(--text-secondary)', fontWeight: '500' }}>Consola de Diagnóstico de Servidor</span>
                        <span style={{ color: 'var(--excel-green-light)', fontSize: '11.5px', fontWeight: '600' }}>Pestaña Rendimiento ➡️ Abrir Consola DBA</span>
                      </div>

                      <div style={{ display: 'flex', justifyContent: 'space-between', padding: '10px 14px', backgroundColor: 'var(--excel-bg-app)', border: '1px solid var(--excel-border)', borderRadius: '6px', fontSize: '12.5px', alignItems: 'center' }}>
                        <span style={{ color: 'var(--text-secondary)', fontWeight: '500' }}>Caché de Consultas</span>
                        <span style={{ color: 'var(--text-muted)', fontSize: '11.5px', fontStyle: 'italic' }}>Activo automático por 60 segundos en metadatos</span>
                      </div>
                    </div>

                    <div style={{ display: 'flex', gap: '10px', backgroundColor: 'rgba(255, 183, 77, 0.08)', border: '1px dashed rgba(255, 183, 77, 0.3)', padding: '12px', borderRadius: '6px', marginTop: '8px' }}>
                      <span style={{ fontSize: '16px' }}>⚡</span>
                      <span style={{ fontSize: '11.5px', color: 'var(--text-secondary)', lineHeight: '1.5' }}>
                        <strong>Nota de Rendimiento:</strong> Si notas que los datos tardan unos milisegundos en cargar al cambiar de tabla por primera vez, es normal. La aplicación realiza consultas dinámicas optimizadas sobre el catálogo de SQL Server y luego las almacena en la caché de Caffeine para entregas instantáneas.
                      </span>
                    </div>
                  </div>
                )}
              </div>
            </div>

            <div className="dba-footer" style={{ borderTop: '1px solid var(--excel-border)', backgroundColor: 'var(--excel-bg-sidebar)' }}>
              <button 
                className="excel-btn primary" 
                onClick={() => setShowManual(false)}
                style={{ backgroundColor: 'var(--excel-green)', borderColor: 'var(--excel-green)' }}
              >
                ¡Entendido, a explorar!
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
