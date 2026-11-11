import React, { useState, useEffect, useRef } from 'react';
import {
  Database, RefreshCw, Download, Settings,
  ChevronLeft, ChevronRight, Table, Cpu, Link2,
  HelpCircle, Layers, Plug, LogOut, User
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';

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
  onOpenConnectionModal,
  currentView = 'explorer',
  setCurrentView = () => {},
  uxMode = 'simple',
  onSetUxMode = () => {}
}) {
  const { user, logout, isAuthenticated } = useAuth();
  const [activeTab, setActiveTab] = useState('home'); // 'home', 'data', 'about'
  const [showColumnsDropdown, setShowColumnsDropdown] = useState(false);
  const columnsDropdownRef = useRef(null);

  // Cierra el desplegable de columnas al tocar/clicar fuera o con Escape
  // (en pantallas táctiles no existía otra forma de cerrarlo que volver al botón).
  useEffect(() => {
    if (!showColumnsDropdown) return undefined;
    const handlePointer = (e) => {
      if (columnsDropdownRef.current && !columnsDropdownRef.current.contains(e.target)) {
        setShowColumnsDropdown(false);
      }
    };
    const handleKey = (e) => { if (e.key === 'Escape') setShowColumnsDropdown(false); };
    document.addEventListener('pointerdown', handlePointer);
    document.addEventListener('keydown', handleKey);
    return () => {
      document.removeEventListener('pointerdown', handlePointer);
      document.removeEventListener('keydown', handleKey);
    };
  }, [showColumnsDropdown]);

  const handleToggleTab = (tab) => {
    setActiveTab(tab);
    setShowColumnsDropdown(false);
  };

  return (
    <div className="ribbon">
      {/* Barra superior de identificación */}
      <div className="ribbon-top-bar">
        <div className="excel-logo-area">
          <img src="/max_andres_head_256.webp" alt="Logo Multi-DB Workbook" className="excel-logo" width="28" height="28" />
          <h1 className="app-title">Multi-DB <span className="app-title-suffix">Workbook</span></h1>
        </div>

        {/* Selector de Modo de Trabajo Principal */}
        <div className="view-mode-switcher" role="tablist" aria-label="Vista principal">
          <button
            role="tab"
            aria-selected={currentView === 'explorer'}
            className={`view-mode-btn ${currentView === 'explorer' ? 'active' : ''}`}
            onClick={() => setCurrentView('explorer')}
            title="Explorador de tablas individuales estilo Excel"
          >
            <Table size={13} />
            <span className="label-long">{uxMode === 'simple' ? 'Hojas de Datos' : 'Explorador de Tablas'}</span>
            <span className="label-short">{uxMode === 'simple' ? 'Datos' : 'Tablas'}</span>
          </button>
          <button
            role="tab"
            aria-selected={currentView === 'custom-reports'}
            className={`view-mode-btn ${currentView === 'custom-reports' ? 'active' : ''}`}
            onClick={() => setCurrentView('custom-reports')}
            title="Constructor y visor de reportes personalizados con cruces multi-tabla"
          >
            <Layers size={13} />
            <span className="label-long">{uxMode === 'simple' ? 'Cruzar y Armar Reportes' : 'Reportes Personalizados (Joins)'}</span>
            <span className="label-short">Reportes</span>
          </button>
        </div>

        {/* Selector de Experiencia Dual (Modo Fácil vs Modo DBA) */}
        <div className="ux-mode-toggle" title="Alternar entre interfaz amigable tipo Excel/Buk o interfaz técnica de Base de Datos">
          <button
            className={`ux-mode-btn ${uxMode === 'simple' ? 'active simple' : ''}`}
            onClick={() => onSetUxMode('simple')}
            aria-pressed={uxMode === 'simple'}
          >
            <span className="ux-label-long">🟢 Modo Fácil (Excel)</span>
            <span className="ux-label-short">Fácil</span>
          </button>
          <button
            className={`ux-mode-btn ${uxMode === 'advanced' ? 'active advanced' : ''}`}
            onClick={() => onSetUxMode('advanced')}
            aria-pressed={uxMode === 'advanced'}
          >
            <span className="ux-label-long">🛠️ Modo DBA</span>
            <span className="ux-label-short">DBA</span>
          </button>
        </div>

        <div className="ribbon-actions">
          {/* Botón de Gestión de Conexión de Base de Datos */}
          <button
            className="excel-btn conn-trigger-btn"
            onClick={onOpenConnectionModal}
            title={dbInfo ? `Conexión activa: ${dbInfo.databaseProduct || 'BD'} (${dbInfo.currentDatabase || 'Principal'}). Clic para administrar o alternar motor.` : 'Configurar conexión de base de datos'}
          >
            <Plug size={13} className="excel-icon" />
            <span className="conn-trigger-label">{dbInfo ? `${dbInfo.databaseProduct || 'BD'}${dbInfo.currentDatabase ? ` · ${dbInfo.currentDatabase}` : ''}` : 'Conexión BD'}</span>
            <span className={`status-dot ${dbInfo?.databaseProduct ? 'online' : ''}`}></span>
          </button>

          <button
            className="excel-btn ribbon-guide-btn"
            onClick={onOpenManual}
            title="Abrir Manual de Uso Interactivo y atajos de teclado"
            aria-label="Abrir guía de uso"
          >
            <HelpCircle size={13} className="excel-icon" />
            <span className="btn-label">Guía</span>
          </button>

          {/* Perfil de Usuario y Logout */}
          {isAuthenticated && user && (
            <div className="user-profile-pill" title={`Usuario autenticado: ${user.username}. Clic en el icono de salir para cerrar sesión.`}>
              <User size={13} className="excel-icon" />
              <span className="user-profile-name">{user.username}</span>
              <button
                type="button"
                className="user-logout-btn"
                onClick={logout}
                title="Cerrar sesión de usuario"
                aria-label="Cerrar sesión"
              >
                <LogOut size={12} />
              </button>
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
            className="ribbon-tab ribbon-tab--cta"
            onClick={() => setCurrentView('custom-reports')}
            title="Abrir el generador de reportes personalizados y cruces multi-tabla"
          >
            <Layers size={13} className="inline-icon" />
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
                aria-label="Página anterior"
              >
                <ChevronLeft size={16} />
                <span className="btn-label">Anterior</span>
              </button>

              <span
                className="ribbon-page-indicator"
                title={`Visualizando página ${page} de un total de ${totalPages || 1}`}
              >
                Pág. <strong>{page}</strong> de <strong>{totalPages || 1}</strong>
              </span>

              <button
                className="excel-btn"
                onClick={onNextPage}
                disabled={page >= totalPages || isDataLoading}
                title="Cargar la página siguiente de registros en la grilla"
                aria-label="Página siguiente"
              >
                <span className="btn-label">Siguiente</span>
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
                  <Link2 size={12} className="inline-icon" />
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
              <div className="control-group control-group--dropdown" ref={columnsDropdownRef} title="Configuración de la estructura de campos del reporte final de Excel">
                <span className="control-label">Columnas para el Reporte</span>
                <button
                  className="excel-btn"
                  onClick={() => setShowColumnsDropdown(!showColumnsDropdown)}
                  disabled={isDataLoading}
                  aria-expanded={showColumnsDropdown}
                  title="Haz clic para desplegar la lista de columnas y marcar cuáles deseas incluir en el reporte de Excel (.xlsx)"
                >
                  <Table size={14} />
                  Columnas ({selectedColumns.length} de {columns.length})
                </button>

                {showColumnsDropdown && (
                  <div className="ribbon-dropdown">
                    {/* Botones de acción rápida */}
                    <div className="ribbon-dropdown-actions">
                      <button
                        className="excel-btn excel-btn--xs"
                        onClick={() => setSelectedColumns(columns.map(c => c.name))}
                        title="Marcar todas las columnas de la lista para incluirlas en la exportación"
                      >
                        Marcar todas
                      </button>
                      <button
                        className="excel-btn excel-btn--xs"
                        onClick={() => setSelectedColumns([])}
                        title="Desmarcar todas las columnas de la lista para realizar una selección limpia"
                      >
                        Desmarcar todas
                      </button>
                    </div>

                    {/* Lista con scroll de checkboxes */}
                    <div className="ribbon-dropdown-list">
                      {columns.map(col => {
                        const isChecked = selectedColumns.includes(col.name);
                        return (
                          <label
                            key={col.name}
                            className={`ribbon-dropdown-item ${isChecked ? 'checked' : ''}`}
                            title={`Incluir columna '${col.name}' en la descarga del archivo de Excel`}
                          >
                            <input
                              type="checkbox"
                              checked={isChecked}
                              onChange={(e) => {
                                if (e.target.checked) {
                                  setSelectedColumns([...selectedColumns, col.name]);
                                } else {
                                  setSelectedColumns(selectedColumns.filter(c => c !== col.name));
                                }
                              }}
                            />
                            <span className="text-ellipsis" title={col.name}>
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
          <div className="ribbon-about">
            <div className="ribbon-about-stats">
              <div className="ribbon-stat" title="El frontend de la aplicación web fue desarrollado con React y empaquetado ultra rápido con compiladores en Rust">
                <Cpu size={14} className="excel-icon" />
                <span><strong>Frontend:</strong> React + Vite</span>
              </div>
              <div className="ribbon-stat" title="Conexiones/procesos actualmente abiertos en la base de datos">
                <RefreshCw size={14} className="excel-icon" />
                <span><strong>Conexiones BD:</strong> <strong className="text-accent">{dbInfo?.activeConnections ?? '1'}</strong> activas</span>
              </div>
              <div className="ribbon-stat" title="Cantidad total de tablas físicas detectadas en la base de datos">
                <Table size={14} className="excel-icon" />
                <span><strong>Tablas Detectadas:</strong> <strong className="text-accent">{dbInfo?.totalTables ?? tablesCount}</strong></span>
              </div>
              {dbInfo?.totalSizeMb != null && (
                <div className="ribbon-stat" title="Tamaño total en disco consumido por los archivos de la base de datos">
                  <Database size={14} className="excel-icon" />
                  <span><strong>Tamaño Total:</strong> <strong className="text-warn">{dbInfo.totalSizeMb} MB</strong></span>
                </div>
              )}
            </div>

            <button
              className="excel-btn primary"
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
