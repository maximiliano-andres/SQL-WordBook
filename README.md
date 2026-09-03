<div align="center">

# 📊 PushDbTemplate · SQL Server Workbook

### *Explorador inteligente, auditor DBA y generador de reportes multi-tabla con la magia de una hoja de cálculo.*

[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 4.1.1](https://img.shields.io/badge/Spring_Boot-4.1.1-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![React 19](https://img.shields.io/badge/React-19-61DAFB?style=for-the-badge&logo=react&logoColor=black)](https://react.dev/)
[![Vite 6](https://img.shields.io/badge/Vite-6-646CFF?style=for-the-badge&logo=vite&logoColor=white)](https://vitejs.dev/)
[![SQL Server](https://img.shields.io/badge/SQL_Server-Enterprise%20%2F%20Express-CC2927?style=for-the-badge&logo=microsoftsqlserver&logoColor=white)](https://www.microsoft.com/sql-server)
[![Docker](https://img.shields.io/badge/Docker-Multi--stage-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)
[![License: Proprietary](https://img.shields.io/badge/License-Proprietary-gray?style=for-the-badge)](pom.xml)

<br/>

> 💡 **¿Qué es PushDbTemplate?**  
> Una solución web full-stack de **solo lectura y auditoría segura** que transforma cualquier base de datos SQL Server en un **Excel interactivo y colaborativo**. Permite inspeccionar tablas, definir relaciones virtuales, cruzar múltiples entidades visualmente y generar exportaciones a Excel (`.xlsx`) y CSV de alto volumen sin riesgos de modificación de datos ni necesidad de otorgar accesos directos a SSMS o DBeaver.

---

</div>

## 🎮 Elige tu Modo de Exploración

<details open>
<summary><b>💼 Soy Usuario de Negocio / Analista de Datos</b> <i>(Haz clic para desplegar)</i></summary>
<br>

* 📄 **Navegación tipo Excel:** Cambia entre tablas usando las pestañas inferiores de hoja (`SheetTabs`).
* 🔍 **Filtros Dinámicos:** Aplica filtros instantáneos por columna (`CONTAINS`, `EQUALS`, `GREATER_THAN`, `BETWEEN`, `IS_NULL`, etc.).
* 🔗 **Nombres Claros (FK):** Olvídate de ver `id_cliente = 4528`. El sistema traduce automáticamente los IDs a nombres legibles como `"Acme Corp S.A."`.
* 🧩 **Cruzar Tablas sin Saber SQL:** Usa el **Constructor de Reportes** para unir clientes, ventas y productos con sugerencias inteligentes en 1 clic.
* 📥 **Exportar a Excel:** Descarga tus datos limpios y listos para trabajar con streaming optimizado.
* 🟢 **Modo Fácil:** Activa el botón de interfaz simplificada en el Ribbon superior para ocultar tecnicismos de base de datos.

</details>

<details>
<summary><b>💻 Soy Desarrollador Full-Stack</b> <i>(Haz clic para desplegar)</i></summary>
<br>

* 🚀 **Stack Moderno:** Java 21 + Spring Boot 4.1.1 en backend, React 19 + Vite 6 en frontend.
* 🏛️ **Arquitectura Limpia y Modular:** Lógica desacoplada en 5 servicios especializados (`SchemaMetadataService`, `ForeignKeyService`, `CustomReportService`, `ExcelExportService`, `DatabaseDiagnosticsService`).
* 🛡️ **Seguridad Nativa:** Whitelisting estricto contra el catálogo de metadatos, identificadores escapados con `[ ]` (T-SQL) y protección contra Formula Injection.
* ⚡ **Caché Caffeine:** Caché en memoria para metadatos de esquema sincronizada con cabeceras HTTP `Cache-Control`.
* 🧪 **Suite de Tests:** Pruebas unitarias e integradas con Spring MockMvc y JUnit 5 (`./mvnw test`).

</details>

<details>
<summary><b>🛡️ Soy DBA / Administrador de Infraestructura</b> <i>(Haz clic para desplegar)</i></summary>
<br>

* 🔒 **Superficie de Ataque Cero:** Sin operaciones de escritura (`DML/DDL`) sobre los datos del negocio. Conexión de solo lectura.
* 🎛️ **Consola DBA Embebida:** Visualiza en tiempo real versión del motor, tamaño de archivos de datos y logs, modelo de recuperación, colación y conexiones activas.
* 🚦 **Control de Recursos y Concurrencia:** Límite configurable de filas exportables (`EXPORT_MAX_ROWS`) y semáforo de descargas concurrentes (`EXPORT_MAX_CONCURRENT`) para proteger el pool de HikariCP.
* 📦 **Contenedorización Segura:** Dockerfile multi-stage con runtime JRE 21 ejecutando como usuario no privilegiado (`appuser`).
* 🩺 **Observabilidad:** Integración completa con Spring Boot Actuator (`/actuator/health` y `/actuator/metrics`).

</details>

---

## 📑 Tabla de Contenidos

1. [✨ Superpoderes y Características](#-superpoderes-y-características)
2. [🕹️ Vista Guiada de la Interfaz](#️-vista-guiada-de-la-interfaz)
3. [🏛️ Arquitectura del Sistema](#️-arquitectura-del-sistema)
4. [🛠️ Tech Stack](#️-tech-stack)
5. [🚀 Guía de Inicio Rápido](#-guía-de-inicio-rápido)
   - [Variables de Entorno (.env)](#variables-de-entorno-env)
   - [Desarrollo Local](#desarrollo-local)
   - [Compilación y Artefacto Único](#compilación-y-artefacto-único)
   - [Despliegue con Docker](#despliegue-con-docker)
6. [🧩 Constructor de Reportes Multi-Tabla (Custom Reports)](#-constructor-de-reportes-multi-tabla-custom-reports)
7. [🔗 Resolución y Gestión de Claves Foráneas (FK)](#-resolución-y-gestión-de-claves-foráneas-fk)
8. [🔌 Referencia Completa de la API REST](#-referencia-completa-de-la-api-rest)
9. [🛡️ Matriz de Seguridad y Rendimiento](#️-matriz-de-seguridad-y-rendimiento)
10. [🧪 Testing y Aseguramiento de Calidad](#-testing-y-aseguramiento-de-calidad)
11. [📂 Estructura del Código Fuente](#-estructura-del-código-fuente)
12. [❓ Preguntas Frecuentes y Solución de Problemas](#-preguntas-frecuentes-y-solución-de-problemas)

---

## ✨ Superpoderes y Características

```
 📊 SPREADSHEET VIEW              🧩 CUSTOM REPORT BUILDER           🛡️ DBA COCKPIT
┌─────────────────────────┐      ┌─────────────────────────┐       ┌─────────────────────────┐
│ • Paginación fluida     │      │ • Joins visuales        │       │ • Telemetría SQL Server │
│ • Formato inteligente   │  ➕  │ • Sugerencias FK 1-clic │   ➕   │ • Tamaño en disco       │
│ • FKs Virtuales en celda│      │ • Filtros y orden multin│       │ • Conexiones activas    │
│ • Export Excel/CSV      │      │ • Plantillas en BD      │       │ • Consola de diagnóstico│
└─────────────────────────┘      └─────────────────────────┘       └─────────────────────────┘
```

### 1. 📊 Explorador de Tablas Estilo Hoja de Cálculo
* **Navegación Paginada Eficiente:** Paginación server-side con `OFFSET / FETCH NEXT` (límite configurable de 10 a 100 registros por página).
* **Renderizado Inteligente de Tipos:** Alineación numérica automática a la derecha con estilos visuales, fechas centradas, booleanos formateados y valores `NULL` estilizados en cursiva suave.
* **Filtros Dinámicos Integrados:** Filtra cualquier columna al instante con operadores avanzados (`Contiene`, `Es Igual`, `Mayor que`, `Menor que`, `Entre`, `Es Nulo`, etc.).
* **Selector Dinámico de Proyecciones:** Elige exactamente qué columnas deseas visualizar o exportar para minimizar el tráfico de red.

### 2. 🔗 Relaciones Inteligentes (Foreign Keys Nativas y Virtuales)
* **Detección Automática:** Inspección del catálogo del motor vía `DatabaseMetaData.getImportedKeys()`.
* **FKs Virtuales / Personalizadas:** ¿Tu base de datos carece de constraints formales por diseño legado? Declara relaciones virtuales directamente haciendo doble clic en el encabezado de la columna.
* **Persistencia Robusta en BD:** Las relaciones personalizadas se guardan en la tabla `dbo.push_custom_fks` dentro del propio SQL Server, garantizando que todos los usuarios compartan la misma configuración sin depender de archivos locales.
* **Resolución en Lote (Zero N+1):** Una única consulta optimizada `WHERE pk IN (...)` por cada columna FK visible, calculada únicamente sobre los valores únicos de la página en pantalla.
* **3 Modos de Visualización:**
  * `ID`: Muestra el código numérico original (ej. `104`).
  * `Valor Real`: Muestra únicamente la descripción referenciada (ej. `Logística Central`).
  * `Ambos`: Muestra formato compuesto (ej. `104 - Logística Central`).

### 3. 🧩 Constructor Visual de Reportes Multi-Tabla (Joins)
* **Cruces Visuales Intuitivos:** Soporte completo para `LEFT JOIN`, `INNER JOIN`, `RIGHT JOIN` y `FULL JOIN`.
* **Sugerencias Inteligentes de Cruces:** El sistema analiza las FKs físicas y virtuales existentes y te ofrece un botón para agregar cruces automáticos en un solo clic.
* **Renombrado y Selección de Columnas:** Combina campos de múltiples tablas y asígnales nombres descriptivos (*aliases*) para el reporte final.
* **Filtros Multinivel y Ordenamiento:** Combina condiciones lógicas (`AND`/`OR`) con operadores relacionales y orden multi-columna (`ASC`/`DESC`).
* **Visor SQL en Vivo:** Observa y audita la consulta T-SQL generada en tiempo real antes de ejecutarla.
* **Sistema de Plantillas Persistidas:** Guarda reportes frecuentes con nombre y descripción en la tabla `dbo.push_custom_reports` para reejecutarlos cuando quieras.

### 4. 📥 Motor de Exportación de Alto Rendimiento
* **Streaming Apache POI SXSSF:** Generación de archivos Excel (`.xlsx`) mediante ventana deslizante en memoria y volcado a disco temporal, evitando saturación de memoria RAM (`OutOfMemoryError`).
* **Exportación CSV Instantánea:** Generación ultrarrápida del lado del cliente para la vista actual.
* **Blindaje Anti-Inyección de Fórmulas:** Neutralización automática de caracteres maliciosos (`=`, `+`, `-`, `@`, `\t`, `\r`) para proteger a los usuarios de Microsoft Excel.

### 5. 🌓 Experiencia Dual y Diagnóstico DBA
* **Modo Fácil vs Modo DBA:** Switch accesible en la barra superior para alternar entre terminología amigable y métricas técnicas.
* **Consola de Diagnóstico:** Consulta estado del servidor, base de datos, colación, conexiones y espacio ocupado por cada archivo de datos (`.mdf`) y log (`.ldf`).
* **Guía Interactiva Integrada:** Manual de usuario disponible desde el propio Ribbon con atajos de teclado y consejos de uso.

---

## 🕹️ Vista Guiada de la Interfaz

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│ 🟢 SQL Server Workbook   [Hojas de Datos] [Cruzar y Armar]   [🟢 Modo Fácil | 🛠️ Modo DBA] [?] │  <- Ribbon Superior
├─────────────────────────────────────────────────────────────────────────────────────────────┤
│  Pestaña Inicio: [Filas: 15 ▼]  [◀ Pág 1 de 24 ▶]  [Ver FK: Ambos ▼]  [🔍 Filtro Rápido]   │  <- Barra de Herramientas
├─────────────────────────────────────────────────────────────────────────────────────────────┤
│ fx | Tabla activa: dbo.Ventas (1,450 registros)                                            │  <- Barra de Fórmulas
├────┬──────────────┬────────────────────────┬───────────────────────────┬────────────────────┤
│ #  │ id_venta     │ id_cliente (FK) 🔗     │ id_producto (FK) 🔗       │ monto_total        │  <- Encabezados
├────┼──────────────┼────────────────────────┼───────────────────────────┼────────────────────┤
│ 1  │ 1001         │ 12 - Acme Sur S.A.     │ 501 - Licencia Enterprise │ $ 1,250.00         │  <- Grilla de Datos
│ 2  │ 1002         │ 15 - Banco Global      │ 504 - Soporte Gold 24/7   │ $ 4,800.00         │     (Excel Theme)
│ 3  │ 1003         │ 12 - Acme Sur S.A.     │ 502 - Módulo Adicional    │ $   350.00         │
└────┴──────────────┴────────────────────────┴───────────────────────────┴────────────────────┘
│ 📄 Clientes │ 📄 Ventas ★ │ 📄 Productos │ 📄 Proveedores │ 📄 Sucursales │ ◀ ▶ [Buscar Hoja] │  <- SheetTabs
└─────────────────────────────────────────────────────────────────────────────────────────────┘
│ Total: 1,450 filas | Mostrando 1-15 | Tiempo respuesta: 18ms | Conexión: ONLINE 🟢          │  <- StatusBar
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 🏛️ Arquitectura del Sistema

El sistema implementa una **Arquitectura en Capas Limpia (Clean Architecture)** orientada a la seguridad de solo lectura:

```mermaid
flowchart TD
    subgraph Frontend["🖥️ FRONTEND (React 19 + Vite 6)"]
        UI["Ribbon · Spreadsheet · SheetTabs · StatusBar"]
        CR["CustomReports (Visual Join Builder & Templates)"]
        MODALS["DbaConsoleModal · UserGuideModal"]
    end

    subgraph Security["🛡️ SEGURIDAD PERIMETRAL"]
        SEC["Spring Security (HTTP Basic Auth obligatoria)"]
        CSP["Content-Security-Policy & Headers Seguros"]
        REDIRECT["IndexRedirectFilter (/index.html -> /)"]
    end

    subgraph ControllerLayer["🎮 CONTROLLER REST"]
        CTRL["DatabaseController (/api/db/**)"]
    end

    subgraph ServiceLayer["⚙️ SERVICIOS MODULARES ESPECIALIZADOS"]
        SMS["SchemaMetadataService<br/><i>(Catálogo, conteo y paginación)</i>"]
        FKS["ForeignKeyService<br/><i>(Detección, resolución FK y Joins)</i>"]
        CRS["CustomReportService<br/><i>(Motor T-SQL multi-tabla y plantillas)</i>"]
        EES["ExcelExportService<br/><i>(Streaming SXSSF & Semáforo Concurrencia)</i>"]
        DDS["DatabaseDiagnosticsService<br/><i>(Telemetría y Métricas DBA)</i>"]
        SAFE["SqlSafe<br/><i>(Validación & Escape T-SQL Whitelist)</i>"]
    end

    subgraph CacheAndPool["⚡ CACHÉ & CONEXIONES"]
        CAFFEINE[("Caffeine Cache<br/>TTL: 60s")]
        HIKARI[("HikariCP Connection Pool")]
    end

    subgraph Database["🗄️ SQL SERVER"]
        SYS_TABLES[("Tablas de Negocio (Solo Lectura)")]
        CFK_TABLE[("dbo.push_custom_fks (FKs Virtuales)")]
        CREP_TABLE[("dbo.push_custom_reports (Plantillas)")]
    end

    Frontend -->|"Peticiones HTTP/JSON + Auth"| SEC
    SEC --> REDIRECT --> CSP --> CTRL
    
    CTRL --> SMS & FKS & CRS & EES & DDS
    SMS & FKS & CRS & EES & DDS --> SAFE
    SMS & FKS <--> CAFFEINE
    SMS & FKS & CRS & EES & DDS --> HIKARI
    
    HIKARI --> SYS_TABLES
    HIKARI --> CFK_TABLE
    HIKARI --> CREP_TABLE
```

---

## 🛠️ Tech Stack

| Área | Componente | Versión / Detalle | Propósito |
|---|---|---|---|
| **Backend** | Java | `OpenJDK 21 (LTS)` | Runtime de alto rendimiento |
| | Spring Boot | `4.1.1` | Web MVC, Security, JDBC, Cache, Actuator |
| | Persistencia | `JdbcTemplate` + `mssql-jdbc` | Consultas dinámicas optimizadas sin sobrecarga ORM |
| | Pool de Conexiones | `HikariCP` | Pool de baja latencia con Prepared Statements cacheados |
| | Motor de Caché | `Caffeine` | Caché en memoria para metadatos poco cambiantes |
| | Exportador Excel | `Apache POI 5.3.0` | `SXSSFWorkbook` (Streaming con ventana de memoria) |
| **Frontend** | React | `19.x` | SPA declarativo con Hooks modernos |
| | Bundler / DevServer| `Vite 6.x` | Compilación y recarga ultra rápida (HMR) |
| | Iconografía | `lucide-react` | Conjunto completo de íconos vectoriales modernos |
| | Estilos | `Vanilla CSS Modular` | Tema inspirado fielmente en Microsoft Excel |
| **Infraestructura**| Docker | Multi-Stage | Node 22 Build ➔ JDK 21 Build ➔ JRE 21 Slim Runtime |
| | Base de Datos | `Microsoft SQL Server` | 2014, 2016, 2017, 2019, 2022 y Azure SQL |

---

## 🚀 Guía de Inicio Rápido

### Variables de Entorno (.env)

Crea tu archivo `.env` en la raíz del proyecto clonando el archivo de ejemplo:

```bash
cp .env.example .env
```

Configura tus variables según el entorno:

```ini
# ==============================================================================
# CONEXIÓN A BASE DE DATOS SQL SERVER
# ==============================================================================
DB_URL=jdbc:sqlserver://localhost:1433;databaseName=MiBaseDatos;encrypt=false;trustServerCertificate=true
DB_USERNAME=sa
DB_PASSWORD=TuPasswordSeguro123!
DB_POOL_MAX_SIZE=10
DB_POOL_MIN_IDLE=2

# ==============================================================================
# SEGURIDAD Y AUTENTICACIÓN WEB (OBLIGATORIO: Sin esto la app no arranca)
# ==============================================================================
APP_USER=admin
APP_PASSWORD=PasswordSuperSeguro2026!

# ==============================================================================
# POLÍTICAS DE EXPORTACIÓN Y RENDIMIENTO
# ==============================================================================
EXPORT_MAX_ROWS=50000
EXPORT_MAX_CONCURRENT=2
```

> [!IMPORTANT]
> **Arranque Seguro:** Si `APP_USER` o `APP_PASSWORD` se encuentran vacíos o no declarados, la aplicación **fallará intencionalmente al iniciar** para evitar exponer la información sin autenticación.

---

### Desarrollo Local

Para desarrollo activo con hot-reload en frontend y backend:

<details open>
<summary><b>Paso 1: Iniciar Backend (Spring Boot en puerto 8080)</b></summary>

```bash
# En la raíz del proyecto
./mvnw spring-boot:run
```

</details>

<details open>
<summary><b>Paso 2: Iniciar Frontend (Vite en puerto 5173 con proxy)</b></summary>

```bash
cd frontend
npm install
npm run dev
```

</details>

Abre tu navegador en **`http://localhost:5173`** e ingresa las credenciales de `APP_USER` y `APP_PASSWORD`.

---

### Compilación y Artefacto Único

Puedes empaquetar todo el frontend compilado dentro del `.jar` de Spring Boot para ejecutarlo en un solo puerto (`:8080`):

```bash
# 1. Compilar el frontend
cd frontend
npm ci
npm run build
cd ..

# 2. Copiar los estáticos a los recursos de Spring
mkdir -p src/main/resources/static
cp -r frontend/dist/* src/main/resources/static/

# 3. Empaquetar el JAR ejecutable
./mvnw clean package -DskipTests

# 4. Ejecutar el servidor standalone
java -jar target/PushDbTemplate-0.0.1-SNAPSHOT.jar
```

Accede directamente a **`http://localhost:8080`**.

---

### Despliegue con Docker

El proyecto incluye un [`Dockerfile`](Dockerfile) multi-stage optimizado que no requiere tener Java ni Node instalados en el host:

```bash
# 1. Construir la imagen Docker
docker build -t pushdbtemplate:latest .

# 2. Ejecutar el contenedor pasando el archivo de variables de entorno
docker run -d \
  --name pushdbtemplate_app \
  -p 8080:8080 \
  --env-file .env \
  --restart unless-stopped \
  pushdbtemplate:latest
```

Verifica la salud del contenedor:
```bash
curl -i http://localhost:8080/actuator/health
```

---

## 🧩 Constructor de Reportes Multi-Tabla (Custom Reports)

El generador de reportes permite realizar cruces avanzados entre tablas sin escribir una sola línea de SQL:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. SELECCIONAR TABLA BASE ➔ 2. AGREGAR CRUCES (JOINS) ➔ 3. FILTROS & ORDEN  │
├─────────────────────────────────────────────────────────────────────────────┤
│ Tabla Principal: [dbo.Facturas ▼]                                           │
│ Cruces Activos:                                                             │
│  ├─ [LEFT JOIN] dbo.Clientes ON Facturas.id_cliente = Clientes.id_cliente    │
│  └─ [LEFT JOIN] dbo.Vendedores ON Facturas.id_vendedor = Vendedores.id_vend │
│                                                                             │
│ 💡 Sugerencias detectadas:                                                  │
│  [➕ Unir con dbo.DetalleFacturas (vía id_factura)]                          │
├─────────────────────────────────────────────────────────────────────────────┤
│ Columnas del Reporte:                                                       │
│  ☑ Facturas.folio AS [Folio Factura]                                        │
│  ☑ Clientes.razon_social AS [Nombre Cliente]                                │
│  ☑ Vendedores.nombre AS [Ejecutivo Asignado]                                │
│  ☑ Facturas.total AS [Monto Neto]                                           │
├─────────────────────────────────────────────────────────────────────────────┤
│ [▶ Ejecutar Vista Previa]  [📥 Descargar Excel (.xlsx)]  [💾 Guardar Plantilla] │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Características Principales:
1. **Tipos de Cruces Soportados:** `LEFT JOIN` (por defecto, preserva datos principales), `INNER JOIN` (coincidencias estrictas), `RIGHT JOIN` y `FULL JOIN`.
2. **Sugerencias Basadas en FKs:** Detección en tiempo real de relaciones existentes que conectan con la tabla base seleccionada.
3. **Selector y Aliases de Columnas:** Selección precisa de campos evitando colisiones de nombres (`id_cliente` de tabla A vs `id_cliente` de tabla B).
4. **Filtros Multi-Criterio:** Condiciones con operadores `>`, `<`, `=`, `LIKE`, `BETWEEN`, `IN`, `IS NULL`, etc.
5. **Auditoría SQL en Vivo:** Código T-SQL generado listo para inspección visual.
6. **Plantillas en Base de Datos:** Guardado centralizado en `dbo.push_custom_reports`.

---

## 🔗 Resolución y Gestión de Claves Foráneas (FK)

### 1. ¿Cómo funciona la resolución?
Cuando visualizas una tabla con una FK referenciada (por ejemplo `id_categoria = 3`), el backend no ejecuta consultas fila por fila (evitando el problema de rendimiento N+1). En su lugar:
1. Extrae los valores únicos de la columna presentes en la página actual (ej. `[1, 3, 7]`).
2. Ejecuta una sola consulta: `SELECT id_categoria, nombre_categoria FROM dbo.Categorias WHERE id_categoria IN (1, 3, 7)`.
3. Devuelve un mapa de resolución que el frontend asocia en tiempo récord.

### 2. Creación de FKs Virtuales desde la Interfaz:
1. Haz **doble clic en el encabezado** de cualquier columna numérica o identificador.
2. Selecciona la tabla de destino (`referencedTable`) y su clave primaria (`referencedColumn`).
3. Elige qué columna descriptiva deseas mostrar (`displayColumn`, ej. `razon_social`).
4. (Opcional) Aplica un filtro adicional a la relación.
5. Haz clic en **"Guardar Relación"**. La configuración se persistirá automáticamente en `dbo.push_custom_fks`.

---

## 🔌 Referencia Completa de la API REST

Todos los endpoints bajo `/api/db/**` requieren cabecera de autenticación **HTTP Basic Auth** (`Authorization: Basic base64(user:pass)`).

<details>
<summary><b>1. Diagnóstico y Metadatos de Servidor</b> <code>GET /api/db/info</code></summary>

```http
GET /api/db/info HTTP/1.1
Authorization: Basic YWRtaW46UGFzc3dvcmQ=
```

**Respuesta Exitosa (200 OK):**
```json
{
  "databaseProduct": "Microsoft SQL Server",
  "databaseVersion": "15.00.4236.7",
  "jdbcUrl": "jdbc:sqlserver://localhost:1433;databaseName=PROD;user=***;password=***",
  "totalTables": 84,
  "totalViews": 12,
  "activeConnections": 8,
  "dbState": "ONLINE",
  "dbRecoveryModel": "SIMPLE",
  "dbCollation": "SQL_Latin1_General_CP1_CI_AS",
  "totalSizeMb": 2048,
  "dbFiles": [
    { "name": "PROD_Data", "type": "ROWS", "sizeMb": 1536 },
    { "name": "PROD_Log", "type": "LOG", "sizeMb": 512 }
  ],
  "customFksCount": 5
}
```
</details>

<details>
<summary><b>2. Listado de Tablas</b> <code>GET /api/db/tables</code></summary>

```http
GET /api/db/tables HTTP/1.1
```

**Respuesta (200 OK):**
```json
[
  { "schema": "dbo", "name": "Clientes" },
  { "schema": "dbo", "name": "Facturas" },
  { "schema": "dbo", "name": "Productos" }
]
```
</details>

<details>
<summary><b>3. Esquema de Columnas</b> <code>GET /api/db/tables/{schema}/{name}/columns</code></summary>

```http
GET /api/db/tables/dbo/Clientes/columns HTTP/1.1
```

**Respuesta (200 OK):**
```json
[
  { "name": "id_cliente", "type": "int", "size": 10, "nullable": false },
  { "name": "razon_social", "type": "varchar", "size": 150, "nullable": false },
  { "name": "email", "type": "varchar", "size": 100, "nullable": true }
]
```
</details>

<details>
<summary><b>4. Datos Paginados con Resolución FK</b> <code>GET /api/db/tables/{schema}/{name}/data</code></summary>

**Parámetros Query:**
* `limit` (int, default: 15, max: 100)
* `offset` (int, default: 0)
* `columns` (List<String>, opcional)
* `filterColumn`, `filterOperator`, `filterValue`, `filterValue2` (opcionales)

```http
GET /api/db/tables/dbo/Facturas/data?limit=15&offset=0 HTTP/1.1
```

**Respuesta (200 OK):**
```json
{
  "data": [
    { "id_factura": 101, "id_cliente": 12, "monto": 1500.0 }
  ],
  "totalRows": 1450,
  "limit": 15,
  "offset": 0,
  "currentPage": 1,
  "totalPages": 97,
  "fkColumns": [
    {
      "column": "id_cliente",
      "referencedSchema": "dbo",
      "referencedTable": "Clientes",
      "referencedColumn": "id_cliente",
      "displayColumn": "razon_social",
      "enabled": true,
      "custom": false
    }
  ],
  "fkResolutions": {
    "id_cliente": [
      { "status": "RESOLVED", "value": "Acme Sur S.A." }
    ]
  }
}
```
</details>

<details>
<summary><b>5. Sugerencias de Cruces (Joins)</b> <code>GET /api/db/custom-reports/suggest-joins</code></summary>

```http
GET /api/db/custom-reports/suggest-joins?schema=dbo&table=Facturas HTTP/1.1
```

**Respuesta (200 OK):**
```json
[
  {
    "sourceColumn": "id_cliente",
    "targetSchema": "dbo",
    "targetTable": "Clientes",
    "targetColumn": "id_cliente",
    "suggestedJoinType": "LEFT JOIN",
    "relationshipName": "FK_Facturas_Clientes",
    "isVirtual": false
  }
]
```
</details>

<details>
<summary><b>6. Vista Previa de Reporte Personalizado</b> <code>POST /api/db/custom-reports/preview</code></summary>

**Payload Body:**
```json
{
  "baseTable": { "schema": "dbo", "name": "Facturas", "alias": "f" },
  "joins": [
    {
      "type": "LEFT",
      "targetTable": { "schema": "dbo", "name": "Clientes", "alias": "c" },
      "onLeftColumn": "f.id_cliente",
      "onRightColumn": "c.id_cliente"
    }
  ],
  "columns": [
    { "tableAlias": "f", "columnName": "id_factura", "customLabel": "Folio" },
    { "tableAlias": "c", "columnName": "razon_social", "customLabel": "Cliente" }
  ],
  "limit": 15,
  "offset": 0
}
```
</details>

<details>
<summary><b>7. Plantillas de Reportes</b> <code>GET | POST | DELETE /api/db/custom-reports/templates</code></summary>

* `GET /api/db/custom-reports/templates` ➔ Listar plantillas guardadas.
* `POST /api/db/custom-reports/templates` ➔ Guardar/actualizar plantilla.
* `DELETE /api/db/custom-reports/templates/{id}` ➔ Eliminar plantilla por ID.
</details>

---

## 🛡️ Matriz de Seguridad y Rendimiento

| Vector / Control | Implementación en PushDbTemplate |
|---|---|
| **Inyección SQL (SQLi)** | **Whitelisting Riguroso**: Tablas y columnas se verifican contra los metadatos del motor antes de armar consultas. Identificadores delimitados con corchetes `[ ]`. |
| **Formula & CSV Injection** | Valores que inician con `=`, `+`, `-`, `@`, tabulaciones o saltos de línea se prefijan con `'` tanto en el exportador Excel como en el CSV. |
| **Autenticación Obligatoria** | Filtro `SecurityConfig` HTTP Basic. Si faltan credenciales en variables de entorno, la app no arranca. |
| **Agotamiento de Memoria RAM** | Exportaciones a Excel usan `SXSSFWorkbook` (Apache POI) con ventana en disco temporal y límite de filas (`EXPORT_MAX_ROWS`). |
| **Saturación del Pool JDBC** | Semáforo de concurrencia (`EXPORT_MAX_CONCURRENT`) que devuelve `429 Too Many Requests` ante exceso de exportaciones paralelas. |
| **Fuga de Información en Errores** | Las excepciones internas devuelven un `correlationId` aleatorio (UUID); nunca se expone el stacktrace SQL ni datos internos al cliente. |
| **Contenedor Seguro** | La imagen Docker ejecuta sobre un usuario no-root (`appuser`) de mínimos privilegios. |
| **Políticas de Navegador** | `Content-Security-Policy: default-src 'self' ...` y API completamente *stateless* (sin cookies vulnerables a CSRF). |

---

## 🧪 Testing y Aseguramiento de Calidad

El proyecto cuenta con cobertura de pruebas unitarias y de integración para asegurar que cada servicio funcione de forma confiable:

```bash
# Ejecutar todas las pruebas del backend
./mvnw test

# Ejecutar el linter del frontend
cd frontend
npm run lint
```

### Resumen de Suites de Prueba:

| Clase de Prueba | Capa Evaluada | Aspectos Clave Validados |
|---|---|---|
| `DatabaseControllerTest` | Controller REST | Códigos HTTP (200, 400, 403, 429, 500), serialización JSON y manejo de correlationId. |
| `SchemaMetadataServiceTest` | Metadatos y Datos | Whitelisting de identificadores, paginación T-SQL y filtrado dinámico. |
| `ForeignKeyServiceTest` | Relaciones FK | Detección de FKs nativas, resolución en lote sin N+1 y sugerencias de cruces. |
| `CustomReportServiceTest` | Reportes Dinámicos | Generación segura de sentencias T-SQL multi-tabla y CRUD de plantillas en BD. |
| `PushDbTemplateApplicationTests` | Contexto Spring | Carga exitosa del ApplicationContext y configuración de seguridad. |

---

## 📂 Estructura del Código Fuente

```
PushDbTemplate/
├── src/main/java/com/LectorDBTemplate/PushDbTemplate/
│   ├── PushDbTemplateApplication.java        # Punto de entrada principal
│   ├── config/
│   │   ├── SecurityConfig.java               # Configuración de Spring Security & CSP
│   │   ├── DatabaseConfig.java               # Hook de inicialización de tablas del sistema
│   │   ├── CacheConfig.java                  # Configuración de Caffeine Cache
│   │   └── IndexRedirectFilter.java          # Normalización de rutas SPA
│   ├── controller/
│   │   └── DatabaseController.java           # Endpoints REST y ExceptionHandler central
│   └── service/
│       ├── SchemaMetadataService.java        # Tablas, columnas, conteos y paginación
│       ├── ForeignKeyService.java            # Detección y resolución de FKs físicas/virtuales
│       ├── CustomReportService.java          # Motor de consultas multi-tabla y plantillas
│       ├── ExcelExportService.java           # Streaming SXSSF y control de concurrencia
│       ├── DatabaseDiagnosticsService.java   # Telemetría de servidor y archivos de datos
│       └── SqlSafe.java                      # Utilitario de sanitización y escape T-SQL
├── src/main/resources/
│   ├── application.yaml                      # Configuración central de Spring Boot
│   └── static/                               # Build de producción del frontend SPA
├── src/test/java/...                         # Suite de tests unitarios y de integración
├── frontend/
│   ├── src/
│   │   ├── App.jsx                           # Orquestador del estado global
│   │   ├── index.css                         # Sistema de diseño inspirado en Excel
│   │   ├── components/
│   │   │   ├── Ribbon.jsx                    # Barra de herramientas superior y modos UX
│   │   │   ├── Spreadsheet.jsx               # Grilla interactiva tipo Excel
│   │   │   ├── SheetTabs.jsx                 # Pestañas inferiores de hojas de cálculo
│   │   │   ├── StatusBar.jsx                 # Barra de estado inferior y telemetría
│   │   │   ├── DbaConsoleModal.jsx           # Consola de diagnóstico para administradores
│   │   │   ├── UserGuideModal.jsx            # Manual interactivo de usuario
│   │   │   ├── CustomReports.jsx             # Vista principal del constructor de reportes
│   │   │   └── custom-reports/               # Paneles de Joins, Columnas, Filtros y Modales
│   │   └── utils/fk.js                       # Utilidades de formateo y mapeo FK
│   ├── package.json                          # Dependencias de React 19 y Vite 6
│   └── vite.config.js                        # Configuración del proxy para desarrollo
├── Dockerfile                                # Build multi-stage para producción
├── .env.example                              # Plantilla documentada de variables de entorno
└── pom.xml                                   # Dependencias Maven y configuración del build
```

---

## ❓ Preguntas Frecuentes y Solución de Problemas

<details>
<summary><b>1. ¿La aplicación puede modificar o borrar datos de mi SQL Server?</b></summary>
<br>
<b>No.</b> Todas las consultas sobre las tablas de negocio se ejecutan exclusivamente mediante sentencias <code>SELECT</code> generadas internamente por la aplicación. Las únicas tablas sobre las que la aplicación realiza operaciones de inserción o actualización son sus propias tablas de configuración interna (<code>dbo.push_custom_fks</code> y <code>dbo.push_custom_reports</code>).
</details>

<details>
<summary><b>2. La aplicación no arranca y lanza un error de seguridad al inicio</b></summary>
<br>
Asegúrate de haber creado tu archivo <code>.env</code> en la raíz del proyecto y que las variables <code>APP_USER</code> y <code>APP_PASSWORD</code> no estén vacías. Por diseño, la aplicación previene iniciar si no existe un usuario definido para proteger la API.
</details>

<details>
<summary><b>3. ¿Cómo cambio el nombre que se muestra para una clave foránea (FK)?</b></summary>
<br>
En la grilla de datos, haz doble clic sobre el encabezado de la columna FK. Se abrirá el modal de configuración de la relación donde podrás elegir cualquier columna de texto de la tabla destino en el campo <b>Columna a Mostrar</b>.
</details>

<details>
<summary><b>4. ¿Por qué una exportación a Excel devuelve código HTTP 429?</b></summary>
<br>
Para evitar saturar los recursos de tu servidor SQL Server, el sistema limita el número de descargas simultáneas pesadas mediante <code>EXPORT_MAX_CONCURRENT</code> (por defecto: 2). Si varios usuarios descargan reportes al mismo tiempo, el sistema les pedirá esperar unos segundos antes de reintentar.
</details>

---

<div align="center">

**Hecho con pasión para optimizar el trabajo de analistas, consultores y administradores de bases de datos.**  
*PushDbTemplate — Tu base de datos SQL Server, ahora tan fácil y potente como Excel.*

</div>
