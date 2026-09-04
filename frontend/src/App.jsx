import React, { useState, useEffect, useCallback, useRef, Suspense } from 'react';
import Ribbon from './components/Ribbon';
import Spreadsheet from './components/Spreadsheet';
import SheetTabs from './components/SheetTabs';
import StatusBar from './components/StatusBar';
import DbaConsoleModal from './components/DbaConsoleModal';
import UserGuideModal from './components/UserGuideModal';
import LoginModal from './components/LoginModal';
import ConnectionModal from './components/ConnectionModal';
import { ToastProvider, useToast } from './context/ToastContext';
import { AuthProvider, useAuth } from './context/AuthContext';
import { fkStatusText } from './utils/fk';
import { sanitizeForSpreadsheet } from './utils/csv';

// Vista "Reportes Personalizados" se carga solo bajo demanda
const CustomReports = React.lazy(() => import('./components/CustomReports'));

export default function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <AppContent />
      </ToastProvider>
    </AuthProvider>
  );
}

function AppContent() {
  const { success, error: toastError, warning, info, confirm } = useToast();
  const { apiFetch, isAuthenticated } = useAuth();

  // Modo de Experiencia: 'simple' (Fácil tipo Excel / Buk) | 'advanced' (Modo DBA / SQL)
  const [uxMode, setUxMode] = useState(() => {
    return localStorage.getItem('pushdb_ux_mode') || 'simple';
  });

  const handleSetUxMode = (mode) => {
    setUxMode(mode);
    localStorage.setItem('pushdb_ux_mode', mode);
    if (mode === 'simple') {
      info('Modo Fácil activado: Vista optimizada para Excel y Buk', 'Modo de Experiencia');
    } else {
      info('Modo DBA activado: Vista técnica completa con SQL y métricas', 'Modo de Experiencia');
    }
  };

  // Vista activa: 'explorer' (Explorador de Tablas) | 'custom-reports' (Constructor de Reportes)
  const [currentView, setCurrentView] = useState('explorer');

  // Estados de Base de Datos
  const [dbInfo, setDbInfo] = useState(null);
  const [tables, setTables] = useState([]);
  const [activeTable, setActiveTable] = useState(null);

  // Estados de Datos de la Tabla Activa
  const [data, setData] = useState([]);
  const [columns, setColumns] = useState([]);
  const [selectedColumns, setSelectedColumns] = useState([]);
  const [loadedTableKey, setLoadedTableKey] = useState("");
  const [columnsCache, setColumnsCache] = useState({});
  // Espejo de loadedTableKey/columnsCache leído dentro de fetchTableDataAndSchema
  // sin declararlos como dependencia: si el callback dependiera de un estado que
  // él mismo escribe, cada primera carga de una tabla le daría nueva identidad
  // y retriggerearía el efecto de carga, duplicando el fetch de datos/columnas.
  const loadedTableKeyRef = useRef(loadedTableKey);
  const columnsCacheRef = useRef(columnsCache);
  useEffect(() => { loadedTableKeyRef.current = loadedTableKey; }, [loadedTableKey]);
  useEffect(() => { columnsCacheRef.current = columnsCache; }, [columnsCache]);

  // Foreign Keys detectadas y resueltas
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
  const [responseTime, setResponseTime] = useState(null);
  const [showDbaConsole, setShowDbaConsole] = useState(false);
  const [showManual, setShowManual] = useState(false);
  const [showConnectionModal, setShowConnectionModal] = useState(false);
  const [appliedFilter, setAppliedFilter] = useState(null);

  // --- LLAMADA API: INFORMACIÓN CONEXIÓN ---
  const fetchDbInfo = useCallback(async () => {
    if (!isAuthenticated) return;
    try {
      const res = await apiFetch('/api/db/info');
      if (!res.ok) throw new Error('Error al conectar con la API de base de datos.');
      const infoData = await res.json();
      setDbInfo(infoData);
    } catch (err) {
      console.error(err);
      setError('Error al obtener la información de conexión.');
    }
  }, [apiFetch, isAuthenticated]);

  // --- LLAMADA API: LISTA DE TABLAS ---
  const fetchTablesList = useCallback(async () => {
    if (!isAuthenticated) return;
    try {
      const res = await apiFetch('/api/db/tables');
      if (!res.ok) throw new Error('Error al recuperar las hojas de trabajo.');
      const list = await res.json();
      setTables(list);
      // Forma funcional: evita que activeTable sea dependencia de este callback.
      // De lo contrario, seleccionar la primera tabla le da nueva identidad a
      // fetchTablesList, lo que retriggerea el efecto de carga inicial y duplica
      // el fetch de /api/db/info y /api/db/tables en cada arranque.
      setActiveTable(prev => (list.length > 0 && !prev) ? list[0] : prev);
    } catch (err) {
      console.error(err);
      setError('Error al recuperar las tablas del servidor de base de datos.');
    }
  }, [apiFetch, isAuthenticated]);

  // --- LLAMADA API: DATOS Y ESTRUCTURA (COMBINADOS) ---
  const fetchTableDataAndSchema = useCallback(async () => {
    if (!activeTable || !isAuthenticated) return;

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
    const needsSchema = !columnsCacheRef.current[tableKey];

    try {
      const [dataRes, schemaRes] = await Promise.all([
        apiFetch(dataUrl),
        needsSchema ? apiFetch(schemaUrl) : Promise.resolve(null)
      ]);

      if (!dataRes.ok) {
        const errJson = await dataRes.json();
        throw new Error(errJson.error || 'Error al leer registros.');
      }
      const pageResult = await dataRes.json();

      let columnsList = columnsCacheRef.current[tableKey];
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

      if (loadedTableKeyRef.current !== tableKey) {
        setSelectedColumns(columnsList.map(c => c.name));
        setLoadedTableKey(tableKey);
      }

      const endTime = performance.now();
      setResponseTime(Math.round(endTime - startTime));
    } catch (err) {
      console.error(err);
      setError(err.message);
      setData([]);
    } finally {
      setIsDataLoading(false);
    }
  }, [activeTable, limit, page, appliedFilter, apiFetch, isAuthenticated]);

  // Cargar metadatos iniciales cuando el usuario está autenticado, o limpiar datos al cerrar sesión
  useEffect(() => {
    if (isAuthenticated) {
      fetchDbInfo();
      fetchTablesList();
    } else {
      setDbInfo(null);
      setTables([]);
      setActiveTable(null);
      setData([]);
      setColumns([]);
      setSelectedColumns([]);
      setColumnsCache({});
      setLoadedTableKey("");
      setFkColumns([]);
      setFkResolutions({});
      setError(null);
    }
  }, [isAuthenticated, fetchDbInfo, fetchTablesList]);

  // Recargar datos cuando cambia la tabla activa, el límite de filas o la página
  useEffect(() => {
    if (activeTable && isAuthenticated) {
      fetchTableDataAndSchema();
    }
  }, [activeTable, limit, page, isAuthenticated, fetchTableDataAndSchema]);

  // Manejar cambio exitoso de conexión de base de datos
  const handleConnectionSuccess = (newConnInfo) => {
    setActiveTable(null);
    setData([]);
    setColumns([]);
    setColumnsCache({});
    setLoadedTableKey("");
    setPage(1);
    setAppliedFilter(null);
    fetchDbInfo();
    fetchTablesList();
  };

  const handleSelectTable = (table) => {
    setActiveTable(table);
    setPage(1);
    setAppliedFilter(null);
  };

  const handlePrevPage = () => {
    if (page > 1) setPage(page - 1);
  };

  const handleNextPage = () => {
    if (page < totalPages) setPage(page + 1);
  };

  const handleRefresh = () => {
    fetchTableDataAndSchema();
  };

  const handleExportCsv = () => {
    if (data.length === 0 || !activeTable) return;

    const headers = Object.keys(data[0]);
    const fkColumnNames = new Set(fkColumns.map(fk => fk.column));

    const csvRows = [
      headers.join(','),
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
      const response = await apiFetch(url);
      if (!response.ok) {
        let errorMessage = 'Error al exportar el archivo.';
        try {
          const errJson = await response.json();
          errorMessage = errJson.error || errorMessage;
        } catch (e) {}
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
      const res = await apiFetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      
      if (!res.ok) {
        throw new Error('Error al guardar la clave foránea personalizada.');
      }
      
      success('Clave foránea actualizada con éxito');
      handleRefresh();
    } catch (err) {
      console.error(err);
      toastError(err.message);
    }
  };

  return (
    <div className="app-layout">
      {/* Modal de Inicio de Sesión / Acceder */}
      <LoginModal />

      {/* Modal de Conexión de Base de Datos */}
      <ConnectionModal
        isOpen={showConnectionModal}
        onClose={() => setShowConnectionModal(false)}
        onConnectionSuccess={handleConnectionSuccess}
      />

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
        onOpenConnectionModal={() => setShowConnectionModal(true)}
        currentView={currentView}
        setCurrentView={setCurrentView}
        uxMode={uxMode}
        onSetUxMode={handleSetUxMode}
      />

      {currentView === 'explorer' ? (
        <>
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
            uxMode={uxMode}
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
        </>
      ) : (
        /* Módulo Completo de Reportes Personalizados Multi-Tabla */
        <Suspense fallback={(
          <div className="view-state-screen">
            <div className="excel-spinner"></div>
            <p>Cargando módulo de Reportes Personalizados...</p>
          </div>
        )}>
          <CustomReports
            tables={tables}
            columnsCache={columnsCache}
            setColumnsCache={setColumnsCache}
            uxMode={uxMode}
          />
        </Suspense>
      )}

      {/* Modal de Consola DBA */}
      {showDbaConsole && dbInfo && (
        <DbaConsoleModal dbInfo={dbInfo} onClose={() => setShowDbaConsole(false)} />
      )}

      {/* Modal de Guía de Uso Interactivo */}
      {showManual && (
        <UserGuideModal onClose={() => setShowManual(false)} />
      )}
    </div>
  );
}
