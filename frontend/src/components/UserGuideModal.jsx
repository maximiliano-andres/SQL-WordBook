import { useState } from 'react';
import { X, BookOpen, Layers, Link2, Download, Lightbulb, Sparkles } from 'lucide-react';

const TABS = [
  { id: 'general', label: 'Navegación', Icon: Layers },
  { id: 'fk', label: 'Relaciones (FK)', Icon: Link2 },
  { id: 'export', label: 'Exportaciones', Icon: Download },
  { id: 'custom_reports', label: 'Reportes y Cruces', Icon: Sparkles },
  { id: 'tips', label: 'Tips y Atajos', Icon: Lightbulb }
];

// Modal de "Guía de Uso Interactivo": comportamiento visual idéntico al que
// vivía inline en App.jsx. Maneja su propio estado de pestaña activa
// (manualTab) internamente ya que ningún otro componente lo necesita.
// Los bloques de estilo repetidos 3+ veces (título de sección, tarjetas,
// banners de tip/seguridad, filas de la pestaña "Tips") se migraron a clases
// `manual-*` en index.css; ver esa hoja de estilos para el detalle visual.
export default function UserGuideModal({ onClose }) {
  const [manualTab, setManualTab] = useState('general');

  return (
    <div className="dba-modal-overlay" onClick={onClose}>
      <div className="dba-modal dba-modal--wide" onClick={(e) => e.stopPropagation()}>
        <div className="dba-modal-header dba-modal-header--accent">
          <h3 className="dba-modal-title">
            <BookOpen size={18} />
            Guía de Uso Interactivo <span>Manual</span>
          </h3>
          <button
            className="dba-close-btn"
            onClick={onClose}
            title="Cerrar guía"
          >
            <X size={18} />
          </button>
        </div>

        <div className="dba-modal-body dba-modal-body--split">
          {/* Sidebar del manual */}
          <div className="manual-tab-sidebar">
            {TABS.map(({ id, label, Icon }) => (
              <button
                key={id}
                className={`manual-tab-btn ${manualTab === id ? 'active' : ''}`}
                onClick={() => setManualTab(id)}
              >
                <Icon size={15} />
                {label}
              </button>
            ))}
          </div>

          {/* Contenido del manual */}
          <div className="manual-tab-content">
            {manualTab === 'general' && (
              <div className="manual-section">
                <div className="manual-section-header">
                  <span className="manual-section-emoji">🏁</span>
                  <h4 className="manual-section-title">¡Bienvenido a tu SQL Server Workbook!</h4>
                </div>
                <p className="manual-lead-text">
                  Esta aplicación te permite explorar tus bases de datos SQL Server mediante una interfaz idéntica a <strong>Microsoft Excel</strong>. Aquí tienes los aspectos clave para empezar:
                </p>

                <div className="manual-grid-2">
                  <div className="manual-card">
                    <div className="manual-card-title">
                      <span>📄 Hojas de Cálculo</span>
                    </div>
                    <p className="manual-card-text">
                      En la parte inferior de la pantalla verás las tablas de tu base de datos listadas como pestañas de hojas. Haz clic en cualquiera para cargar sus registros al instante.
                    </p>
                  </div>

                  <div className="manual-card">
                    <div className="manual-card-title">
                      <span>🔢 Paginación y Límites</span>
                    </div>
                    <p className="manual-card-text">
                      En la pestaña <strong>Inicio</strong> del Ribbon superior, puedes cambiar el número de filas por página (10, 15, 30, etc.) y paginar de forma ultra fluida.
                    </p>
                  </div>
                </div>

                <div className="manual-tip-banner">
                  <span className="manual-tip-emoji">💡</span>
                  <span className="manual-tip-text">
                    <strong>Tip Pro:</strong> El alineamiento de las celdas se comporta igual que en Excel. Los números se alinean a la derecha en azul y los valores nulos (<code>NULL</code>) se muestran en cursiva gris para facilitar la lectura rápida.
                  </span>
                </div>
              </div>
            )}

            {manualTab === 'fk' && (
              <div className="manual-section">
                <div className="manual-section-header">
                  <span className="manual-section-emoji">🔗</span>
                  <h4 className="manual-section-title">Resolución Inteligente de Relaciones (FK)</h4>
                </div>
                <p className="manual-lead-text">
                  El sistema detecta automáticamente las relaciones físicas de clave foránea y te permite definir <strong>relaciones virtuales (Custom FKs)</strong>. Esto asocia IDs numéricos con nombres descriptivos de forma automática.
                </p>

                <div className="manual-feature-list">
                  <div className="manual-feature-card">
                    <div className="manual-feature-icon green">
                      <Link2 size={16} />
                    </div>
                    <div>
                      <h5 className="manual-feature-title">Relaciones Virtuales (Creación Directa)</h5>
                      <p className="manual-card-text">
                        ¿Falta una FK física en tu base de datos? Haz doble clic en el encabezado de la columna de un ID (ej: <code>ClienteId</code>), selecciona la tabla origen y columna descriptiva (ej: <code>Nombre</code>) y ¡listo! Se resolverá automáticamente.
                      </p>
                    </div>
                  </div>

                  <div className="manual-feature-card">
                    <div className="manual-feature-icon amber">
                      <Layers size={16} />
                    </div>
                    <div>
                      <h5 className="manual-feature-title">Visualización Flexible</h5>
                      <p className="manual-card-text">
                        En el Ribbon, usa el control "Mostrar FK" para alternar la visualización entre: <strong>Código ID</strong>, el <strong>Valor Descriptivo</strong>, o <strong>Ambos combinados</strong>.
                      </p>
                    </div>
                  </div>
                </div>
              </div>
            )}

            {manualTab === 'export' && (
              <div className="manual-section">
                <div className="manual-section-header">
                  <span className="manual-section-emoji">📥</span>
                  <h4 className="manual-section-title">Exportación de Reportes a Excel y CSV</h4>
                </div>
                <p className="manual-lead-text">
                  Puedes descargar la información de cualquier tabla a tu disco local con un solo clic. El sistema genera archivos nativos y optimizados de manera segura.
                </p>

                <div className="manual-grid-2">
                  <div className="manual-card">
                    <h5 className="manual-numbered-card-title">📁 Reporte Excel (.xlsx)</h5>
                    <p className="manual-card-text">
                      Genera un archivo compatible con Excel utilizando la API nativa de Apache POI por streaming (SXSSF). Esto comprime el tamaño del archivo y reduce la carga en memoria del servidor.
                    </p>
                  </div>

                  <div className="manual-card">
                    <h5 className="manual-numbered-card-title">📄 Reporte CSV</h5>
                    <p className="manual-card-text">
                      Exporta los datos delimitados por comas para su lectura por sistemas automatizados. Incluye protección automática contra <em>Inyección de Fórmulas CSV</em>.
                    </p>
                  </div>
                </div>

                <div className="manual-security-banner">
                  <span>🛡️</span>
                  <span>
                    <strong>Seguridad:</strong> La exportación cuenta con límites concurrentes para evitar la saturación de conexiones y salvaguardar el rendimiento de tu base de datos productiva.
                  </span>
                </div>
              </div>
            )}

            {manualTab === 'custom_reports' && (
              <div className="manual-section">
                <div className="manual-section-header">
                  <span className="manual-section-emoji">📊</span>
                  <h4 className="manual-section-title">Constructor de Reportes Personalizados</h4>
                </div>
                <p className="manual-lead-text">
                  Permite cruzar múltiples tablas (LEFT JOIN, INNER JOIN), seleccionar columnas de diferentes fuentes, aplicar filtros dinámicos y exportar reportes consolidados:
                </p>

                <div className="manual-numbered-list">
                  <div className="manual-numbered-card">
                    <h5 className="manual-numbered-card-title">1. Detección Inteligente de Cruces (FKs)</h5>
                    <p className="manual-card-text">
                      Al seleccionar tu tabla principal, el sistema detecta automáticamente las relaciones de Claves Foráneas (reales y virtuales) y te permite unirlas con un solo clic.
                    </p>
                  </div>

                  <div className="manual-numbered-card">
                    <h5 className="manual-numbered-card-title">2. Selección y Renombrado de Columnas</h5>
                    <p className="manual-card-text">
                      Elige qué columnas incluir de cada tabla y asígnales nombres amigables (aliases) que aparecerán directamente en el encabezado de tu archivo Excel.
                    </p>
                  </div>

                  <div className="manual-numbered-card">
                    <h5 className="manual-numbered-card-title">3. Plantillas Reutilizables en Base de Datos</h5>
                    <p className="manual-card-text">
                      Guarda tus consultas complejas con un nombre y descripción. Quedarán almacenadas en la base de datos para que puedas ejecutarlas o modificarlas en cualquier momento.
                    </p>
                  </div>
                </div>
              </div>
            )}

            {manualTab === 'tips' && (
              <div className="manual-section">
                <div className="manual-section-header">
                  <span className="manual-section-emoji">💡</span>
                  <h4 className="manual-section-title">Consejos Pro y Diagnóstico (DBA)</h4>
                </div>
                <p className="manual-lead-text">
                  Sácale el máximo provecho a la aplicación con estos atajos y funcionalidades avanzadas de rendimiento:
                </p>

                <div className="manual-tips-list">
                  <div className="manual-tips-row">
                    <span className="manual-tips-label">Recargar Datos Actuales</span>
                    <kbd className="manual-kbd">F5 o Botón Refrescar</kbd>
                  </div>

                  <div className="manual-tips-row">
                    <span className="manual-tips-label">Consola de Diagnóstico de Servidor</span>
                    <span className="manual-tips-value">Pestaña Rendimiento ➡️ Abrir Consola DBA</span>
                  </div>

                  <div className="manual-tips-row">
                    <span className="manual-tips-label">Caché de Consultas</span>
                    <span className="manual-tips-value muted">Activo automático por 60 segundos en metadatos</span>
                  </div>
                </div>

                <div className="manual-warn-banner">
                  <span className="manual-tip-emoji">⚡</span>
                  <span style={{ fontSize: '11.5px', color: 'var(--text-secondary)', lineHeight: '1.5' }}>
                    <strong>Nota de Rendimiento:</strong> Si notas que los datos tardan unos milisegundos en cargar al cambiar de tabla por primera vez, es normal. La aplicación realiza consultas dinámicas optimizadas sobre el catálogo de SQL Server y luego las almacena en la caché de Caffeine para entregas instantáneas.
                  </span>
                </div>
              </div>
            )}
          </div>
        </div>

        <div className="dba-footer dba-footer--sidebar">
          <button
            className="excel-btn primary"
            onClick={onClose}
          >
            ¡Entendido, a explorar!
          </button>
        </div>
      </div>
    </div>
  );
}
