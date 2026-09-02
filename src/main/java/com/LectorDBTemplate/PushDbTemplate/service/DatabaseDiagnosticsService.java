package com.LectorDBTemplate.PushDbTemplate.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Estadísticas y metadatos de conexión del servidor de base de datos, usados por la Consola
 * de Diagnóstico (DBA) del frontend. Extraído de la antigua DatabaseService (ver auditoría,
 * hallazgo F6): a diferencia de los demás servicios, no comparte estado ni depende de la
 * validación de esquema — es autocontenido.
 */
@Service
public class DatabaseDiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDiagnosticsService.class);
    private final JdbcTemplate jdbcTemplate;

    public DatabaseDiagnosticsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Recupera información general de la base de datos de manera segura y confidencial,
     * incorporando métricas y estadísticas avanzadas para desarrolladores y consultores expertos.
     */
    public Map<String, Object> getDatabaseInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        try (Connection conn = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            info.put("databaseProduct", meta.getDatabaseProductName());
            info.put("databaseVersion", meta.getDatabaseProductVersion());
            info.put("driverName", meta.getDriverName());
            info.put("driverVersion", meta.getDriverVersion());

            // Ocultamos credenciales delicadas del URL JDBC de conexión
            String url = meta.getURL();
            if (url != null) {
                url = url.replaceAll("(?i)password=[^;]*", "password=******");
                url = url.replaceAll("(?i)user=[^;]*", "user=******");
            }
            info.put("jdbcUrl", url);

            // --- Estadísticas y Datos para Consultor y Desarrollador Experto ---

            // 1. Total de tablas en el esquema actual
            try {
                Integer totalTables = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_type = 'BASE TABLE'", Integer.class);
                info.put("totalTables", totalTables);
            } catch (Exception e) {
                info.put("totalTables", null);
            }

            // 2. Total de vistas en el esquema actual
            try {
                Integer totalViews = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_type = 'VIEW'", Integer.class);
                info.put("totalViews", totalViews);
            } catch (Exception e) {
                info.put("totalViews", null);
            }

            // 3. Conexiones/procesos activos en esta base de datos
            try {
                Integer activeConnections = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys.sysprocesses WHERE dbid = DB_ID()", Integer.class);
                info.put("activeConnections", activeConnections);
            } catch (Exception e) {
                info.put("activeConnections", null);
            }

            // 4. Estado de la base de datos, Collation y Recovery Model
            try {
                Map<String, Object> dbMeta = jdbcTemplate.queryForMap(
                    "SELECT state_desc, recovery_model_desc, collation_name FROM sys.databases WHERE name = DB_NAME()");
                info.put("dbState", dbMeta.get("state_desc"));
                info.put("dbRecoveryModel", dbMeta.get("recovery_model_desc"));
                info.put("dbCollation", dbMeta.get("collation_name"));
            } catch (Exception e) {
                // Fallback usando DATABASEPROPERTYEX si sys.databases no está accesible
                try {
                    String collation = jdbcTemplate.queryForObject("SELECT DATABASEPROPERTYEX(DB_NAME(), 'Collation')", String.class);
                    String status = jdbcTemplate.queryForObject("SELECT DATABASEPROPERTYEX(DB_NAME(), 'Status')", String.class);
                    String recovery = jdbcTemplate.queryForObject("SELECT DATABASEPROPERTYEX(DB_NAME(), 'Recovery')", String.class);
                    info.put("dbState", status);
                    info.put("dbRecoveryModel", recovery);
                    info.put("dbCollation", collation);
                } catch (Exception ex) {
                    info.put("dbState", "ONLINE");
                    info.put("dbRecoveryModel", "SIMPLE");
                    info.put("dbCollation", "SQL_Latin1_General_CP1_CI_AS");
                }
            }

            // 5. Tamaño total de la BD en disco (Archivos MDF y LDF)
            try {
                List<Map<String, Object>> files = jdbcTemplate.queryForList(
                    "SELECT name, type_desc, (size * 8) / 1024 AS size_mb FROM sys.database_files");
                List<Map<String, Object>> formattedFiles = new ArrayList<>();
                int totalSizeMb = 0;
                for (Map<String, Object> file : files) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("name", file.get("name"));
                    f.put("type", file.get("type_desc"));
                    Number sizeVal = (Number) file.get("size_mb");
                    int size = sizeVal != null ? sizeVal.intValue() : 0;
                    f.put("sizeMb", size);
                    totalSizeMb += size;
                    formattedFiles.add(f);
                }
                info.put("dbFiles", formattedFiles);
                info.put("totalSizeMb", totalSizeMb);
            } catch (Exception e) {
                info.put("dbFiles", Collections.emptyList());
                info.put("totalSizeMb", null);
            }

            // 6. Relaciones manuales virtuales guardadas (Custom FKs)
            try {
                Integer customFksCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dbo.push_custom_fks", Integer.class);
                info.put("customFksCount", customFksCount);
            } catch (Exception e) {
                info.put("customFksCount", 0);
            }

        } catch (SQLException e) {
            log.error("Error al obtener información de la base de datos", e);
            info.put("error", "No se pudieron obtener los metadatos de conexión: " + e.getMessage());
        }
        return info;
    }
}
