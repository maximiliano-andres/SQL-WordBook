# Historial de cambios

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/). El proyecto usa
[versionado semántico](https://semver.org/lang/es/): MAYOR.MENOR.PARCHE.

## [1.1.0] - 2026-11-11

Endurecimiento según ISO/IEC 27001/27002:2022 e ISO/IEC 25010. Todas las opciones nuevas son
opcionales: con la configuración actual, la app se usa igual que antes. Detalle por control en
[docs/cumplimiento-iso.md](docs/cumplimiento-iso.md).

### Agregado
- Registro de auditoría en `logs/audit.log`: inicios de sesión (exitosos, fallidos y bloqueados),
  cierres de sesión, pruebas y cambios de conexión, exportaciones con cantidad de filas, plantillas,
  relaciones, accesos denegados y peticiones de origen no permitido.
- Verificación de `Origin`/`Referer` en peticiones que modifican datos (defensa adicional contra CSRF).
- Duración máxima absoluta de sesión (`SESSION_MAX_LIFETIME`, 8 h por defecto).
- Cabeceras `Referrer-Policy`, `Permissions-Policy`, HSTS y `frame-ancestors 'none'` en la CSP.
- Lista opcional de servidores permitidos para conexiones dinámicas (`DB_ALLOWED_HOSTS`).
- Validación TLS obligatoria opcional para conexiones dinámicas (`DB_REQUIRE_TLS_VALIDATION`).
- Tiempo máximo por consulta SQL (`DB_QUERY_TIMEOUT_SECONDS`, 120 s por defecto).
- Enmascaramiento opcional de columnas sensibles en grilla, vista previa y Excel (`APP_MASKED_COLUMNS`).
- Trazabilidad en los Excel exportados: autor, fecha y clasificación en las propiedades del
  documento, y pie de página «Confidencial – uso interno».
- Advertencia en el log si `APP_PASSWORD` tiene menos de 12 caracteres.
- Perfiles `dev` y `prod`.
- `HEALTHCHECK` en Docker, endpoints `/actuator/health/liveness` y `/readiness`, apagado ordenado.
- CI en GitHub Actions (pruebas, cobertura, lint, `npm audit`, build Docker), escaneo semanal OWASP
  Dependency-Check y Dependabot.
- Cobertura con JaCoCo y mínimo exigido de 80 % de líneas en `mvn verify`.
- 31 pruebas automatizadas nuevas (329 de backend en total).

### Cambiado
- Tiempo de inactividad de la sesión: de 8 h a 30 min (`SESSION_IDLE_TIMEOUT`).
- `trustServerCertificate=false` en la URL por defecto de la base de la aplicación (el perfil `dev`
  mantiene `true` para SQL Server local).
- Umbral de detección de fugas de conexión de 2 s a 60 s (evita falsas alarmas en exportaciones).
- Idioma del documento HTML a español (`lang="es"`) y título de la pestaña a «Multi-DB Workbook».
- Versión del proyecto de `0.0.1-SNAPSHOT` a `1.1.0`.

### Corregido
- 5 vulnerabilidades en dependencias de desarrollo del frontend (vite, vitest, undici,
  source-map-js) mediante `npm audit fix`, sin cambios de versión mayor.
- 22 advertencias de lint en el frontend (código sin uso).

## [1.0.0] - 2026-11-04

Primera versión documentada: explorador de tablas, constructor de reportes multi-tabla,
exportación a Excel, 6 motores de bases de datos, inicio de sesión seguro, Docker y capturas de
la interfaz.
