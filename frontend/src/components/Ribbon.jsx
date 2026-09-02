import React, { useState } from 'react';
import {
  Database, RefreshCw, Download, Info, Settings,
  ChevronLeft, ChevronRight, Table, Cpu, ShieldAlert, Link2,
  HelpCircle, Layers
} from 'lucide-react';

export default function Ribbon({
  activeTable,
  dbInfo,
  limit,
  setLimit,
  page,
  totalPages,
  onPrevPage,
  onNextPage,
  onRefresh,
  onExportCsv,
  onExportExcel,
  isDataLoading,
  tablesCount,
  columns = [],
  selectedColumns = [],
  setSelectedColumns,
  fkColumns = [],
  fkDisplayMode = 'both',
  setFkDisplayMode,
  onOpenDbaConsole,
  onOpenManual,
  currentView = 'explorer',
  setCurrentView = () => {},
  uxMode = 'simple',
  onSetUxMode = () => {}
}) {
  const [activeTab, setActiveTab] = useState('home'); // 'home', 'data', 'about'
  const [showColumnsDropdown, setShowColumnsDropdown] = useState(false);

  const handleToggleTab = (tab) => {
    setActiveTab(tab);
    setShowColumnsDropdown(false);
  };

  return (
    <div className="ribbon">
      {/* Barra superior de identificación */}
      <div className="ribbon-top-bar">
        <div className="excel-logo-area">
          <img src="/logo.png" alt="Logo" className="excel-logo" />
          <h1 className="app-title">SQL Server <span>Workbook</span></h1>
        </div>

        {/* Selector de Modo de Trabajo Principal */}
        <div className="view-mode-switcher">
          <button
            className={`view-mode-btn ${currentView === 'explorer' ? 'active' : ''}`}
            onClick={() => setCurrentView('explorer')}
            title="Explorador de tablas individuales estilo Excel"
          >
            <Table size={13} />
            <span>{uxMode === 'simple' ? 'Hojas de Datos' : 'Explorador de Tablas'}</span>
          </button>
          <button
            className={`view-mode-btn ${currentView === 'custom-reports' ? 'active' : ''}`}
            onClick={() => setCurrentView('custom-reports')}
            title="Constructor y visor de reportes personalizados con cruces multi-tabla"
          >
            <Layers size={13} style={{ color: currentView === 'custom-reports' ? '#fff' : 'var(--excel-green-light)' }} />
            <span>{uxMode === 'simple' ? 'Cruzar y Armar Reportes' : 'Reportes Personalizados (Joins)'}</span>
          </button>
        </div>

        {/* Selector de Experiencia Dual (Modo Fácil vs Modo DBA) */}
        <div className="ux-mode-toggle" title="Alternar entre interfaz amigable tipo Excel/Buk o interfaz técnica de Base de Datos">
          <button
            className={`ux-mode-btn ${uxMode === 'simple' ? 'active simple' : ''}`}
            onClick={() => onSetUxMode('simple')}
          >
            <span>🟢 Modo Fácil (Excel)</span>
          </button>
          <button
            className={`ux-mode-btn ${uxMode === 'advanced' ? 'active advanced' : ''}`}
            onClick={() => onSetUxMode('advanced')}
          >
            <span>🛠️ Modo DBA</span>
          </button>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <button
            className="excel-btn"
            onClick={onOpenManual}
            style={{ padding: '4px 10px', fontSize: '11px', gap: '4px', height: '26px' }}
            title="Abrir Manual de Uso Interactivo"
          >
            <HelpCircle size={13} className="excel-icon" style={{ color: 'var(--excel-green-light)' }} />
            <span>Guía</span>
          </button>
          {uxMode === 'advanced' && (
            <div className="db-status">
              <span className={`status-dot ${dbInfo?.databaseProduct ? 'online' : ''}`}></span>
              <span>{dbInfo?.databaseProduct || 'Desconectado'}</span>
            </div>
          )}
        </div>
      </div>

      {/* Pestañas de la cinta de opciones (solo para modo explorador) */}
      {currentView === 'explorer' && (
        <div className="ribbon-tabs">
          <button
            className={`ribbon-tab ${activeTab === 'home' ? 'active' : ''}`}
            onClick={() => handleToggleTab('home')}
            title="Controles principales: Paginación, navegación de registros y visualización de Claves Foráneas (FK)"
          >
            Inicio
          </button>
          <button
            className={`ribbon-tab ${activeTab === 'data' ? 'active' : ''}`}
            onClick={() => handleToggleTab('data')}
            title="Herramientas de datos: Exportación completa a Excel (.xlsx), CSV, y configuración de columnas del reporte"
          >
            Descargar Reporte
          </button>
          <button
            className={`ribbon-tab ${activeTab === 'about' ? 'active' : ''}`}
            onClick={() => handleToggleTab('about')}
            title="Detalles del sistema: Optimización del motor de base de datos, seguridad y tiempos de respuesta"
          >
            Rendimiento y Seguridad
          </button>
          <button
            className="ribbon-tab"
            style={{ marginLeft: 'auto', color: 'var(--excel-green-light)', fontWeight: 600 }}
            onClick={() => setCurrentView('custom-reports')}
            title="Abrir el generador de reportes personalizados y cruces multi-tabla"
          >
            <Layers size={13} style={{ marginRight: '4px', verticalAlign: '-2px' }} />
            Ir a Reportes Personalizados →
          </button>
        </div>
      )}

      {/* Controles de la cinta según la pestaña activa */}
      {currentView === 'explorer' && (
      <div className="ribbon-controls">
        {activeTab === 'home' && (
          <>
            {/* Grupo: Navegación de registros */}
            <div className="control-group" title="Configuración de la paginación y límites de filas para la grilla de datos">
              <span className="control-label">Registros</span>
              <select
                className="excel-select"
                value={limit}
                onChange={(e) => setLimit(Number(e.target.value))}
                disabled={!activeTable}
                title="Cantidad de registros que se cargarán en cada página de la grilla"
              >
                <option value={10}>10 filas</option>
                <option value={15}>15 filas</option>
                <option value={30}>30 filas</option>
                <option value={50}>50 filas</option>
                <option value={100}>100 filas</option>
              </select>

              <button
                className="excel-btn"
                onClick={onPrevPage}
                disabled={page <= 1 || isDataLoading}
                title="Cargar la página anterior de registros en la grilla"
              >
                <ChevronLeft size={16} />
                Anterior
              </button>

              <span
                style={{ fontSize: '12px', color: 'var(--text-secondary)' }}
                title={`Visualizando página ${page} de un total de ${totalPages || 1}`}
              >
                Pág. <strong>{page}</strong> de <strong>{totalPages || 1}</strong>
              </span>

              <button
                className="excel-btn"
                onClick={onNextPage}
                disabled={page >= totalPages || isDataLoading}
                title="Cargar la página siguiente de registros en la grilla"
              >
                Siguiente
                <ChevronRight size={16} />
              </button>
            </div>

            {/* Grupo: Acciones de Tabla */}
            <div className="control-group" title="Operaciones directas sobre la base de datos de origen">
              <span className="control-label">Acciones</span>
              <button
                className="excel-btn"
                onClick={onRefresh}
                disabled={!activeTable || isDataLoading}
                title="Volver a consultar la base de datos SQL Server en tiempo real para reflejar cambios externos"
              >
                <RefreshCw size={14} className={isDataLoading ? 'animate-spin' : ''} />
                Actualizar
              </button>
            </div>

            {/* Grupo: Foreign Keys — solo visible si la tabla activa tiene FK detectadas */}
            {fkColumns.length > 0 && (
              <div className="control-group" title="Modos de traducción para las Claves Foráneas (FK) detectadas">
                <span className="control-label">
                  <Link2 size={12} style={{ marginRight: '4px', verticalAlign: '-2px' }} />
                  Foreign Keys ({fkColumns.length})
                </span>
                <div className="fk-mode-toggle">
                  {[
                    { value: 'id', label: 'ID', title: 'Mostrar únicamente el código identificador original (ej. 118)' },
                    { value: 'real', label: 'Valor real', title: 'Traducir el ID al valor descriptivo real en caliente (ej. Jornal)' },
                    { value: 'both', label: 'Ambos', title: 'Mostrar el ID original al lado de su valor real descriptivo (ej. 118 · Jornal)' }
                  ].map(opt => (
                    <button
                      key={opt.value}
                      className={`fk-mode-btn ${fkDisplayMode === opt.value ? 'active' : ''}`}
                      onClick={() => setFkDisplayMode(opt.value)}
                      disabled={isDataLoading}
                      title={opt.title}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </>
        )}

        {activeTab === 'data' && (
          <>
            {/* Grupo: Exportación */}
            <div className="control-group" title="Descarga de reportes en formatos de hoja de cálculo estándar">
              <span className="control-label">Exportación Completa</span>
              <button
                className="excel-btn primary"
                onClick={() => {
                  setShowColumnsDropdown(false);
                  onExportExcel();
                }}
                disabled={!activeTable || isDataLoading || selectedColumns.length === 0}
                title="Descargar el reporte completo de la tabla en un archivo real Excel (.xlsx) resolviendo todas las relaciones de FKs en caliente"
              >
                <Download size={14} />
                Exportar a Excel (.xlsx)
              </button>

              <button
                className="excel-btn"
                onClick={onExportCsv}
                disabled={!activeTable || isDataLoading}
                title="Descargar de manera inmediata un archivo CSV rápido que contiene únicamente la vista actual de registros de la grilla"
              >
                <Download size={14} />
                Exportar Vista (CSV)
              </button>
            </div>

            {/* Grupo: Selección de Columnas */}
            {activeTable && (
              <div className="control-group" style={{ position: 'relative' }} title="Configuración de la estructura de campos del reporte final de Excel">
                <span className="control-label">Columnas para el Reporte</span>
                <button
                  className="excel-btn"
                  onClick={() => setShowColumnsDropdown(!showColumnsDropdown)}
                  disabled={isDataLoading}
                  title="Haz clic para desplegar la lista de columnas y marcar cuáles deseas incluir en el reporte de Excel (.xlsx)"
                >
                  <Table size={14} />
                  Columnas ({selectedColumns.length} de {columns.length})
                </button>

                {showColumnsDropdown && (
                  <div style={{
                    position: 'absolute',
                    top: '100%',
                    left: '0',
                    marginTop: '4px',
                    width: '260px',
                    maxHeight: '260px',
                    backgroundColor: 'var(--excel-bg-header)',
                    border: '1px solid var(--excel-border)',
                    borderRadius: '6px',
                    boxShadow: '0 10px 25px rgba(0, 0, 0, 0.5)',
                    zIndex: 100,
                    display: 'flex',
                    flexDirection: 'column',
                    padding: '12px'
                  }}>
                    {/* Botones de acción rápida */}
                    <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--excel-border)', paddingBottom: '8px', marginBottom: '8px' }}>
                      <button
                        className="excel-btn"
                        style={{ padding: '2px 6px', fontSize: '10px' }}
                        onClick={() => setSelectedColumns(columns.map(c => c.name))}
                        title="Marcar todas las columnas de la lista para incluirlas en la exportación"
                      >
                        Marcar todas
                      </button>
                      <button
                        className="excel-btn"
                        style={{ padding: '2px 6px', fontSize: '10px' }}
                        onClick={() => setSelectedColumns([])}
                        title="Desmarcar todas las columnas de la lista para realizar una selección limpia"
                      >
                        Desmarcar todas
                      </button>
                    </div>

                    {/* Lista con scroll de checkboxes */}
                    <div style={{ overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '6px', flexGrow: 1, paddingRight: '4px' }}>
                      {columns.map(col => {
                        const isChecked = selectedColumns.includes(col.name);
                        return (
                          <label
                            key={col.name}
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              gap: '8px',
                              fontSize: '12px',
                              cursor: 'pointer',
                              color: isChecked ? 'var(--text-primary)' : 'var(--text-muted)'
                            }}
                            title={`Incluir columna '${col.name}' en la descarga del archivo de Excel`}
                          >
                            <input
                              type="checkbox"
                              checked={isChecked}
                              style={{ accentColor: 'var(--excel-green-light)', cursor: 'pointer' }}
                              onChange={(e) => {
                                if (e.target.checked) {
                                  setSelectedColumns([...selectedColumns, col.name]);
                                } else {
                                  setSelectedColumns(selectedColumns.filter(c => c !== col.name));
                                }
                              }}
                            />
                            <span style={{ textOverflow: 'ellipsis', overflow: 'hidden', whiteSpace: 'nowrap' }} title={col.name}>
                              {col.name}
                            </span>
                          </label>
                        );
                      })}
                    </div>
                  </div>
                )}
              </div>
            )}
          </>
        )}

        {activeTab === 'about' && (
          <div style={{ display: 'flex', justifyContent: 'space-between', width: '100%', alignItems: 'center' }}>
            <div style={{ display: 'flex', gap: '24px', fontSize: '11.5px', color: 'var(--text-secondary)', alignItems: 'center' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }} title="El frontend de la aplicación web fue desarrollado con React y empaquetado ultra rápido con compiladores en Rust">
                <Cpu size={14} className="excel-icon" />
                <span><strong>Frontend:</strong> React + Vite</span>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }} title="Conexiones/procesos actualmente abiertos en la base de datos">
                <RefreshCw size={14} className="excel-icon" />
                <span><strong>Conexiones BD:</strong> <strong style={{ color: 'var(--excel-green-light)' }}>{dbInfo?.activeConnections !== null ? dbInfo.activeConnections : '1'}</strong> activas</span>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }} title="Cantidad total de tablas físicas detectadas en la base de datos">
                <Table size={14} className="excel-icon" />
                <span><strong>Tablas Detectadas:</strong> <strong style={{ color: 'var(--excel-green-light)' }}>{dbInfo?.totalTables !== null ? dbInfo.totalTables : tablesCount}</strong></span>
              </div>
              {dbInfo?.totalSizeMb !== null && (
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }} title="Tamaño total en disco consumido por los archivos de la base de datos">
                  <Database size={14} className="excel-icon" />
                  <span><strong>Tamaño Total:</strong> <strong style={{ color: 'var(--excel-amber)' }}>{dbInfo.totalSizeMb} MB</strong></span>
                </div>
              )}
            </div>

            <button
              className="excel-btn primary"
              style={{ padding: '6px 12px', fontSize: '11px', display: 'flex', alignItems: 'center', gap: '6px', height: '30px' }}
              onClick={onOpenDbaConsole}
              title="Abrir el panel de diagnóstico detallado para desarrolladores, consultores y administradores de bases de datos"
            >
              <Settings size={13} />
              Abrir Consola de Diagnóstico (DBA)
            </button>
          </div>
        )}
      </div>
      )}
    </div>
  );
}
