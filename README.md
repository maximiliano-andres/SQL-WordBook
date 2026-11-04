<div align="center">

<img src="frontend/public/logo.png" alt="Logo Multi-DB Workbook" width="112" />

# Multi-DB Workbook

### Consulta, cruza y exporta datos empresariales sin escribir SQL

Una plataforma web que convierte una base de datos corporativa en una **hoja de cálculo segura y fácil de usar**,
pensada para equipos de **Recursos Humanos, Finanzas y Análisis** que necesitan información confiable sin depender de TI.

[![Java 21](https://img.shields.io/badge/Java-21_LTS-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![React 19](https://img.shields.io/badge/React-19-149ECA?style=for-the-badge&logo=react&logoColor=white)](https://react.dev/)
[![Vite 6](https://img.shields.io/badge/Vite-6-646CFF?style=for-the-badge&logo=vite&logoColor=white)](https://vitejs.dev/)
[![Docker](https://img.shields.io/badge/Docker-Multi--stage-2496ED?style=for-the-badge&logo=docker&logoColor=white)](Dockerfile)

[![SQL Server](https://img.shields.io/badge/SQL_Server-CC2927?style=flat-square&logo=microsoftsqlserver&logoColor=white)](#-requisitos-previos)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=flat-square&logo=postgresql&logoColor=white)](#-requisitos-previos)
[![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=flat-square&logo=mysql&logoColor=white)](#-requisitos-previos)
[![MariaDB](https://img.shields.io/badge/MariaDB-003545?style=flat-square&logo=mariadb&logoColor=white)](#-requisitos-previos)
[![Oracle](https://img.shields.io/badge/Oracle-F80000?style=flat-square&logo=oracle&logoColor=white)](#-requisitos-previos)
[![SQLite](https://img.shields.io/badge/SQLite-003B57?style=flat-square&logo=sqlite&logoColor=white)](#-requisitos-previos)
[![Tests](https://img.shields.io/badge/tests-258_casos-2EA44F?style=flat-square)](#-calidad-y-pruebas)
[![Licencia](https://img.shields.io/badge/licencia-propietaria-555?style=flat-square)](pom.xml)

[**Resumen**](#-el-proyecto-en-30-segundos) ·
[**Capturas**](#-capturas) ·
[**Funcionalidades**](#-funcionalidades) ·
[**Tecnología**](#-tecnología) ·
[**Mapas del sistema**](#-mapas-del-sistema) ·
[**Requisitos**](#-requisitos-previos) ·
[**Manual de uso**](#-manual-de-uso)

</div>

---

## ⚡ El proyecto en 30 segundos

| | |
|---|---|
| **El problema** | En RRHH y Finanzas la información vive en bases de datos (nóminas, cargos, centros de costo, liquidaciones). Para obtener un reporte hay que pedírselo a TI o aprender SQL, y los datos llegan llenos de códigos (`id_cargo = 118`) en vez de nombres. |
| **La solución** | Una aplicación web con apariencia de hoja de cálculo para **explorar tablas, traducir códigos a nombres reales, cruzar información de varias tablas y descargar el resultado en Excel**, todo con clics. |
| **Para quién** | Analistas de RRHH, Finanzas y Control de Gestión (uso diario) y administradores de bases de datos (modo técnico con diagnóstico del motor). |
| **Seguridad** | Consulta en **modo solo lectura** sobre los datos de negocio, acceso protegido por usuario y contraseña, y protección contra inyección SQL y fórmulas maliciosas en Excel. |
| **Alcance** | Aplicación full-stack completa: backend Java/Spring, frontend React, 6 motores de bases de datos, Docker y 258 casos de prueba automatizados. |

> [!IMPORTANT]
> **Herramienta de uso individual.** Está diseñada para que **una persona** la ejecute en su equipo o servidor y trabaje con **una conexión activa a la vez**.
> Tiene un único usuario de acceso, definido en la configuración, y si se cambia de base de datos el cambio aplica a toda la aplicación.
> No está pensada como plataforma multiusuario con cuentas y permisos distintos por persona (ver [Limitaciones](#-limitaciones-conocidas-y-próximos-pasos)).

---

## 🖼️ Capturas

> Imágenes con datos de demostración ficticios.

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/01-login.png" alt="Pantalla de acceso" /><p align="center"><b>Acceso seguro</b>: alertas claras y guía de ingreso</p></td>
    <td width="50%"><img src="docs/screenshots/02-explorador.png" alt="Explorador de datos" /><p align="center"><b>Explorador</b>: tablas como hojas, códigos traducidos a nombres</p></td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/03-constructor-reportes.png" alt="Constructor de reportes" /><p align="center"><b>Constructor de reportes</b>: cruza tablas en 3 pasos</p></td>
    <td width="50%"><img src="docs/screenshots/04-resultados.png" alt="Resultados del reporte" /><p align="center"><b>Resultados</b>: vista previa y descarga a Excel</p></td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/06-relaciones.png" alt="Configuración de relaciones" /><p align="center"><b>Relaciones</b>: vincula códigos con su descripción</p></td>
    <td width="50%" align="center"><img src="docs/screenshots/05-movil.png" alt="Vista móvil" width="230" /><p align="center"><b>Responsive</b>: de móviles a monitores 4K</p></td>
  </tr>
</table>

---

## ✨ Funcionalidades

### Para el usuario de negocio (RRHH · Finanzas)

| Funcionalidad | Qué permite hacer |
|---|---|
| 📄 **Tablas como hojas de cálculo** | Cada tabla aparece como una pestaña, como en Excel. Se recorre página a página (10 a 100 filas) y se busca por nombre. |
| 🔍 **Filtros sin SQL** | Filtra cualquier columna con condiciones simples: *contiene, igual a, entre, mayor que, menor que, vacío, no vacío*. |
| 🔗 **Códigos convertidos en nombres** | Muestra `118 · Analista Contable` en lugar de solo `118`. Se puede elegir ver el código, el nombre o ambos. |
| 🧩 **Cruzar información de varias tablas** | Combina, por ejemplo, *Empleados + Cargos + Centros de Costo* en un solo reporte. El sistema **sugiere automáticamente** los cruces posibles. |
| ✏️ **Columnas a medida** | Elige qué columnas incluir, en qué orden y con qué encabezado aparecerán en el Excel final. |
| 🧹 **Eliminar duplicados** | Quita filas repetidas del reporte con un clic. |
| 📥 **Descarga a Excel y CSV** | Genera archivos `.xlsx` del reporte completo (hasta 50.000 filas por defecto) o un CSV rápido de la vista actual. |
| 💾 **Plantillas reutilizables** | Guarda un reporte (por ejemplo, *"Nómina mensual por centro de costo"*) y vuelve a ejecutarlo cuando lo necesites. |
| 🟢 **Modo Fácil** | Lenguaje simple, sin tecnicismos de bases de datos. Es el modo por defecto. |
| 📖 **Guía integrada** | Manual interactivo disponible en todo momento desde el botón **Guía**. |

### Para el perfil técnico (DBA · TI)

| Funcionalidad | Detalle |
|---|---|
| 🛠️ **Modo DBA** | Muestra esquemas, nombres técnicos, tiempos de consulta y el **SQL generado** para auditarlo. |
| 🔌 **Gestor de conexiones** | Conecta en caliente a SQL Server, PostgreSQL, MySQL, MariaDB, Oracle, SQLite o una URL JDBC directa, con **prueba de conexión y latencia** antes de aplicar. |
| 🩺 **Consola de diagnóstico** | Versión del motor, estado, modelo de recuperación, colación, conexiones activas y tamaño de los archivos de datos y log. |
| 🗂️ **Relaciones virtuales** | Declara relaciones entre tablas aunque la base de datos no tenga *foreign keys* formales (algo común en sistemas heredados). |

---

## 🧰 Tecnología

### Stack

| Capa | Tecnología | Uso en el proyecto |
|---|---|---|
| **Backend** | Java 21 (LTS) · Spring Boot 4.1 | API REST, seguridad, caché, monitoreo (Actuator) |
| | Spring Security | Login HTTP Basic + sesión de servidor (cookie `HttpOnly`, `SameSite=Lax`), límite de intentos |
| | Spring JDBC + HikariCP | Consultas dinámicas parametrizadas y pool de conexiones intercambiable en caliente |
| | Flyway | Migraciones versionadas del esquema propio de la aplicación |
| | Caffeine | Caché en memoria de metadatos (tablas, columnas, relaciones) |
| | Apache POI 5.3 (SXSSF) | Exportación a Excel en *streaming*, sin agotar la memoria |
| | Drivers JDBC | `mssql-jdbc`, `postgresql`, `mysql-connector-j`, `mariadb-java-client`, `ojdbc11`, `sqlite-jdbc` |
| **Frontend** | React 19 · Vite 6 | SPA con carga diferida (*lazy loading*) del módulo de reportes |
| | CSS moderno nativo | Sistema de diseño propio: tokens, Grid, Flexbox, `clamp()`, *container-friendly*, sin frameworks CSS |
| | lucide-react | Iconografía |
| **Calidad** | JUnit 5 · Spring MockMvc · Vitest · Testing Library · oxlint | 218 pruebas de backend + 40 de frontend |
| **Infraestructura** | Docker multi-stage | Node 22 → JDK 21 → imagen final JRE 21 con usuario sin privilegios |

### Competencias técnicas que demuestra

- **Arquitectura en capas** con servicios especializados y el **patrón Strategy** (`DbDialect`) para soportar 6 motores de bases de datos con una sola base de código.
- **Seguridad aplicada**: *whitelisting* de identificadores contra el catálogo del motor, consultas parametrizadas, protección contra *CSV/Formula Injection*, *rate limiting* contra fuerza bruta, CSP y credenciales que nunca se guardan en el navegador.
- **Rendimiento**: paginación en el servidor, resolución de relaciones **sin el problema N+1** (una consulta por columna y página), caché con invalidación, exportación en *streaming* y un semáforo de concurrencia que protege el pool de conexiones.
- **UX/UI y accesibilidad**: diseño *mobile-first* validado de 320 px a 4K, navegación por teclado, ARIA, modo simple y modo experto.
- **Ingeniería de software**: migraciones versionadas, pruebas automatizadas, linting, Docker y configuración externalizada por variables de entorno.

---

## 🗺️ Mapas del sistema

### 1. Arquitectura general

```mermaid
flowchart LR
    U(["👤 Usuario<br/>RRHH · Finanzas · DBA"]) --> FE

    subgraph FE["🖥️ Frontend · React 19"]
        direction TB
        L["Pantalla de acceso"]
        EX["Explorador de tablas"]
        RB["Constructor de reportes"]
        CM["Gestor de conexiones"]
    end

    FE -- "HTTP(S) · JSON<br/>cookie de sesión" --> SEC

    subgraph BE["⚙️ Backend · Spring Boot 4"]
        direction TB
        SEC["🛡️ Spring Security<br/>login · límite de intentos · CSP"]
        API["API REST<br/>/api/auth · /api/db"]
        SRV["Servicios<br/>Metadatos · Relaciones · Reportes<br/>Exportación · Diagnóstico"]
        DIA["Dialectos SQL<br/>(patrón Strategy)"]
        CACHE[("Caché<br/>Caffeine")]
        SEC --> API --> SRV --> DIA
        SRV <--> CACHE
    end

    DIA -- "conexión activa<br/>(intercambiable)" --> BIZ[("🗄️ Base de datos de negocio<br/>SQL Server · PostgreSQL · MySQL<br/>MariaDB · Oracle · SQLite")]
    SRV -- "configuración propia<br/>(migrada por Flyway)" --> APP[("🗃️ SQL Server de la aplicación<br/>relaciones virtuales · plantillas")]
```

### 2. Recorrido del usuario

```mermaid
flowchart LR
    A["🔐 Iniciar sesión"] --> B{"¿Base de datos<br/>correcta?"}
    B -- "No" --> C["🔌 Elegir conexión<br/>y probarla"] --> D
    B -- "Sí" --> D["📄 Abrir una tabla<br/>(pestaña inferior)"]
    D --> E["🔍 Filtrar y revisar"]
    E --> F{"¿Necesitas datos<br/>de otras tablas?"}
    F -- "No" --> G["📥 Descargar Excel"]
    F -- "Sí" --> H["🧩 Cruzar y armar reporte<br/>1 · tabla base<br/>2 · columnas<br/>3 · filtros"]
    H --> I["▶ Ejecutar y revisar"] --> G
    I --> J["💾 Guardar como plantilla"]
```

### 3. Inicio de sesión seguro

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuario
    participant W as Navegador (React)
    participant S as Spring Security
    participant R as Límite de intentos

    U->>W: Usuario + contraseña
    W->>R: POST /api/auth/login (una sola vez)
    alt Más de 8 intentos por minuto
        R-->>W: 429 → «Acceso bloqueado temporalmente»
    else Dentro del límite
        R->>S: Validar credenciales (BCrypt)
        alt Credenciales válidas
            S-->>W: 200 + cookie de sesión HttpOnly (8 h)
            W-->>U: Acceso al explorador
        else Inválidas
            S-->>W: 401 → mensaje genérico (no revela si el usuario existe)
        end
    end
    Note over W,S: Las peticiones siguientes viajan con la cookie,<br/>la contraseña nunca se guarda en el navegador.
```

### 4. Cómo se traduce un código a un nombre (sin N+1)

```mermaid
flowchart LR
    P["Página visible<br/>15 filas"] --> V["Valores únicos de la columna<br/>id_cargo: 118, 119, 120"]
    V --> Q["1 sola consulta<br/>SELECT id, nombre FROM Cargos<br/>WHERE id IN (118, 119, 120)"]
    Q --> M["Mapa de traducción"]
    M --> G["Grilla muestra<br/>118 · Analista Contable"]
```

### 5. Datos propios de la aplicación

```mermaid
erDiagram
    PUSH_CUSTOM_FKS {
        nvarchar schema_name PK
        nvarchar table_name PK
        nvarchar fk_column PK
        nvarchar referenced_table
        nvarchar referenced_column
        nvarchar display_column "columnas a mostrar"
        nvarchar filter_column "filtro opcional"
        bit enabled
    }
    PUSH_CUSTOM_REPORTS {
        nvarchar id PK
        nvarchar name
        nvarchar description
        nvarchar config_json "tablas, cruces, columnas, filtros"
        datetime2 created_at
        datetime2 updated_at
    }
```

> La aplicación **solo escribe** en estas dos tablas propias. Sobre las tablas de negocio únicamente ejecuta consultas de lectura.

---

## 📋 Requisitos previos

### Para usar la aplicación (usuario final)

| Requisito | Detalle |
|---|---|
| Navegador moderno | Chrome, Edge, Firefox o Safari en sus versiones actuales |
| Credenciales de acceso | Usuario y contraseña entregados por quien instaló la aplicación |
| Datos de conexión | Servidor, base de datos, usuario y contraseña de la base que vas a consultar, si no viene preconfigurada |

### Para instalarla (perfil técnico)

| Requisito | Versión | Necesario para |
|---|---|---|
| **SQL Server** accesible | SQL Server o Azure SQL | **Obligatorio.** Es la base propia de la aplicación, donde Flyway crea `dbo.push_custom_fks` y `dbo.push_custom_reports`. El usuario de conexión debe poder **crear tablas** en el primer arranque. |
| **Docker** *(opción recomendada)* | 20+ | Ejecutar todo sin instalar Java ni Node |
| **Java** *(sin Docker)* | JDK 21 | Compilar y ejecutar el backend |
| **Node.js** *(sin Docker)* | 20 o 22 + npm | Compilar el frontend |
| Puertos libres | `8080` (app) · `5173` (solo en desarrollo) | |
| Acceso de red | Desde el servidor de la app hacia las bases de datos | Firewall o VPN según corresponda |

> [!TIP]
> Para explorar datos, lo ideal es usar un **usuario de base de datos de solo lectura** sobre las tablas de negocio. La aplicación no las modifica, pero el principio de mínimo privilegio suma una capa extra de seguridad.

---

## 🚀 Instalación

### 1. Configurar las variables de entorno

```bash
cp .env.example .env
```

Completa el archivo `.env`, que **nunca se sube a git** (está en `.gitignore`):

| Variable | Obligatoria | Descripción |
|---|:---:|---|
| `DB_URL` | ✅ | URL JDBC del SQL Server de la aplicación |
| `DB_USERNAME` / `DB_PASSWORD` | ✅ | Credenciales de ese SQL Server |
| `APP_USER` / `APP_PASSWORD` | ✅ | **Usuario y contraseña para entrar a la aplicación** (ver [cómo funciona el acceso](#-cómo-funciona-el-acceso)) |
| `EXPORT_MAX_ROWS` | | Máximo de filas por exportación a Excel (por defecto `50000`) |
| `EXPORT_MAX_CONCURRENT` | | Exportaciones simultáneas permitidas (por defecto `2`) |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` | | Tamaño del pool de conexiones (por defecto `10` / `2`) |
| `SESSION_COOKIE_SECURE` | | Usa `true` en producción con HTTPS |

### 2. Ejecutar

<details open>
<summary><b>Opción A · Docker (recomendada)</b></summary>

```bash
docker build -t multidb-workbook .
docker run -d --name multidb-workbook -p 8080:8080 --env-file .env --restart unless-stopped multidb-workbook
```
Abre **http://localhost:8080**. Para verificar el estado: `curl http://localhost:8080/actuator/health`
</details>

<details>
<summary><b>Opción B · Desarrollo local (con recarga en caliente)</b></summary>

```bash
# Terminal 1 · Backend (puerto 8080)
./mvnw spring-boot:run

# Terminal 2 · Frontend (puerto 5173, con proxy hacia el backend)
cd frontend && npm install && npm run dev
```
Abre **http://localhost:5173**.
</details>

<details>
<summary><b>Opción C · Un único archivo .jar</b></summary>

```bash
cd frontend && npm ci && npm run build && cd ..
rm -rf src/main/resources/static && mkdir -p src/main/resources/static
cp -r frontend/dist/* src/main/resources/static/
./mvnw clean package -DskipTests
java -jar target/PushDbTemplate-0.0.1-SNAPSHOT.jar
```
Ejecuta el `.jar` desde la carpeta que contiene el `.env`. Abre **http://localhost:8080**.
</details>

### 🔑 Cómo funciona el acceso

- Existe **un solo usuario**, definido por `APP_USER` y `APP_PASSWORD`. No hay pantalla de registro.
- Al arrancar, el servidor crea ese usuario **en memoria** y cifra la contraseña con **BCrypt**. No se guarda en ninguna base de datos.
- **Para cambiar la contraseña:** edita el `.env` y **reinicia** la aplicación.
- Si faltan `APP_USER` o `APP_PASSWORD`, la aplicación **no arranca**. Es intencional, para que nunca quede expuesta sin protección.
- Tras 8 intentos fallidos en un minuto desde la misma IP, el acceso se bloquea temporalmente.

---

## 📖 Manual de uso

> Pensado para personas sin conocimientos técnicos. Todo se hace con clics.

### Paso 1 · Ingresar
1. Abre la dirección de la aplicación en tu navegador.
2. Escribe tu **usuario** y **contraseña**. Puedes usar el ícono 👁 para ver lo que escribiste.
3. Presiona **Iniciar sesión** o la tecla Enter.

| Si ves este mensaje… | Qué hacer |
|---|---|
| *Usuario o contraseña incorrectos* | Revisa mayúsculas y minúsculas, y que Bloq Mayús esté desactivado. |
| *Acceso bloqueado temporalmente* | Hubo muchos intentos fallidos. Espera unos minutos. |
| *No pudimos conectar con el servidor* | Revisa tu conexión o avisa al administrador. |
| *Tu sesión ha expirado* | Vuelve a ingresar tu contraseña. La sesión dura 8 horas. |

### Paso 2 · Elegir la base de datos *(solo si no viene preconfigurada)*
1. Haz clic en el botón de conexión, en la parte superior derecha.
2. Elige el motor (SQL Server, PostgreSQL, MySQL, etc.) y completa servidor, base de datos, usuario y contraseña.
3. Presiona **Probar conexión** y, si todo está bien, **Conectar y cargar**.
4. Opcional: **Guardar favorito** para la próxima vez. Por seguridad, la contraseña no se guarda.

### Paso 3 · Explorar una tabla
1. En la **barra inferior** verás las tablas como pestañas. Haz clic en una, o búscala con **Buscar hoja**.
2. Usa **Anterior / Siguiente** y el selector de filas para recorrer los registros.
3. **Filtrar registros:** elige la columna, la condición y el valor, y presiona **Aplicar filtro**. Para volver a ver todo, usa la ✕ del filtro activo.

### Paso 4 · Ver nombres en vez de códigos
1. Entra en **Estructura** (botón junto a *Registros*).
2. En la columna que contiene códigos (por ejemplo `id_cargo`), presiona **Vincular tabla**.
3. Elige la tabla que tiene los nombres (por ejemplo *Cargos*), su columna de código y los datos que quieres mostrar.
4. Guarda con ✓. Desde ese momento la grilla y el Excel mostrarán `118 · Analista Contable`.
5. En la pestaña **Inicio**, el selector **ID / Valor real / Ambos** cambia cómo se muestran.

### Paso 5 · Descargar a Excel
1. Ve a la pestaña **Descargar reporte**.
2. En **Columnas**, marca las que quieres incluir.
3. Presiona **Exportar a Excel (.xlsx)** para descargar la tabla completa con los filtros aplicados, o **Exportar vista (CSV)** para descargar solo lo que ves en pantalla.

### Paso 6 · Cruzar varias tablas en un reporte
1. Haz clic en **Cruzar y armar reportes**, arriba al centro.
2. **① Tabla de partida:** elige la tabla principal, por ejemplo *Empleados*.
3. Revisa las **relaciones detectadas** y presiona **Unir y reemplazar** o **Al final** para sumar otra tabla. También puedes usar **+ Unir otra tabla**.
4. **② Campos para tu Excel:** marca las columnas, ordénalas con ↑ ↓ y cambia su encabezado.
5. **③ Filtrar y ordenar:** agrega condiciones, por ejemplo *estado igual a "Activo"*, y el orden.
6. Presiona **Ejecutar consulta** para ver la vista previa y luego **Descargar Excel (.xlsx)**.
7. **Guardar plantilla** te permite repetir el mismo reporte otro día desde **Mis plantillas**.

### Paso 7 · Modo Fácil o Modo DBA
- **Modo Fácil** (por defecto): lenguaje simple, ideal para RRHH y Finanzas.
- **Modo DBA**: muestra nombres técnicos, el SQL generado y la **consola de diagnóstico** (pestaña *Rendimiento y seguridad*).

> [!TIP]
> El botón **Guía**, en la barra superior, abre una guía interactiva dentro de la aplicación, con consejos y atajos.

---

## 🛡️ Seguridad

| Riesgo | Cómo se mitiga |
|---|---|
| Inyección SQL | Tablas y columnas se validan contra el catálogo real del motor (*whitelist*), los identificadores se escapan según cada dialecto y los valores van parametrizados. |
| Fórmulas maliciosas en Excel/CSV | Los valores que empiezan con `=`, `+`, `-`, `@` o caracteres de control se neutralizan antes de exportar. |
| Fuerza bruta | Límite de 8 intentos por minuto por IP (respuesta 429). |
| Robo de credenciales | La contraseña se envía una sola vez, se cifra con BCrypt en el servidor y nunca se almacena en el navegador. La sesión usa una cookie `HttpOnly` + `SameSite=Lax`. |
| Enumeración de usuarios | El mensaje de error de login es genérico y no indica si el usuario existe. |
| Exposición de errores internos | Los errores se devuelven con un identificador de correlación, sin trazas ni SQL. |
| Agotamiento de recursos | Límite de filas exportables, semáforo de exportaciones concurrentes y Excel en *streaming*. |
| Contenedor | La imagen Docker se ejecuta con un usuario sin privilegios (`appuser`) y aplica *Content-Security-Policy*. |

---

## ✅ Calidad y pruebas

```bash
./mvnw test                          # 218 pruebas de backend (JUnit 5 + MockMvc)
cd frontend && npm test              # 40 pruebas de frontend (Vitest + Testing Library)
cd frontend && npm run lint          # análisis estático (oxlint)
```

Cubren autenticación y límite de intentos, controladores REST, metadatos y caché, relaciones, reportes multi-tabla, exportación a Excel, diagnóstico, conexiones dinámicas, dialectos SQL y utilidades del frontend (CSV seguro, conexiones guardadas, accesibilidad de modales).

---

## 📂 Estructura del proyecto

```
PushDbTemplate/
├── src/main/java/.../PushDbTemplate/
│   ├── config/        Seguridad, límite de intentos, caché y persistencia propia
│   ├── controller/    API REST: /api/auth (acceso) y /api/db (datos y reportes)
│   └── service/       Metadatos, relaciones, reportes, exportación, diagnóstico,
│       └── dialect/   un dialecto por motor (SQL Server, PostgreSQL, MySQL, MariaDB, Oracle, SQLite)
├── src/main/resources/
│   ├── application.yaml        Configuración (lee el .env)
│   └── db/migration/           Migraciones Flyway
├── src/test/                   Pruebas de backend
├── frontend/
│   └── src/
│       ├── components/         Login, Ribbon, grilla, pestañas, modales y constructor de reportes
│       ├── context/            Sesión (AuthContext) y notificaciones (ToastContext)
│       ├── hooks/ · utils/     Accesibilidad de modales, CSV seguro, relaciones
│       └── index.css           Sistema de diseño y responsive
├── docs/screenshots/           Capturas del README
├── Dockerfile                  Build multi-stage
└── .env.example                Plantilla de configuración
```

---

## ❓ Preguntas frecuentes

<details>
<summary><b>¿Puede modificar o borrar datos de mi base de datos?</b></summary>
<br>
No. Sobre las tablas de negocio solo ejecuta consultas de lectura. Las únicas escrituras ocurren en sus dos tablas propias de configuración (relaciones virtuales y plantillas).
</details>

<details>
<summary><b>¿Pueden usarla varias personas al mismo tiempo?</b></summary>
<br>
No está pensada para eso. Tiene un solo usuario, y la conexión activa es común para toda la aplicación: si alguien cambia de base de datos, cambia para todos. Para un equipo, lo recomendable es que cada persona ejecute su propia instancia.
</details>

<details>
<summary><b>La aplicación no arranca</b></summary>
<br>
Revisa que exista el archivo <code>.env</code> con <code>APP_USER</code>, <code>APP_PASSWORD</code>, <code>DB_URL</code>, <code>DB_USERNAME</code> y <code>DB_PASSWORD</code>, y que el SQL Server de la aplicación sea accesible y permita crear tablas en el primer arranque.
</details>

<details>
<summary><b>Olvidé la contraseña</b></summary>
<br>
No hay recuperación automática. Quien administra la instalación debe definir una nueva en <code>APP_PASSWORD</code> dentro del <code>.env</code> y reiniciar la aplicación.
</details>

<details>
<summary><b>Al exportar aparece "demasiadas exportaciones en curso"</b></summary>
<br>
Para no saturar la base de datos, solo se permiten 2 exportaciones a la vez (<code>EXPORT_MAX_CONCURRENT</code>). Espera unos segundos y vuelve a intentarlo.
</details>

---

## 🧭 Limitaciones conocidas y próximos pasos

| Hoy | Evolución propuesta |
|---|---|
| Un único usuario, definido por variables de entorno | Usuarios en base de datos con roles (*Consulta* / *Administrador*) o inicio de sesión con el directorio corporativo (Azure AD / LDAP) |
| Una conexión activa común a toda la aplicación | Conexión independiente por sesión de usuario |
| Sin recuperación de contraseña ni MFA | Flujo de recuperación y segundo factor de autenticación |
| Montos sin separador de miles en la grilla | Formato numérico configurable por columna |

---

<div align="center">

Desarrollado por **[@maximiliano-andres](https://github.com/maximiliano-andres)**

*Java · Spring Boot · React · SQL · Seguridad de aplicaciones · UX para usuarios de negocio*

</div>
