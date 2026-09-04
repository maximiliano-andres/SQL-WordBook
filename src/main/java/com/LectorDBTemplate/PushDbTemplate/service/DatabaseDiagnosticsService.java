package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.dialect.DbDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Estadísticas y metadatos de conexión del servidor de base de datos activo,
 * con soporte multi-motor para la Consola DBA y el Ribbon.
 */
@Service
public class DatabaseDiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDiagnosticsService.class);
    private final DynamicDataSourceService dynamicDataSourceService;

    public DatabaseDiagnosticsService(DynamicDataSourceService dynamicDataSourceService) {
        this.dynamicDataSourceService = dynamicDataSourceService;
    }

    private JdbcTemplate getJdbcTemplate() {
        return dynamicDataSourceService.getJdbcTemplate();
    }

    public Map<String, Object> getDatabaseInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        DbDialect dialect = dynamicDataSourceService.getDialect();

        try (Connection conn = Objects.requireNonNull(dynamicDataSourceService.getDataSource()).getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            info.put("engineType", dialect.getEngineType());
            info.put("databaseProduct", meta.getDatabaseProductName());
            info.put("databaseVersion", meta.getDatabaseProductVersion());
            info.put("driverName", meta.getDriverName());
            info.put("driverVersion", meta.getDriverVersion());

            String url = meta.getURL();
            if (url != null) {
                url = url.replaceAll("(?i)password=[^;]*", "password=******")
                         .replaceAll("(?i)user=[^;]*", "user=******");
            }
            info.put("jdbcUrl", url);
            String currentDb = conn.getCatalog() != null && !conn.getCatalog().isBlank() ? conn.getCatalog() : conn.getSchema();
            info.put("currentDatabase", currentDb != null ? currentDb : "Principal");

            // Contar tablas y vistas universales
            int totalTables = 0;
            int totalViews = 0;
            try (ResultSet rs = meta.getTables(conn.getCatalog(), null, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String type = rs.getString("TABLE_TYPE");
                    String schem = rs.getString("TABLE_SCHEM");
                    String name = rs.getString("TABLE_NAME");
                    if (!dialect.isSystemTable(schem, name)) {
                        if ("VIEW".equalsIgnoreCase(type)) {
                            totalViews++;
                        } else {
                            totalTables++;
                        }
                    }
                }
            } catch (Exception ignored) {}

            info.put("totalTables", totalTables);
            info.put("totalViews", totalViews);

            // Métricas específicas según el motor
            info.put("dbState", "ONLINE");
            info.put("dbRecoveryModel", dialect.getDisplayName());
            info.put("dbCollation", "UTF-8 / Estándar");
            info.put("activeConnections", 1);
            info.put("dbFiles", Collections.emptyList());
            info.put("totalSizeMb", null);
            info.put("customFksCount", 0);

            if ("SQLSERVER".equalsIgnoreCase(dialect.getEngineType())) {
                enrichSqlServerDiagnostics(info);
            } else if ("POSTGRESQL".equalsIgnoreCase(dialect.getEngineType())) {
                enrichPostgresDiagnostics(info);
            } else if ("MYSQL".equalsIgnoreCase(dialect.getEngineType()) || "MARIADB".equalsIgnoreCase(dialect.getEngineType())) {
                enrichMySqlDiagnostics(info);
            }

        } catch (SQLException e) {
            log.error("Error al obtener información de la base de datos para {}", dialect.getDisplayName(), e);
            info.put("error", "No se pudieron obtener los metadatos de conexión: " + e.getMessage());
            info.put("databaseProduct", dialect.getDisplayName());
            info.put("engineType", dialect.getEngineType());
        }
        return info;
    }

    private void enrichSqlServerDiagnostics(Map<String, Object> info) {
        JdbcTemplate jdbc = getJdbcTemplate();
        try {
            Integer activeConns = jdbc.queryForObject("SELECT COUNT(*) FROM sys.sysprocesses WHERE dbid = DB_ID()", Integer.class);
            info.put("activeConnections", activeConns);
        } catch (Exception ignored) {}

        try {
            Map<String, Object> dbMeta = jdbc.queryForMap(
                "SELECT state_desc, recovery_model_desc, collation_name FROM sys.databases WHERE name = DB_NAME()");
            info.put("dbState", dbMeta.get("state_desc"));
            info.put("dbRecoveryModel", dbMeta.get("recovery_model_desc"));
            info.put("dbCollation", dbMeta.get("collation_name"));
        } catch (Exception ignored) {}
    }

    private void enrichPostgresDiagnostics(Map<String, Object> info) {
        JdbcTemplate jdbc = getJdbcTemplate();
        try {
            Integer activeConns = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database()", Integer.class);
            info.put("activeConnections", activeConns);
        } catch (Exception ignored) {}
        try {
            Long sizeBytes = jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class);
            if (sizeBytes != null) {
                info.put("totalSizeMb", (int)(sizeBytes / (1024 * 1024)));
            }
        } catch (Exception ignored) {}
    }

    private void enrichMySqlDiagnostics(Map<String, Object> info) {
        JdbcTemplate jdbc = getJdbcTemplate();
        try {
            Integer activeConns = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.processlist", Integer.class);
            info.put("activeConnections", activeConns);
        } catch (Exception ignored) {}
    }
}
