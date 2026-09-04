import React, { useState, useEffect } from 'react';
import {
  Database, Server, CheckCircle2, AlertCircle, X,
  Activity, Play, Save, Trash2, Shield,
  HardDrive, Cpu, Terminal, Key, Globe, FolderOpen
} from 'lucide-react';
import { useToast } from '../context/ToastContext';
import { useAuth } from '../context/AuthContext';

const ENGINES = [
  { id: 'SQLSERVER', name: 'SQL Server', icon: '🗄️', port: 1433, defaultDb: 'CL3530BD01MAP', color: '#107c41', placeholder: 'localhost o 192.168.1.100' },
  { id: 'POSTGRESQL', name: 'PostgreSQL', icon: '🐘', port: 5432, defaultDb: 'postgres', color: '#336791', placeholder: 'localhost o db.ejemplo.com' },
  { id: 'MYSQL', name: 'MySQL', icon: '🐬', port: 3306, defaultDb: 'mysql', color: '#00758f', placeholder: 'localhost o 127.0.0.1' },
  { id: 'MARIADB', name: 'MariaDB', icon: '🦭', port: 3306, defaultDb: 'mariadb', color: '#c0765a', placeholder: 'localhost' },
  { id: 'ORACLE', name: 'Oracle DB', icon: '⚡', port: 1521, defaultDb: 'XE', color: '#f80000', placeholder: 'localhost o oracle-server' },
  { id: 'SQLITE', name: 'SQLite', icon: '🪶', port: null, defaultDb: 'database.sqlite', color: '#003b57', placeholder: './database.sqlite o /ruta/base.db' },
  { id: 'CUSTOM', name: 'JDBC Directo', icon: '🔌', port: null, defaultDb: '', color: '#8855ff', placeholder: 'jdbc:motor://servidor:puerto/bd' }
];

export default function ConnectionModal({ isOpen, onClose, onConnectionSuccess }) {
  const { apiFetch } = useAuth();
  const { success, error: toastError, info, confirm } = useToast();

  const [activeTab, setActiveTab] = useState('new'); // 'new' | 'saved'
  const [engineType, setEngineType] = useState('SQLSERVER');
  const [profileName, setProfileName] = useState('');
  const [host, setHost] = useState('localhost');
  const [port, setPort] = useState(1433);
  const [databaseName, setDatabaseName] = useState('CL3530BD01MAP');
  const [schema, setSchema] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [customJdbcUrl, setCustomJdbcUrl] = useState('');
  const [sslMode, setSslMode] = useState('prefer');
  const [trustCert, setTrustCert] = useState(true);

  // Estados de Operación
  const [isTesting, setIsTesting] = useState(false);
  const [testResult, setTestResult] = useState(null); // { success, message, latencyMs, databaseProduct, databaseVersion }
  const [isConnecting, setIsConnecting] = useState(false);

  // Conexiones Guardadas
  const [savedConnections, setSavedConnections] = useState(() => {
    try {
      const saved = localStorage.getItem('pushdb_saved_connections');
      return saved ? JSON.parse(saved) : [];
    } catch {
      return [];
    }
  });

  // Al cambiar motor, sugerir valores por defecto
  const handleSelectEngine = (engineId) => {
    setEngineType(engineId);
    setTestResult(null);
    const engine = ENGINES.find(e => e.id === engineId);
    if (engine) {
      if (engine.port) setPort(engine.port);
      if (engine.defaultDb && !databaseName) setDatabaseName(engine.defaultDb);
    }
  };

  // Cargar conexión guardada
  const handleLoadSaved = (conn) => {
    setEngineType(conn.engineType || 'SQLSERVER');
    setProfileName(conn.profileName || '');
    setHost(conn.host || 'localhost');
    setPort(conn.port || 1433);
    setDatabaseName(conn.databaseName || '');
    setSchema(conn.schema || '');
    setUsername(conn.username || '');
    setPassword(conn.password || '');
    setCustomJdbcUrl(conn.customJdbcUrl || '');
    setTestResult(null);
    setActiveTab('new');
    info(`Perfil "${conn.profileName || 'Conexión'}" cargado`, 'Gestor de Conexiones');
  };

  // Guardar conexión actual en favoritos
  const handleSaveCurrentProfile = () => {
    const name = profileName.trim() || `${engineType} - ${databaseName || host}`;
    const newProfile = {
      id: Date.now().toString(),
      profileName: name,
      engineType,
      host,
      port,
      databaseName,
      schema,
      username,
      password,
      customJdbcUrl,
      savedAt: new Date().toLocaleDateString()
    };

    const updated = [newProfile, ...savedConnections.filter(c => c.profileName !== name)];
    setSavedConnections(updated);
    localStorage.setItem('pushdb_saved_connections', JSON.stringify(updated));
    success('Conexión guardada en favoritos', name);
  };

  // Eliminar conexión guardada con confirmación
  const handleDeleteSaved = (id, e) => {
    e.stopPropagation();
    confirm({
      title: '¿Eliminar conexión guardada?',
      message: '¿Estás seguro de que deseas eliminar este perfil de conexión favorito?',
      confirmText: 'Eliminar',
      cancelText: 'Cancelar',
      type: 'danger',
      onConfirm: () => {
        const updated = savedConnections.filter(c => c.id !== id);
        setSavedConnections(updated);
        localStorage.setItem('pushdb_saved_connections', JSON.stringify(updated));
        info('Perfil de conexión eliminado', 'Gestor de Conexiones');
      }
    });
  };

  // Probar Conexión
  const handleTestConnection = async () => {
    setIsTesting(true);
    setTestResult(null);

    const payload = {
      engineType,
      host,
      port: Number(port) || null,
      databaseName,
      username,
      password,
      schema,
      customJdbcUrl: engineType === 'CUSTOM' ? customJdbcUrl : null,
      additionalParams: {
        sslmode: sslMode,
        trustServerCertificate: String(trustCert)
      }
    };

    try {
      const res = await apiFetch('/api/db/connection/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      const data = await res.json();
      setTestResult(data);
      if (data.success) {
        success(`Conexión exitosa a ${data.databaseProduct} (${data.latencyMs} ms)`, 'Prueba de Conexión');
      } else {
        toastError(data.message || 'Error al conectar con la base de datos', 'Fallo de Conexión');
      }
    } catch (err) {
      setTestResult({
        success: false,
        message: err.message || 'Error de red al intentar probar la conexión'
      });
      toastError(err.message, 'Error de Comunicación');
    } finally {
      setIsTesting(false);
    }
  };

  // Conectar y Aplicar
  const handleConnect = async () => {
    setIsConnecting(true);

    const payload = {
      engineType,
      host,
      port: Number(port) || null,
      databaseName,
      username,
      password,
      schema,
      customJdbcUrl: engineType === 'CUSTOM' ? customJdbcUrl : null,
      additionalParams: {
        sslmode: sslMode,
        trustServerCertificate: String(trustCert)
      }
    };

    try {
      const res = await apiFetch('/api/db/connection/connect', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      const data = await res.json();
      if (!res.ok || !data.success) {
        throw new Error(data.message || 'No se pudo establecer la conexión');
      }

      success(`Conectado a ${data.connectionInfo?.displayName || engineType}`, 'Base de Datos Activa');
      if (onConnectionSuccess) {
        onConnectionSuccess(data.connectionInfo);
      }
      onClose();
    } catch (err) {
      toastError(err.message || 'Error al conectar', 'Error');
    } finally {
      setIsConnecting(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="conn-modal-overlay" onClick={onClose}>
      <div className="conn-modal-card" onClick={(e) => e.stopPropagation()}>
        {/* Cabecera */}
        <div className="conn-modal-header">
          <div className="conn-header-title-area">
            <Database size={20} className="conn-header-icon" />
            <div>
              <h3 className="conn-modal-title">Gestor de Conexiones de Base de Datos</h3>
              <p className="conn-modal-subtitle">Conecta en tiempo real a MySQL, PostgreSQL, Oracle, SQLite o SQL Server</p>
            </div>
          </div>
          <button className="conn-close-btn" onClick={onClose} title="Cerrar ventana">
            <X size={18} />
          </button>
        </div>

        {/* Pestañas Nueva / Guardadas */}
        <div className="conn-tabs">
          <button
            className={`conn-tab ${activeTab === 'new' ? 'active' : ''}`}
            onClick={() => setActiveTab('new')}
          >
            <Server size={14} />
            <span>Configurar Conexión</span>
          </button>
          <button
            className={`conn-tab ${activeTab === 'saved' ? 'active' : ''}`}
            onClick={() => setActiveTab('saved')}
          >
            <HardDrive size={14} />
            <span>Conexiones Guardadas ({savedConnections.length})</span>
          </button>
        </div>

        <div className="conn-modal-body">
          {activeTab === 'new' && (
            <>
              {/* Selector de Motores */}
              <div className="conn-engine-grid">
                {ENGINES.map((engine) => (
                  <button
                    key={engine.id}
                    type="button"
                    className={`conn-engine-card ${engineType === engine.id ? 'active' : ''}`}
                    onClick={() => handleSelectEngine(engine.id)}
                  >
                    <span className="conn-engine-icon">{engine.icon}</span>
                    <span className="conn-engine-name">{engine.name}</span>
                    {engine.port && <span className="conn-engine-port">:{engine.port}</span>}
                  </button>
                ))}
              </div>

              {/* Resultado de prueba de conexión */}
              {testResult && (
                <div className={`conn-test-banner ${testResult.success ? 'success' : 'error'} animate-fade-in`}>
                  {testResult.success ? (
                    <>
                      <CheckCircle2 size={18} className="conn-test-icon success" />
                      <div>
                        <strong>{testResult.message}</strong>
                        <div className="conn-test-detail">
                          Latencia: <span>{testResult.latencyMs} ms</span> | Motor: <span>{testResult.databaseProduct} {testResult.databaseVersion || ''}</span>
                        </div>
                      </div>
                    </>
                  ) : (
                    <>
                      <AlertCircle size={18} className="conn-test-icon error" />
                      <div>
                        <strong>Fallo de Conexión:</strong>
                        <div className="conn-test-detail">{testResult.message}</div>
                      </div>
                    </>
                  )}
                </div>
              )}

              {/* Formulario de Configuración */}
              <div className="conn-form-grid">
                {engineType === 'CUSTOM' ? (
                  <div className="conn-form-col full">
                    <label className="conn-label">Cadena JDBC Completa</label>
                    <input
                      type="text"
                      className="conn-input font-mono"
                      placeholder="jdbc:postgresql://servidor:5432/mibasedatos"
                      value={customJdbcUrl}
                      onChange={(e) => setCustomJdbcUrl(e.target.value)}
                    />
                  </div>
                ) : engineType === 'SQLITE' ? (
                  <div className="conn-form-col full">
                    <label className="conn-label">
                      <FolderOpen size={14} style={{ display: 'inline', marginRight: '4px' }} />
                      Ruta del Archivo SQLite (.db / .sqlite)
                    </label>
                    <input
                      type="text"
                      className="conn-input font-mono"
                      placeholder="./mi_base_de_datos.sqlite o /ruta/absoluta/datos.db"
                      value={databaseName}
                      onChange={(e) => setDatabaseName(e.target.value)}
                    />
                  </div>
                ) : (
                  <>
                    <div className="conn-form-col span-2">
                      <label className="conn-label">Servidor / Host</label>
                      <input
                        type="text"
                        className="conn-input"
                        placeholder={ENGINES.find(e => e.id === engineType)?.placeholder || 'localhost'}
                        value={host}
                        onChange={(e) => setHost(e.target.value)}
                      />
                    </div>

                    <div className="conn-form-col">
                      <label className="conn-label">Puerto</label>
                      <input
                        type="number"
                        className="conn-input font-mono"
                        placeholder="1433"
                        value={port || ''}
                        onChange={(e) => setPort(e.target.value)}
                      />
                    </div>

                    <div className="conn-form-col span-2">
                      <label className="conn-label">Base de Datos / Catálogo / SID</label>
                      <input
                        type="text"
                        className="conn-input"
                        placeholder="Ej. ventas_db o CL3530BD01MAP"
                        value={databaseName}
                        onChange={(e) => setDatabaseName(e.target.value)}
                      />
                    </div>

                    <div className="conn-form-col">
                      <label className="conn-label">Esquema (Opcional)</label>
                      <input
                        type="text"
                        className="conn-input"
                        placeholder="dbo / public / HR"
                        value={schema}
                        onChange={(e) => setSchema(e.target.value)}
                      />
                    </div>
                  </>
                )}

                {engineType !== 'SQLITE' && (
                  <>
                    <div className="conn-form-col">
                      <label className="conn-label">Usuario</label>
                      <input
                        type="text"
                        className="conn-input"
                        placeholder="Usuario de la base de datos"
                        value={username}
                        onChange={(e) => setUsername(e.target.value)}
                      />
                    </div>

                    <div className="conn-form-col">
                      <label className="conn-label">Contraseña</label>
                      <input
                        type="password"
                        className="conn-input"
                        placeholder="••••••••••••"
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                      />
                    </div>

                    <div className="conn-form-col">
                      <label className="conn-label">Nombre del Perfil (Favorito)</label>
                      <input
                        type="text"
                        className="conn-input"
                        placeholder="Ej. BD Producción"
                        value={profileName}
                        onChange={(e) => setProfileName(e.target.value)}
                      />
                    </div>
                  </>
                )}
              </div>
            </>
          )}

          {activeTab === 'saved' && (
            <div className="conn-saved-list">
              {savedConnections.length === 0 ? (
                <div className="conn-saved-empty">
                  <HardDrive size={32} className="conn-empty-icon" />
                  <h4>No hay conexiones guardadas</h4>
                  <p>Guarda tus bases de datos frecuentes para alternar entre ellas con un solo clic.</p>
                </div>
              ) : (
                savedConnections.map((conn) => {
                  const engine = ENGINES.find(e => e.id === conn.engineType) || ENGINES[0];
                  return (
                    <div
                      key={conn.id}
                      className="conn-saved-card"
                      onClick={() => handleLoadSaved(conn)}
                    >
                      <div className="conn-saved-badge">
                        <span>{engine.icon}</span>
                      </div>
                      <div className="conn-saved-info">
                        <div className="conn-saved-name">{conn.profileName || engine.name}</div>
                        <div className="conn-saved-meta">
                          {conn.engineType === 'SQLITE' ? (
                            <span>{conn.databaseName}</span>
                          ) : (
                            <span>{conn.host}:{conn.port} · {conn.databaseName} ({conn.username || 'sin usuario'})</span>
                          )}
                        </div>
                      </div>
                      <button
                        className="conn-saved-delete-btn"
                        onClick={(e) => handleDeleteSaved(conn.id, e)}
                        title="Eliminar de favoritos"
                      >
                        <Trash2 size={15} />
                      </button>
                    </div>
                  );
                })
              )}
            </div>
          )}
        </div>

        {/* Acciones de Pie */}
        <div className="conn-modal-footer">
          {activeTab === 'new' && (
            <button
              type="button"
              className="excel-btn"
              onClick={handleSaveCurrentProfile}
              title="Guardar esta configuración en favoritos"
            >
              <Save size={14} />
              <span>Guardar Favorito</span>
            </button>
          )}

          <div style={{ marginLeft: 'auto', display: 'flex', gap: '8px' }}>
            <button
              type="button"
              className="excel-btn"
              onClick={handleTestConnection}
              disabled={isTesting || isConnecting}
              title="Probar si las credenciales y el host son válidos sin activar la conexión"
            >
              <Activity size={14} className={isTesting ? 'animate-spin' : ''} />
              <span>{isTesting ? 'Probando...' : 'Probar Conexión'}</span>
            </button>

            <button
              type="button"
              className="excel-btn primary"
              onClick={handleConnect}
              disabled={isConnecting}
              title="Establecer conexión activa y cargar datos"
            >
              <Play size={14} />
              <span>{isConnecting ? 'Conectando...' : 'Conectar y Cargar'}</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
