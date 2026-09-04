package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.dialect.DbDialect;
import com.LectorDBTemplate.PushDbTemplate.service.dialect.DbDialectFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private DataSource currentDataSource;
    private JdbcTemplate currentJdbcTemplate;
    private DbDialect currentDialect;
    private ConnectionConfig currentConfig;
    private String lastConnectionError = null;

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
        this.cacheManager = cacheManager;
        this.currentDataSource = initialDataSource;
        this.currentJdbcTemplate = new JdbcTemplate(initialDataSource);

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
        String jdbcUrl = resolveJdbcUrl(config);
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
            log.error("Fallo en prueba de conexión hacia {}: {}", jdbcUrl, e.getMessage());
            return new TestResult(false, "Error de conexión: " + e.getMessage(), 0, null, null, null);
        }
    }

    /**
     * Aplica en caliente una nueva conexión a base de datos, reemplazando el pool activo
     * y purgando todas las cachés de metadatos.
     */
    public synchronized ConnectResult connectTo(ConnectionConfig config) {
        String jdbcUrl = resolveJdbcUrl(config);
        String driverClass = resolveDriverClass(config);

        log.info("Iniciando cambio dinámico de conexión hacia {}...", jdbcUrl);

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

        HikariDataSource newDataSource;
        try {
            newDataSource = new HikariDataSource(hikariConfig);
            // Validamos conexión obteniendo metadata
            try (Connection conn = newDataSource.getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();
                String product = meta.getDatabaseProductName();
                this.currentDialect = DbDialectFactory.detectFromProductNameOrUrl(product, jdbcUrl);
                log.info("Conexión verificada exitosamente: {} ({})", currentDialect.getDisplayName(), product);
            }
        } catch (Exception e) {
            log.error("Error al inicializar nuevo pool de conexiones: {}", e.getMessage());
            return new ConnectResult(false, "No se pudo conectar: " + e.getMessage(), null);
        }

        // Reemplazo atómico del DataSource
        DataSource oldDataSource = this.currentDataSource;
        this.currentDataSource = newDataSource;
        this.currentJdbcTemplate = new JdbcTemplate(newDataSource);
        this.currentConfig = config;
        this.lastConnectionError = null;

        // Limpiar cachés
        clearAllCaches();

        // Cerrar el DataSource antiguo si era un pool Hikari dinámico, con demora:
        // cerrarlo de inmediato abortaría conexiones que otros usuarios tengan
        // prestadas en ese momento (ver comentario en OLD_POOL_GRACE_PERIOD_SECONDS).
        if (oldDataSource instanceof HikariDataSource oldHikari) {
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
        summary.put("jdbcUrl", currentConfig != null ? sanitizeJdbcUrl(resolveJdbcUrl(currentConfig)) : "N/A");
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
        DbDialect dialect = DbDialectFactory.getByEngineType(config.engineType());
        return dialect.getDriverClassName();
    }

    private String sanitizeJdbcUrl(String url) {
        if (url == null) return "N/A";
        return url.replaceAll("(?i)password=[^;]*", "password=******")
                  .replaceAll("(?i)user=[^;]*", "user=******");
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
        if (currentDataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }
}
