# Cumplimiento ISO/IEC 27001 · 27002 · 25010

Este documento relaciona los controles técnicos de **Multi-DB Workbook 1.1.0** con los controles
del Anexo A de ISO/IEC 27001:2022 (descritos en ISO/IEC 27002:2022) y con las características de
calidad de ISO/IEC 25010:2023. Sirve como evidencia para una auditoría y como guía de configuración.

> **Alcance.** Cubre solo los controles que implementa la aplicación. Los controles
> organizacionales, de personas y físicos (políticas, capacitación, gestión de proveedores,
> respaldos de la infraestructura, etc.) son responsabilidad de la organización que la opera.

## 1. Controles ISO/IEC 27002:2022

| Control | Cómo lo cubre la aplicación | Dónde está | Configuración |
|---|---|---|---|
| **5.12** Clasificación de la información | Cada Excel exportado lleva la clasificación «Confidencial – uso interno» en sus propiedades y en el pie de página. | `ExcelExportService.applyTraceability` | — |
| **5.15** Control de acceso | Toda la API `/api/**` exige autenticación; solo `/api/auth/status`, los motores soportados y `/actuator/health` son públicos. | `SecurityConfig` | — |
| **5.17** Información de autenticación | Contraseña cifrada con BCrypt en memoria, nunca guardada en el navegador. Advertencia en el log si tiene menos de 12 caracteres. | `SecurityConfig.userDetailsService` | `APP_PASSWORD` |
| **5.34** Privacidad y datos personales | Enmascaramiento opcional de columnas con datos personales (RUT, sueldos) en pantalla y en Excel. | `SensitiveColumnMasker` | `APP_MASKED_COLUMNS` |
| **8.5** Autenticación segura | Límite de 8 intentos por minuto por IP; mensaje de error genérico; sesión con cookie `HttpOnly` y `SameSite=Lax`; cierre por inactividad y duración máxima absoluta. | `LoginRateLimitFilter`, `SessionLifetimeFilter` | `SESSION_IDLE_TIMEOUT` (30 min), `SESSION_MAX_LIFETIME` (8 h) |
| **8.6** Gestión de capacidad | Tiempo máximo por consulta SQL, límite de filas exportables y de exportaciones simultáneas, Excel en *streaming*. | `DynamicDataSourceService`, `ExcelExportService` | `DB_QUERY_TIMEOUT_SECONDS`, `EXPORT_MAX_ROWS`, `EXPORT_MAX_CONCURRENT` |
| **8.8** Gestión de vulnerabilidades técnicas | `npm audit` en cada build, OWASP Dependency-Check semanal, Dependabot y SBOM CycloneDX dentro del JAR. | `.github/workflows/`, `.github/dependabot.yml` | Secreto opcional `NVD_API_KEY` en GitHub |
| **8.9** Gestión de la configuración | Configuración externa por variables de entorno, sin secretos en el repositorio; perfiles `dev` y `prod` separados. | `application*.yaml`, `.env.example` | `SPRING_PROFILES_ACTIVE` |
| **8.11** Enmascaramiento de datos | Ver 5.34. | `SensitiveColumnMasker` | `APP_MASKED_COLUMNS` |
| **8.12** Prevención de fuga de datos | Cada exportación queda registrada con usuario, IP y cantidad de filas; el archivo guarda quién lo generó y cuándo. | `AuditLog`, `ExcelExportService` | — |
| **8.15** Registro (*logging*) | Registro de auditoría separado (`logs/audit.log`, rotación diaria, retención configurable). No registra contraseñas, filtros ni datos de negocio, y neutraliza saltos de línea (CWE-117). | `AuditLog`, `logback-spring.xml` | `AUDIT_LOG_DIR`, `AUDIT_LOG_MAX_HISTORY` (365 días) |
| **8.16** Actividades de monitoreo | Se registran los inicios de sesión fallidos, los bloqueos por exceso de intentos, los accesos denegados y las peticiones de origen no permitido. Pueden enviarse a un SIEM. | `AuditLog` | — |
| **8.17** Sincronización de relojes | Las marcas de tiempo de auditoría usan ISO-8601 con zona horaria. El servidor debe sincronizarse por NTP (responsabilidad de la infraestructura). | `logback-spring.xml` | — |
| **8.20 / 8.22** Seguridad y segregación de redes | Lista opcional de servidores permitidos para conexiones dinámicas; impide usar la app para escanear la red interna o abrir archivos SQLite del servidor. | `DynamicDataSourceService.validateDestination` | `DB_ALLOWED_HOSTS` |
| **8.24** Uso de criptografía | La URL por defecto valida el certificado TLS de la base. Opción para exigir la validación también en las conexiones creadas desde la app. HSTS sobre HTTPS. | `application.yaml`, `DynamicDataSourceService.applyTlsPolicy` | `DB_REQUIRE_TLS_VALIDATION` |
| **8.25** Ciclo de vida de desarrollo seguro | CI con pruebas, cobertura mínima, análisis estático y auditoría de dependencias en cada cambio. | `.github/workflows/ci.yml` | — |
| **8.26** Requisitos de seguridad de las aplicaciones | CSP con `frame-ancestors 'none'`, `Referrer-Policy`, `Permissions-Policy`, `X-Content-Type-Options` y `X-Frame-Options`. | `SecurityConfig` | — |
| **8.28** Codificación segura | Consultas parametrizadas, identificadores validados contra el catálogo, bloqueo de parámetros JDBC peligrosos, protección contra *Formula Injection*, verificación de `Origin`/`Referer` (CSRF). | `SqlSafe`, `DynamicDataSourceService`, `OriginCheckFilter` | `APP_ALLOWED_ORIGINS` |
| **8.29** Pruebas de seguridad | Pruebas automatizadas de autenticación, límite de intentos, CSRF, sesión, cabeceras, destinos permitidos, inyección y enmascaramiento. | `src/test/` | — |
| **8.32** Gestión de cambios | Versionado semántico, `CHANGELOG.md` y validación por CI antes de fusionar. | `pom.xml`, `CHANGELOG.md` | — |

### Eventos de auditoría

| Evento | Cuándo se registra |
|---|---|
| `LOGIN` | Inicio de sesión exitoso (`SUCCESS`) o fallido (`FAILURE`, con el motivo) |
| `LOGIN_RATE_LIMITED` | IP bloqueada por exceso de intentos |
| `LOGOUT` | Cierre de sesión |
| `SESSION_MAX_LIFETIME_EXPIRED` | Sesión cerrada por superar la duración máxima |
| `CONNECTION_TEST` / `CONNECTION_SWITCH` | Prueba o cambio de la base de datos activa (motor, servidor, base y usuario; nunca la contraseña) |
| `EXPORT_TABLE` / `EXPORT_CUSTOM_REPORT` | Descarga a Excel: tabla o reporte, cantidad de columnas, filtros y filas |
| `TEMPLATE_SAVE` / `TEMPLATE_DELETE` | Alta, cambio o baja de una plantilla de reporte |
| `RELATIONS_SAVE` | Cambio de las relaciones (traducción de códigos) de una tabla |
| `ACCESS_DENIED` | Intento de acceder a una tabla o columna no permitida |
| `CSRF_ORIGIN_REJECTED` | Petición que modifica datos desde un origen ajeno |

## 2. Calidad del producto (ISO/IEC 25010:2023)

| Característica | Evidencia |
|---|---|
| **Adecuación funcional** | 369 pruebas automatizadas (329 de backend y 40 de frontend), con 88 % de cobertura de líneas en el backend y un mínimo exigido de 80 % en `mvn verify`. |
| **Eficiencia de desempeño** | Tiempo máximo por consulta, paginación de hasta 100 filas, caché de metadatos, exportación en *streaming* con `fetchSize` y resolución de códigos por lotes (sin N+1). |
| **Compatibilidad** | 6 motores de bases de datos (SQL Server, PostgreSQL, MySQL, MariaDB, Oracle y SQLite) mediante dialectos. |
| **Capacidad de interacción** | Modo Fácil y modo DBA, diseño adaptable de móvil a 4K, `lang="es"` para lectores de pantalla y modales accesibles. |
| **Fiabilidad** | Apagado ordenado, `HEALTHCHECK` en Docker, endpoints de *liveness* y *readiness*, cierre diferido del pool anterior al cambiar de conexión, detección de fugas sin falsas alarmas. |
| **Seguridad** | Ver la sección 1. |
| **Mantenibilidad** | Servicios con una responsabilidad cada uno, análisis estático (oxlint), cobertura medida (JaCoCo), CI y versionado semántico. |
| **Flexibilidad** | Configuración por variables de entorno, perfiles `dev`/`prod`, imagen Docker multi-stage. |
| **Protección (*safety*)** | Acceso de solo lectura sobre los datos de negocio; las únicas escrituras ocurren en las dos tablas propias de configuración. |

## 3. Configuración recomendada para producción

```env
SPRING_PROFILES_ACTIVE=prod
APP_PASSWORD=<mínimo 12 caracteres, generada aleatoriamente>
DB_ALLOWED_HOSTS=<servidores de base de datos autorizados>
DB_REQUIRE_TLS_VALIDATION=true
APP_MASKED_COLUMNS=<columnas con datos personales sensibles>
AUDIT_LOG_DIR=/app/logs   # montado en un volumen persistente y respaldado
```

Además, la organización debe:

- Publicar la app solo detrás de un proxy inverso con HTTPS.
- Sincronizar el reloj del servidor por NTP.
- Respaldar y proteger `audit.log` contra modificaciones, por ejemplo enviándolo a un SIEM o a un almacenamiento de solo escritura.
- Revisar periódicamente el registro de auditoría y los avisos de Dependabot.

## 4. Pendientes que requieren cambiar el flujo actual

Estos puntos fortalecerían el cumplimiento, pero cambian la forma de uso. Quedan como evolución planificada:

| Brecha | Control | Propuesta |
|---|---|---|
| Un único usuario compartido | 5.16 Gestión de identidades, 8.2 Privilegios | Usuarios con roles (Consulta / Administrador) o inicio de sesión con el directorio corporativo |
| Sin segundo factor de autenticación | 8.5 Autenticación segura | MFA (TOTP o el del directorio corporativo) |
| La conexión activa es común a todos | 8.3 Restricción de acceso a la información | Conexión independiente por sesión |
| Las columnas enmascaradas aún se pueden filtrar | 8.11 Enmascaramiento de datos | Permisos por columna según rol |
