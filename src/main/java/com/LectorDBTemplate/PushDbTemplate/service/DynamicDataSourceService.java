package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.dialect.DbDialect;
import com.LectorDBTemplate.PushDbTemplate.service.dialect.DbDialectFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Servicio centralizado para gestionar dinámicamente la conexión activa a base de datos.
 * Permite alternar en caliente entre múltiples motores (SQL Server, MySQL, PostgreSQL, Oracle, SQLite, MariaDB),
 * probar conectividad con cálculo de latencia y notificar a las cachés para su invalidación inmediata.
 */
@Service
public class DynamicDataSourceService {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceService.class);

    // Al cambiar de conexión, el pool anterior se cierra con una demora en vez de
    // inmediatamente: HikariPool.shutdown() aborta a la fuerza las conexiones que
    // otros usuarios tengan prestadas en ese instante, lo que rompía queries
    // concurrentes justo al reconectar. Este margen les da tiempo a terminar.
    private static final long OLD_POOL_GRACE_PERIOD_SECONDS = 30;
    private final ScheduledExecutorService poolCloseExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hikari-pool-closer");
        t.setDaemon(true);
        return t;
    });
    // Pools con cierre programado aún pendiente: si la app se apaga antes de que
    // se cumpla el periodo de gracia, shutdownNow() cancela la tarea programada
    // sin ejecutarla, así que cleanup() los recorre y cierra explícitamente.
    private final Set<HikariDataSource> pendingPoolCloses = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final CacheManager cacheManager;
    // DataSource gestionado por Spring (spring.datasource.*). Es el MISMO que usan Flyway y
    // appJdbcTemplate (FKs personalizadas / plantillas de reportes), así que este servicio
    // nunca debe cerrarlo: antes, al cambiar de conexión desde el frontend, se programaba su
    // cierre igual que el de cualquier pool dinámico y, 30 s después, la persistencia propia
    // de la app quedaba inutilizable hasta reiniciar ("HikariDataSource has been closed").
    private final DataSource initialDataSource;
    private DataSource currentDataSource;
    private JdbcTemplate currentJdbcTemplate;
    private DbDialect currentDialect;
    private ConnectionConfig currentConfig;
    private String lastConnectionError = null;

    // Tiempo máximo de ejecución por sentencia SQL (ISO/IEC 27002:2022 8.6 «Gestión de capacidad»):
    // evita que un reporte con muchos cruces mantenga ocupada la base de datos indefinidamente.
    // 0 = sin límite (comportamiento anterior).
    private final int queryTimeoutSeconds;

    // Destinos permitidos para conexiones dinámicas (ISO/IEC 27002:2022 8.20/8.22). Vacío = cualquier
    // destino (comportamiento anterior). Ver isDestinationAllowed().
    private final List<String> allowedDbHosts;

    // Si es true, las conexiones dinámicas nunca aceptan certificados TLS sin validar
    // (ISO/IEC 27002:2022 8.24): trustServerCertificate=true se reescribe a false. Por defecto false
    // para no romper conexiones existentes a servidores con certificado autofirmado.
    private final boolean requireTlsValidation;

    public record ConnectionConfig(
            String engineType,        // "SQLSERVER", "POSTGRESQL", "MYSQL", "MARIADB", "ORACLE", "SQLITE", "CUSTOM"
            String host,
            Integer port,
            String databaseName,
            String username,
            String password,
            String schema,
            String customJdbcUrl,
            Map<String, String> additionalParams
    ) {}

    public record TestResult(
            boolean success,
            String message,
            long latencyMs,
            String databaseProduct,
            String databaseVersion,
            String driverName
    ) {}

    public record ConnectResult(
            boolean success,
            String message,
            Map<String, Object> connectionInfo
    ) {}

    public DynamicDataSourceService(DataSource initialDataSource, CacheManager cacheManager) {
        this(initialDataSource, cacheManager, 0, "");
    }

    public DynamicDataSourceService(DataSource initialDataSource, CacheManager cacheManager,
                                    int queryTimeoutSeconds, String allowedDbHostsCsv) {
        this(initialDataSource, cacheManager, queryTimeoutSeconds, allowedDbHostsCsv, false);
    }

    @Autowired
    public DynamicDataSourceService(DataSource initialDataSource, CacheManager cacheManager,
                                    @Value("${app.db.query-timeout-seconds:120}") int queryTimeoutSeconds,
                                    @Value("${app.security.allowed-db-hosts:}") String allowedDbHostsCsv,
                                    @Value("${app.security.require-db-tls-validation:false}") boolean requireTlsValidation) {
        this.requireTlsValidation = requireTlsValidation;
        this.cacheManager = cacheManager;
        this.initialDataSource = initialDataSource;
        this.queryTimeoutSeconds = Math.max(0, queryTimeoutSeconds);
        this.allowedDbHosts = parseAllowedHosts(allowedDbHostsCsv);
        this.currentDataSource = initialDataSource;
        this.currentJdbcTemplate = newJdbcTemplate(initialDataSource);

        // Detectamos el dialecto del datasource inicial
        try (Connection conn = initialDataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String product = meta.getDatabaseProductName();
            String url = meta.getURL();
            this.currentDialect = DbDialectFactory.detectFromProductNameOrUrl(product, url);
            this.currentConfig = new ConnectionConfig(
                    this.currentDialect.getEngineType(),
                    "Configuración del Servidor",
                    this.currentDialect.getDefaultPort(),
                    extractDatabaseName(url),
                    meta.getUserName(),
                    null,
                    null,
                    url,
                    Map.of()
            );
            log.info("Conexión inicial establecida con éxito hacia motor {} ({})", currentDialect.getDisplayName(), product);
        } catch (Exception e) {
            log.warn("No se pudo validar la conexión inicial por defecto: {}. La aplicación esperará conexión desde el frontend.", e.getMessage());
            this.currentDialect = DbDialectFactory.getByEngineType("SQLSERVER");
            this.lastConnectionError = e.getMessage();
        }
    }

    public synchronized DataSource getDataSource() {
        return currentDataSource;
    }

    public synchronized JdbcTemplate getJdbcTemplate() {
        return currentJdbcTemplate;
    }

    public synchronized DbDialect getDialect() {
        return currentDialect != null ? currentDialect : DbDialectFactory.getByEngineType("SQLSERVER");
    }

    public synchronized ConnectionConfig getCurrentConfig() {
        return currentConfig;
    }

    /**
     * Prueba una conexión candidata sin alterar la conexión activa del sistema.
     */
    public TestResult testConnection(ConnectionConfig config) {
        long start = System.currentTimeMillis();
        String jdbcUrl = applyTlsPolicy(resolveJdbcUrl(config));
        String urlError = validateJdbcUrl(jdbcUrl);
        if (urlError == null) {
            urlError = validateDestination(jdbcUrl);
        }
        if (urlError != null) {
            return new TestResult(false, urlError, 0, null, null, null);
        }
        String driverClass = resolveDriverClass(config);

        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            return new TestResult(false, "Driver JDBC no encontrado: " + driverClass, 0, null, null, null);
        }

        try (Connection conn = DriverManager.getConnection(jdbcUrl, config.username(), config.password())) {
            long latency = System.currentTimeMillis() - start;
            DatabaseMetaData meta = conn.getMetaData();
            String product = meta.getDatabaseProductName();
            String version = meta.getDatabaseProductVersion();
            String driver = meta.getDriverName() + " " + meta.getDriverVersion();

            // Ejecutamos consulta de prueba del dialecto
            DbDialect dialect = DbDialectFactory.detectFromProductNameOrUrl(product, jdbcUrl);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(dialect.getSampleQuery());
            }

            return new TestResult(true, "Conexión exitosa a " + product, latency, product, version, driver);
        } catch (Exception e) {
            log.error("Fallo en prueba de conexión hacia {}: {}", sanitizeJdbcUrl(jdbcUrl), e.getMessage());
            return new TestResult(false, "Error de conexión: " + e.getMessage(), 0, null, null, null);
        }
    }

    /**
     * Aplica en caliente una nueva conexión a base de datos, reemplazando el pool activo
     * y purgando todas las cachés de metadatos.
     */
    public synchronized ConnectResult connectTo(ConnectionConfig config) {
        String jdbcUrl = applyTlsPolicy(resolveJdbcUrl(config));
        String urlError = validateJdbcUrl(jdbcUrl);
        if (urlError == null) {
            urlError = validateDestination(jdbcUrl);
        }
        if (urlError != null) {
            return new ConnectResult(false, urlError, null);
        }
        String driverClass = resolveDriverClass(config);

        log.info("Iniciando cambio dinámico de conexión hacia {}...", sanitizeJdbcUrl(jdbcUrl));

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setDriverClassName(driverClass);
        if (config.username() != null && !config.username().isBlank()) {
            hikariConfig.setUsername(config.username().trim());
        }
        if (config.password() != null) {
            hikariConfig.setPassword(config.password());
        }

        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setConnectionTimeout(15000);
        hikariConfig.setIdleTimeout(30000);
        hikariConfig.setMaxLifetime(1800000);
        hikariConfig.setPoolName("DynamicHikariPool-" + System.currentTimeMillis());

        HikariDataSource newDataSource = null;
        DbDialect detectedDialect;
        try {
            newDataSource = new HikariDataSource(hikariConfig);
            // Validamos conexión obteniendo metadata
            try (Connection conn = newDataSource.getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();
                String product = meta.getDatabaseProductName();
                detectedDialect = DbDialectFactory.detectFromProductNameOrUrl(product, jdbcUrl);
                log.info("Conexión verificada exitosamente: {} ({})", detectedDialect.getDisplayName(), product);
            }
        } catch (Exception e) {
            // Si el pool llegó a crearse pero la validación falló, se cierra: antes quedaba
            // abierto (con su hilo de housekeeping) sin ninguna referencia que lo cerrara.
            if (newDataSource != null) {
                try {
                    newDataSource.close();
                } catch (Exception ignored) {}
            }
            log.error("Error al inicializar nuevo pool de conexiones hacia {}: {}", sanitizeJdbcUrl(jdbcUrl), e.getMessage());
            return new ConnectResult(false, "No se pudo conectar: " + e.getMessage(), null);
        }

        // Reemplazo atómico del DataSource
        DataSource oldDataSource = this.currentDataSource;
        this.currentDialect = detectedDialect;
        this.currentDataSource = newDataSource;
        this.currentJdbcTemplate = newJdbcTemplate(newDataSource);
        this.currentConfig = config;
        this.lastConnectionError = null;

        // Limpiar cachés
        clearAllCaches();

        // Cerrar el DataSource antiguo si era un pool Hikari dinámico, con demora:
        // cerrarlo de inmediato abortaría conexiones que otros usuarios tengan
        // prestadas en ese momento (ver comentario en OLD_POOL_GRACE_PERIOD_SECONDS).
        // El DataSource inicial de Spring nunca se cierra aquí (ver initialDataSource).
        if (oldDataSource != initialDataSource && oldDataSource instanceof HikariDataSource oldHikari) {
            pendingPoolCloses.add(oldHikari);
            poolCloseExecutor.schedule(() -> {
                try {
                    oldHikari.close();
                    log.info("Pool anterior cerrado correctamente (tras periodo de gracia).");
                } catch (Exception e) {
                    log.warn("Error al cerrar pool anterior: {}", e.getMessage());
                } finally {
                    pendingPoolCloses.remove(oldHikari);
                }
            }, OLD_POOL_GRACE_PERIOD_SECONDS, TimeUnit.SECONDS);
        }

        return new ConnectResult(true, "Conectado exitosamente a " + currentDialect.getDisplayName(), getCurrentConnectionSummary());
    }

    public synchronized Map<String, Object> getCurrentConnectionSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("engineType", currentDialect != null ? currentDialect.getEngineType() : "SQLSERVER");
        summary.put("displayName", currentDialect != null ? currentDialect.getDisplayName() : "Desconectado");
        summary.put("host", currentConfig != null && currentConfig.host() != null ? currentConfig.host() : "localhost");
        summary.put("port", currentConfig != null && currentConfig.port() != null ? currentConfig.port() : (currentDialect != null ? currentDialect.getDefaultPort() : 0));
        summary.put("databaseName", currentConfig != null && currentConfig.databaseName() != null ? currentConfig.databaseName() : "N/A");
        summary.put("username", currentConfig != null && currentConfig.username() != null ? currentConfig.username() : "N/A");
        summary.put("schema", currentConfig != null ? currentConfig.schema() : null);
        summary.put("jdbcUrl", currentConfig != null ? sanitizeJdbcUrl(applyTlsPolicy(resolveJdbcUrl(currentConfig))) : "N/A");
        summary.put("hasError", lastConnectionError != null);
        summary.put("errorMessage", lastConnectionError);
        return summary;
    }

    public List<Map<String, Object>> getPresets() {
        List<Map<String, Object>> presets = new ArrayList<>();
        for (DbDialect dialect : DbDialectFactory.getAllDialects()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("engineType", dialect.getEngineType());
            p.put("displayName", dialect.getDisplayName());
            p.put("defaultPort", dialect.getDefaultPort());
            p.put("driverClassName", dialect.getDriverClassName());
            presets.add(p);
        }
        return presets;
    }

    private void clearAllCaches() {
        if (cacheManager != null) {
            for (String cacheName : cacheManager.getCacheNames()) {
                org.springframework.cache.Cache c = cacheManager.getCache(cacheName);
                if (c != null) {
                    c.clear();
                }
            }
            log.info("Todas las cachés de metadatos fueron invalidadas.");
        }
    }

    private String resolveJdbcUrl(ConnectionConfig config) {
        if (config.customJdbcUrl() != null && !config.customJdbcUrl().isBlank()) {
            return config.customJdbcUrl().trim();
        }
        DbDialect dialect = DbDialectFactory.getByEngineType(config.engineType());
        return dialect.buildJdbcUrl(config.host(), config.port(), config.databaseName(), config.additionalParams());
    }

    private String resolveDriverClass(ConnectionConfig config) {
        // Con URL JDBC personalizada (engineType CUSTOM en el frontend) el driver se elige por
        // el prefijo de la URL. Antes se usaba siempre getByEngineType("CUSTOM"), que cae a
        // SQL Server: "Probar conexión" funcionaba (DriverManager elige driver por URL) pero
        // "Conectar" fallaba con cualquier motor distinto de SQL Server, porque Hikari exige
        // que el driver configurado acepte la URL.
        if (config.customJdbcUrl() != null && !config.customJdbcUrl().isBlank()) {
            DbDialect byUrl = DbDialectFactory.detectFromUrlStrict(config.customJdbcUrl());
            if (byUrl != null) {
                return byUrl.getDriverClassName();
            }
        }
        DbDialect dialect = DbDialectFactory.getByEngineType(config.engineType());
        return dialect.getDriverClassName();
    }

    // Propiedades de driver JDBC que instancian clases arbitrarias, escriben archivos locales,
    // deserializan datos del servidor o leen archivos del cliente. Cualquier usuario autenticado
    // puede enviar una URL JDBC libre (customJdbcUrl) o inyectar propiedades vía host/databaseName
    // (p. ej. "db;trustManagerClass=..."), así que sin este filtro los endpoints de conexión
    // permitían ejecución de código / escritura de archivos en el servidor (familia de
    // CVE-2022-21724 / CVE-2022-26520 en pgjdbc, autoDeserialize/allowLoadLocalInfile en MySQL).
    private static final java.util.regex.Pattern JDBC_PARAM_NAME =
            java.util.regex.Pattern.compile("[;?&]\\s*([A-Za-z0-9_.\\-]+)\\s*=");
    private static final Set<String> FORBIDDEN_JDBC_PARAMS = Set.of(
            // PostgreSQL
            "socketfactory", "socketfactoryarg", "sslfactory", "sslfactoryarg", "sslhostnameverifier",
            "sslpasswordcallback", "authenticationpluginclassname", "loggerfile", "loggerlevel",
            // MySQL / MariaDB
            "autodeserialize", "allowloadlocalinfile", "allowloadlocalinfileinpath", "allowurlinlocalinfile",
            "allowlocalinfile", "queryinterceptors", "statementinterceptors", "exceptioninterceptors",
            "connectionlifecycleinterceptors", "propertiestransform", "authenticationplugins",
            "defaultauthenticationplugin", "clientinfoprovider", "credentialtype",
            // SQL Server
            "socketfactoryclass", "socketfactoryconstructorarg", "trustmanagerclass",
            "trustmanagerconstructorarg", "accesstokencallbackclass",
            // SQLite
            "enable_load_extension"
    );

    /**
     * Valida una URL JDBC antes de abrir cualquier conexión. Devuelve el mensaje de error para el
     * usuario, o null si es aceptable.
     */
    static String validateJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "La URL JDBC no puede estar vacía.";
        }
        if (DbDialectFactory.detectFromUrlStrict(jdbcUrl) == null) {
            return "URL JDBC no soportada: debe comenzar con jdbc:sqlserver:, jdbc:postgresql:, jdbc:mysql:, "
                    + "jdbc:mariadb:, jdbc:oracle: o jdbc:sqlite:.";
        }
        java.util.regex.Matcher m = JDBC_PARAM_NAME.matcher(jdbcUrl);
        while (m.find()) {
            String param = m.group(1).toLowerCase(Locale.ROOT);
            if (FORBIDDEN_JDBC_PARAMS.contains(param)) {
                return "Parámetro JDBC no permitido por seguridad: '" + m.group(1) + "'.";
            }
        }
        return null;
    }

    private static final java.util.regex.Pattern TRUST_SERVER_CERT_TRUE =
            java.util.regex.Pattern.compile("(?i)(trustServerCertificate\\s*=\\s*)true");

    /** Con requireTlsValidation, fuerza la validación del certificado del servidor (SQL Server). */
    String applyTlsPolicy(String jdbcUrl) {
        if (!requireTlsValidation || jdbcUrl == null) {
            return jdbcUrl;
        }
        return TRUST_SERVER_CERT_TRUE.matcher(jdbcUrl).replaceAll("$1false");
    }

    private JdbcTemplate newJdbcTemplate(DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        if (queryTimeoutSeconds > 0) {
            template.setQueryTimeout(queryTimeoutSeconds);
        }
        return template;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    // --- Lista de destinos permitidos (DB_ALLOWED_HOSTS) ---------------------------------------
    // Sin esta lista, cualquier usuario autenticado puede hacer que el SERVIDOR abra conexiones hacia
    // cualquier host:puerto de la red interna (SSRF / escaneo de red) o, con SQLite, abrir cualquier
    // archivo .db del disco del servidor. Formato: lista separada por comas de hosts exactos
    // (db01.empresa.local, 10.0.0.15), comodines de subdominio (*.empresa.local) y la palabra
    // especial "sqlite" para permitir archivos SQLite. Vacía = sin restricción.

    static final String SQLITE_TOKEN = "sqlite";
    private static final java.util.regex.Pattern ORACLE_HOST_PARAM =
            java.util.regex.Pattern.compile("(?i)\\(\\s*HOST\\s*=\\s*([^)\\s]+)\\s*\\)");

    static List<String> parseAllowedHosts(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Devuelve el mensaje de error para el usuario si el destino no está permitido, o null. */
    String validateDestination(String jdbcUrl) {
        if (allowedDbHosts.isEmpty()) {
            return null;
        }
        if (jdbcUrl.regionMatches(true, 0, "jdbc:sqlite:", 0, 12)) {
            return allowedDbHosts.contains(SQLITE_TOKEN)
                    ? null
                    : "Las conexiones a archivos SQLite no están habilitadas en este servidor.";
        }
        List<String> hosts = extractHosts(jdbcUrl);
        if (hosts.isEmpty()) {
            return "No se pudo determinar el servidor de destino de la URL JDBC.";
        }
        for (String host : hosts) {
            if (!isHostAllowed(host)) {
                return "El servidor '" + host + "' no está en la lista de destinos permitidos. "
                        + "Contacta al administrador de la plataforma.";
            }
        }
        return null;
    }

    private boolean isHostAllowed(String host) {
        for (String allowed : allowedDbHosts) {
            if (allowed.startsWith("*.")) {
                String suffix = allowed.substring(1); // ".empresa.local"
                if (host.endsWith(suffix) && host.length() > suffix.length()) {
                    return true;
                }
            } else if (allowed.equals(host)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extrae los hosts de una URL JDBC de los motores soportados (sin puerto, instancia ni
     * credenciales), en minúsculas. Lista vacía si no se pudo interpretar.
     */
    static List<String> extractHosts(String jdbcUrl) {
        List<String> hosts = new ArrayList<>();
        if (jdbcUrl == null) {
            return hosts;
        }
        java.util.regex.Matcher m = ORACLE_HOST_PARAM.matcher(jdbcUrl);
        while (m.find()) {
            hosts.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        if (!hosts.isEmpty()) {
            return hosts;
        }

        String rest;
        if (jdbcUrl.regionMatches(true, 0, "jdbc:oracle:", 0, 12)) {
            int at = jdbcUrl.indexOf('@');
            if (at < 0) return hosts;
            rest = jdbcUrl.substring(at + 1);
            if (rest.startsWith("//")) rest = rest.substring(2);
        } else {
            int slashes = jdbcUrl.indexOf("//");
            if (slashes < 0) return hosts;
            rest = jdbcUrl.substring(slashes + 2);
        }

        int end = rest.length();
        for (char c : new char[]{'/', ';', '?'}) {
            int i = rest.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        String authority = rest.substring(0, end);
        int userInfo = authority.lastIndexOf('@');
        if (userInfo >= 0) {
            authority = authority.substring(userInfo + 1);
        }

        for (String part : authority.split(",")) {
            String h = part.trim();
            if (h.startsWith("[")) {                       // IPv6 [::1]:5432
                int close = h.indexOf(']');
                h = close > 0 ? h.substring(1, close) : h;
            } else {
                int backslash = h.indexOf('\\');           // SQL Server host\INSTANCIA
                if (backslash >= 0) h = h.substring(0, backslash);
                int colon = h.indexOf(':');
                if (colon >= 0) h = h.substring(0, colon);
            }
            if (!h.isEmpty()) {
                hosts.add(h.toLowerCase(Locale.ROOT));
            }
        }
        return hosts;
    }

    private String sanitizeJdbcUrl(String url) {
        return maskJdbcUrl(url);
    }

    /**
     * Enmascara credenciales en una URL JDBC (formato propiedades y formato userinfo) para
     * logs y respuestas. Reutilizado por DatabaseDiagnosticsService.
     */
    public static String maskJdbcUrl(String url) {
        if (url == null) return "N/A";
        // Cubre tanto los parámetros de query/propiedades típicos (password=...;pwd=...) como
        // credenciales embebidas en la forma userinfo de la URL (jdbc:postgresql://user:pass@host/db),
        // ya que customJdbcUrl es texto libre ingresado por el usuario y puede traer cualquiera de los dos formatos.
        return url.replaceAll("(?i)(password|pwd)=[^;&]*", "$1=******")
                  .replaceAll("(?i)user(name)?=[^;&]*", "user$1=******")
                  .replaceAll("://([^/@\\s]+):([^/@\\s]+)@", "://******:******@");
    }

    private String extractDatabaseName(String url) {
        if (url == null) return "N/A";
        try {
            if (url.contains("databaseName=")) {
                int start = url.indexOf("databaseName=") + 13;
                int end = url.indexOf(';', start);
                return end == -1 ? url.substring(start) : url.substring(start, end);
            }
            int lastSlash = url.lastIndexOf('/');
            if (lastSlash != -1) {
                int q = url.indexOf('?', lastSlash);
                return q == -1 ? url.substring(lastSlash + 1) : url.substring(lastSlash + 1, q);
            }
        } catch (Exception ignored) {}
        return "N/A";
    }

    @PreDestroy
    public void cleanup() {
        // shutdownNow() cancela cualquier cierre programado aún no ejecutado,
        // así que esos pools se cierran a mano para no dejarlos abiertos.
        poolCloseExecutor.shutdownNow();
        for (HikariDataSource pending : pendingPoolCloses) {
            try {
                pending.close();
            } catch (Exception ignored) {}
        }
        // El DataSource inicial lo cierra Spring al destruir su bean; aquí solo los dinámicos.
        if (currentDataSource != initialDataSource && currentDataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }
}
