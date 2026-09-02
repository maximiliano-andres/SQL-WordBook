import { Server, X } from 'lucide-react';

// Modal de la "Consola de Diagnóstico DBA": comportamiento visual idéntico al
// que vivía inline en App.jsx, ahora recibiendo dbInfo/onClose como props.
export default function DbaConsoleModal({ dbInfo, onClose }) {
  if (!dbInfo) return null;

  return (
    <div className="dba-modal-overlay" onClick={onClose}>
      <div className="dba-modal" onClick={(e) => e.stopPropagation()}>
        <div className="dba-modal-header">
          <h3 className="dba-modal-title">
            <Server size={18} className="excel-icon" />
            Consola de Diagnóstico de Base de Datos <span>DBA</span>
          </h3>
          <button
            className="dba-close-btn"
            onClick={onClose}
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
                <span className="dba-metric-value dba-metric-value--small">{dbInfo.databaseVersion || 'N/A'}</span>
              </div>
              <div className="dba-metric-row">
                <span className="dba-metric-label">Driver JDBC:</span>
                <span className="dba-metric-value">{dbInfo.driverName || 'N/A'}</span>
              </div>
              <div className="dba-metric-row">
                <span className="dba-metric-label">Versión Driver:</span>
                <span className="dba-metric-value">{dbInfo.driverVersion || 'N/A'}</span>
              </div>
              <div className="dba-metric-row dba-metric-row--stacked">
                <span className="dba-metric-label">Cadena JDBC de Conexión:</span>
                <span className="dba-metric-value dba-metric-value--wrap">{dbInfo.jdbcUrl || 'N/A'}</span>
              </div>
            </div>

            {/* Tarjeta 2: Métricas de Sesiones y Configuración */}
            <div className="dba-card">
              <h4 className="dba-card-title">Rendimiento e Instancia</h4>
              <div className="dba-metric-row">
                <span className="dba-metric-label">Estado de la BD:</span>
                <span className="dba-metric-value" style={{ color: dbInfo.dbState === 'ONLINE' ? 'var(--excel-green-light)' : 'var(--excel-amber)' }}>
                  ● {dbInfo.dbState || 'ONLINE'}
                </span>
              </div>
              <div className="dba-metric-row">
                <span className="dba-metric-label">Conexiones/Procesos Activos:</span>
                <span className="dba-metric-value dba-metric-value--accent-bold">
                  {dbInfo.activeConnections !== null ? dbInfo.activeConnections : 'N/A'}
                </span>
              </div>
              <div className="dba-metric-row">
                <span className="dba-metric-label">Modelo de Recuperación:</span>
                <span className="dba-metric-value">{dbInfo.dbRecoveryModel || 'SIMPLE'}</span>
              </div>
              <div className="dba-metric-row">
                <span className="dba-metric-label">Colación (Collation):</span>
                <span className="dba-metric-value dba-metric-value--small">{dbInfo.dbCollation || 'N/A'}</span>
              </div>
              <div className="dba-metric-row dba-metric-row--divider">
                <span className="dba-metric-label">Pool de Conexión Local:</span>
                <span className="dba-metric-value dba-metric-value--accent">HikariCP (Activo)</span>
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
                <span className="dba-metric-value dba-metric-value--accent">
                  {dbInfo.customFksCount !== null ? dbInfo.customFksCount : '0'} activas
                </span>
              </div>
            </div>

            {/* Tarjeta 4: Almacenamiento y Archivos de Base de Datos */}
            <div className="dba-card">
              <h4 className="dba-card-title">Almacenamiento Físico</h4>
              <div className="dba-metric-row">
                <span className="dba-metric-label">Tamaño Total en Disco:</span>
                <span className="dba-metric-value dba-metric-value--warn-bold">
                  {dbInfo.totalSizeMb !== null ? `${dbInfo.totalSizeMb} MB` : 'N/A'}
                </span>
              </div>

              {dbInfo.dbFiles && dbInfo.dbFiles.length > 0 ? (
                <table className="dba-file-table">
                  <thead>
                    <tr>
                      <th>Archivo Lógico</th>
                      <th>Tipo</th>
                      <th className="dba-td-right">Tamaño</th>
                    </tr>
                  </thead>
                  <tbody>
                    {dbInfo.dbFiles.map(file => (
                      <tr key={file.name}>
                        <td className="dba-td-name" title={file.name}>
                          {file.name}
                        </td>
                        <td>
                          <span className={`dba-file-type-badge ${file.type.toLowerCase()}`}>
                            {file.type}
                          </span>
                        </td>
                        <td className="dba-td-right dba-td-mono">
                          {file.sizeMb} MB
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <span className="dba-metric-value--small" style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>
                  Sin acceso al tamaño de archivos individuales
                </span>
              )}
            </div>

          </div>
        </div>

        <div className="dba-footer">
          <button
            className="excel-btn primary"
            onClick={onClose}
          >
            Cerrar Panel
          </button>
        </div>
      </div>
    </div>
  );
}
