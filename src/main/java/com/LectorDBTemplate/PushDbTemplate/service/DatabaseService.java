package com.LectorDBTemplate.PushDbTemplate.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
public class DatabaseService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseService.class);
    private final JdbcTemplate jdbcTemplate;
    private final long maxExportRows;

    // Referencia al propio bean (proxy de Spring): las llamadas internas a
    // métodos @Cacheable (getTables/getColumns/getTableCount) deben pasar por el proxy o la
    // caché nunca se activa (limitación conocida de AOP por auto-invocación).
    // Se inyecta por campo (en vez de por constructor) para romper el ciclo
    // auto-referencial; @Lazy evita que Spring intente crear el proxy antes de tiempo.
    @Autowired
    @Lazy
    private DatabaseService self;

    // Nombre del archivo plano legado: las FKs personalizadas ahora se persisten en la
    // tabla dbo.push_custom_fks (ver ensureCustomFksTableExists) para sobrevivir a
    // reinicios y múltiples instancias. Este archivo solo se lee una vez, al arrancar,
    // para migrar configuraciones ya existentes hacia la base de datos.
    private static final String LEGACY_CUSTOM_FKS_FILE = "custom-fks.json";
    private static final String CUSTOM_FKS_TABLE = "dbo.push_custom_fks";
    private static final String CUSTOM_REPORTS_TABLE = "dbo.push_custom_reports";

    // Limita cuántas exportaciones completas pueden ejecutarse en paralelo: cada una
    // retiene una conexión de HikariCP (pool de tamaño fijo) durante todo el streaming
    // hacia el cliente, así que sin este límite unas pocas exportaciones concurrentes
    // podrían agotar el pool y bloquear el resto de la aplicación.
    private final Semaphore exportSemaphore;
    private final int maxConcurrentExports;

    @Autowired
    private org.springframework.cache.CacheManager cacheManager;

    public DatabaseService(JdbcTemplate jdbcTemplate,
                            @Value("${app.export.max-rows:50000}") long maxExportRows,
                            @Value("${app.export.max-concurrent:2}") int maxConcurrentExports) {
        this.jdbcTemplate = jdbcTemplate;
        this.maxExportRows = maxExportRows;
        this.maxConcurrentExports = maxConcurrentExports;
        this.exportSemaphore = new Semaphore(maxConcurrentExports);
    }

    @PostConstruct
    public void initCustomFksStorage() {
        ensureCustomFksTableExists();
        ensureCustomReportsTableExists();
        migrateLegacyJsonFileIfNeeded();
    }

    private void ensureCustomReportsTableExists() {
        jdbcTemplate.execute("""
                IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'push_custom_reports' AND schema_id = SCHEMA_ID('dbo'))
                BEGIN
                    CREATE TABLE dbo.push_custom_reports (
                        id NVARCHAR(64) NOT NULL CONSTRAINT PK_push_custom_reports PRIMARY KEY,
                        name NVARCHAR(255) NOT NULL,
                        description NVARCHAR(1000) NULL,
                        config_json NVARCHAR(MAX) NOT NULL,
                        created_at DATETIME2 NOT NULL CONSTRAINT DF_push_custom_reports_created DEFAULT GETDATE(),
                        updated_at DATETIME2 NOT NULL CONSTRAINT DF_push_custom_reports_updated DEFAULT GETDATE()
                    )
                END
                """);
    }

    private void ensureCustomFksTableExists() {
        jdbcTemplate.execute("""
                IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'push_custom_fks' AND schema_id = SCHEMA_ID('dbo'))
                BEGIN
                    CREATE TABLE dbo.push_custom_fks (
                        schema_name NVARCHAR(128) NOT NULL,
                        table_name NVARCHAR(128) NOT NULL,
                        fk_column NVARCHAR(128) NOT NULL,
                        referenced_schema NVARCHAR(128) NOT NULL,
                        referenced_table NVARCHAR(128) NOT NULL,
                        referenced_column NVARCHAR(128) NOT NULL,
                        display_column NVARCHAR(256) NULL,
                        filter_column NVARCHAR(128) NULL,
                        filter_value NVARCHAR(256) NULL,
                        enabled BIT NOT NULL CONSTRAINT DF_push_custom_fks_enabled DEFAULT 1,
                        CONSTRAINT PK_push_custom_fks PRIMARY KEY (schema_name, table_name, fk_column)
                    )
                END
                """);
    }

    // Migración de una sola vez: si ya existe custom-fks.json (versión anterior a
    // esta, basada en archivo) y la tabla todavía está vacía, se importa su contenido
    // para no perder configuraciones ya guardadas. No se borra el archivo original.
    private void migrateLegacyJsonFileIfNeeded() {
        File legacyFile = new File(LEGACY_CUSTOM_FKS_FILE);
        if (!legacyFile.exists()) {
            return;
        }
        Long existingRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + CUSTOM_FKS_TABLE, Long.class);
        if (existingRows != null && existingRows > 0) {
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, List<ForeignKeyInfo>> legacy = mapper.readValue(legacyFile, new TypeReference<Map<String, List<ForeignKeyInfo>>>() {});
            int migratedTables = 0;
            for (Map.Entry<String, List<ForeignKeyInfo>> entry : legacy.entrySet()) {
                String[] parts = entry.getKey().split("\\.", 2);
                if (parts.length != 2) {
                    continue;
                }
                saveCustomFks(parts[0], parts[1], entry.getValue());
                migratedTables++;
            }
            log.info("Migradas configuraciones de FKs personalizadas de {} tablas desde {} a {}",
                    migratedTables, LEGACY_CUSTOM_FKS_FILE, CUSTOM_FKS_TABLE);
        } catch (Exception e) {
            log.error("Error al migrar {} a la base de datos", LEGACY_CUSTOM_FKS_FILE, e);
        }
    }

    public synchronized void saveCustomFks(String schema, String tableName, List<ForeignKeyInfo> fks) {
        // Deduplicar por columna (si el payload trae la misma columna dos veces, gana la última)
        Map<String, ForeignKeyInfo> byColumn = new LinkedHashMap<>();
        if (fks != null) {
            for (ForeignKeyInfo fk : fks) {
                byColumn.put(fk.fkColumn().toLowerCase(Locale.ROOT), new ForeignKeyInfo(
                        fk.fkColumn(), fk.referencedSchema(), fk.referencedTable(), fk.referencedColumn(),
                        fk.displayColumn(), fk.filterColumn(), fk.filterValue(), fk.enabled(), true));
            }
        }

        jdbcTemplate.update("DELETE FROM " + CUSTOM_FKS_TABLE + " WHERE schema_name = ? AND table_name = ?", schema, tableName);
        for (ForeignKeyInfo fk : byColumn.values()) {
            jdbcTemplate.update(
                    "INSERT INTO " + CUSTOM_FKS_TABLE +
                    " (schema_name, table_name, fk_column, referenced_schema, referenced_table, referenced_column, display_column, filter_column, filter_value, enabled)" +
                    " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    schema, tableName, fk.fkColumn(), fk.referencedSchema(), fk.referencedTable(), fk.referencedColumn(),
                    fk.displayColumn(), fk.filterColumn(), fk.filterValue(), fk.enabled());
        }
        log.info("Guardadas {} FKs personalizadas para {}.{} en {}", byColumn.size(), schema, tableName, CUSTOM_FKS_TABLE);

        // Limpiar la caché de la tabla para refrescar
        evictCacheForTable(schema, tableName);
    }

    private List<ForeignKeyInfo> loadCustomFks(String schema, String tableName) {
        String sql = "SELECT fk_column, referenced_schema, referenced_table, referenced_column, " +
                "display_column, filter_column, filter_value, enabled " +
                "FROM " + CUSTOM_FKS_TABLE + " WHERE schema_name = ? AND table_name = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ForeignKeyInfo(
                rs.getString("fk_column"),
                rs.getString("referenced_schema"),
                rs.getString("referenced_table"),
                rs.getString("referenced_column"),
                rs.getString("display_column"),
                rs.getString("filter_column"),
                rs.getString("filter_value"),
                rs.getBoolean("enabled"),
                true
        ), schema, tableName);
    }

    public void evictCacheForTable(String schema, String tableName) {
        String cacheKey = schema + "." + tableName;
        org.springframework.cache.Cache fkCache = cacheManager.getCache("foreignKeys");
        if (fkCache != null) {
            fkCache.evict(cacheKey);
        }
        org.springframework.cache.Cache colCache = cacheManager.getCache("columns");
        if (colCache != null) {
            colCache.evict(cacheKey);
        }
        org.springframework.cache.Cache displayCache = cacheManager.getCache("fkDisplayColumn");
        if (displayCache != null) {
            displayCache.clear();
        }
    }

    // Records de Java 21 para estructurar la información limpiamente
    public record TableInfo(String schema, String name) {}
    public record ColumnInfo(String name, String type, int size, boolean nullable) {}

    // --- Resolución de Foreign Keys ---
    public record ForeignKeyInfo(
        String fkColumn, 
        String referencedSchema, 
        String referencedTable, 
        String referencedColumn,
        String displayColumn,
        String filterColumn,
        String filterValue,
        Boolean enabled,
        Boolean custom
    ) {
        public ForeignKeyInfo {
            if (enabled == null) {
                enabled = true;
            }
            if (custom == null) {
                custom = false;
            }
        }

        public ForeignKeyInfo(String fkColumn, String referencedSchema, String referencedTable, String referencedColumn) {
            this(fkColumn, referencedSchema, referencedTable, referencedColumn, null, null, null, true, false);
        }

        public ForeignKeyInfo(String fkColumn, String referencedSchema, String referencedTable, String referencedColumn, String displayColumn) {
            this(fkColumn, referencedSchema, referencedTable, referencedColumn, displayColumn, null, null, true, false);
        }

        public ForeignKeyInfo(String fkColumn, String referencedSchema, String referencedTable, String referencedColumn, String displayColumn, String filterColumn, String filterValue) {
            this(fkColumn, referencedSchema, referencedTable, referencedColumn, displayColumn, filterColumn, filterValue, true, false);
        }
    }
    public record ForeignKeyColumnInfo(String column, String referencedSchema, String referencedTable, String referencedColumn, String displayColumn, String filterColumn, String filterValue, boolean enabled, boolean custom) {}
    public enum FkStatus { RESOLVED, NULL, ORPHAN }
    public record FkCellResolution(FkStatus status, String value) {}
    public record ForeignKeyResolution(List<ForeignKeyColumnInfo> columns, Map<String, List<FkCellResolution>> resolutions) {}

    // --- Records para Reportes Personalizados Multi-Tabla (Custom Reports) ---
    public record TableRef(String schema, String name, String alias) {}
    public record JoinOn(String tableAlias, String column) {}
    public record JoinDefinition(
        String type, // "INNER", "LEFT", "RIGHT", "FULL"
        TableRef table,
        JoinOn onLeft,
        JoinOn onRight
    ) {}
    public record ReportColumn(
        String tableAlias,
        String column,
        String label
    ) {}
    public record ReportFilter(
        String tableAlias,
        String column,
        String operator, // "=", "!=", "<>", ">", "<", ">=", "<=", "LIKE", "NOT LIKE", "IS NULL", "IS NOT NULL", "BETWEEN", "IN"
        String value,
        String value2,
        String logic // "AND", "OR"
    ) {}
    public record ReportSort(
        String tableAlias,
        String column,
        String direction // "ASC", "DESC"
    ) {}
    public record CustomReportQuery(
        TableRef baseTable,
        List<JoinDefinition> joins,
        List<ReportColumn> columns,
        List<ReportFilter> filters,
        List<ReportSort> sorts,
        Integer limit,
        Integer offset,
        Boolean distinct
    ) {}
    public record CustomReportResult(
        List<Map<String, Object>> data,
        long totalRows,
        int limit,
        int offset,
        int currentPage,
        int totalPages,
        long executionTimeMs,
        String generatedSql,
        List<ReportColumn> columns
    ) {}
    public record ReportTemplate(
        String id,
        String name,
        String description,
        String configJson,
        String createdAt,
        String updatedAt
    ) {}
    public record SuggestedJoin(
        String type,
        String sourceSchema,
        String sourceTable,
        String sourceColumn,
        String targetSchema,
        String targetTable,
        String targetColumn,
        String description
    ) {}

    /**
     * Obtiene la lista de tablas disponibles del esquema de manera segura usando DatabaseMetaData.
     * Se cachea (TTL corto, ver application.yaml) porque isTableValid() la invoca en cada request.
     */
    @Cacheable("tables")
    public List<TableInfo> getTables() {
        List<TableInfo> tables = new ArrayList<>();
        try (Connection conn = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            // Filtra por tipo "TABLE" para excluir vistas, sinónimos, tablas del sistema, etc.
            try (ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    String name = rs.getString("TABLE_NAME");

                    // Excluimos esquemas del sistema propios de SQL Server
                    if (!schema.equalsIgnoreCase("sys") &&
                        !schema.equalsIgnoreCase("INFORMATION_SCHEMA") &&
                        !schema.equalsIgnoreCase("db_owner") &&
                        !schema.equalsIgnoreCase("db_securityadmin") &&
                        !schema.equalsIgnoreCase("db_ddladmin")) {
                        tables.add(new TableInfo(schema, name));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Error al recuperar metadatos de las tablas", e);
        }
        // Ordenamos las tablas por esquema y luego por nombre
        tables.sort(Comparator.comparing(TableInfo::schema).thenComparing(TableInfo::name));
        return tables;
    }

    /**
     * Valida de manera estricta si un esquema y tabla existen en la base de datos (Whitelist Validation).
     * Esto previene ataques de Inyección SQL.
     */
    public boolean isTableValid(String schema, String tableName) {
        if (schema == null || tableName == null) {
            return false;
        }
        return self.getTables().stream().anyMatch(t ->
            t.schema().equalsIgnoreCase(schema) && t.name().equalsIgnoreCase(tableName)
        );
    }

    /**
     * Obtiene la definición de columnas de una tabla seleccionada usando DatabaseMetaData.
     * Se cachea por tabla (mismo motivo que getTables()).
     */
    @Cacheable(value = "columns", key = "#schema + '.' + #tableName")
    public List<ColumnInfo> getColumns(String schema, String tableName) {
        if (!isTableValid(schema, tableName)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + tableName + ")");
        }

        List<ColumnInfo> columns = new ArrayList<>();
        try (Connection conn = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getColumns(null, schema, tableName, "%")) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    String dataType = rs.getString("TYPE_NAME");
                    int columnSize = rs.getInt("COLUMN_SIZE");
                    String isNullable = rs.getString("IS_NULLABLE");
                    columns.add(new ColumnInfo(columnName, dataType, columnSize, "YES".equalsIgnoreCase(isNullable)));
                }
            }
        } catch (SQLException e) {
            log.error("Error al recuperar columnas para la tabla {}.{}", schema, tableName, e);
        }
        return columns;
    }

    private String buildFilterCondition(
            String schema, 
            String tableName, 
            String filterColumn, 
            String filterOperator, 
            String filterValue, 
            String filterValue2,
            List<Object> queryParams) {
        
        if (filterColumn == null || filterColumn.trim().isEmpty()) {
            return null;
        }
        
        // 1. Validar que la columna pertenece a la tabla (evita inyección SQL en la columna)
        List<ColumnInfo> tableCols = self.getColumns(schema, tableName);
        boolean isColValid = tableCols.stream().anyMatch(c -> c.name().equalsIgnoreCase(filterColumn));
        if (!isColValid) {
            throw new SecurityException("Acceso denegado: Nombre de columna no válido para el filtro: '" + filterColumn + "'");
        }
        
        // 2. Validar y normalizar el operador
        if (filterOperator == null || filterOperator.trim().isEmpty()) {
            return null;
        }
        String operator = filterOperator.trim().toUpperCase(Locale.ROOT);
        Set<String> allowedOperators = Set.of("=", "LIKE", ">", "<", ">=", "<=", "IS NULL", "IS NOT NULL", "BETWEEN");
        if (!allowedOperators.contains(operator)) {
            throw new IllegalArgumentException("Operador de filtro no permitido: '" + filterOperator + "'");
        }
        
        // Escapar el nombre de la columna de forma segura
        String safeColName = "[" + filterColumn.replace("]", "]]") + "]";
        
        // 3. Construir la condición SQL y almacenar los parámetros de consulta correspondientes
        if (operator.equals("IS NULL") || operator.equals("IS NOT NULL")) {
            return safeColName + " " + operator;
        } else if (operator.equals("BETWEEN")) {
            if (filterValue == null || filterValue2 == null) {
                return null;
            }
            queryParams.add(filterValue);
            queryParams.add(filterValue2);
            return safeColName + " BETWEEN ? AND ?";
        } else {
            if (filterValue == null) {
                return null;
            }
            if (operator.equals("LIKE")) {
                queryParams.add("%" + filterValue + "%");
            } else {
                queryParams.add(filterValue);
            }
            return safeColName + " " + operator + " ?";
        }
    }

    private String buildFilterCondition(
            String schema, 
            String tableName, 
            String filterColumn, 
            String filterOperator, 
            String filterValue, 
            List<Object> queryParams) {
        return buildFilterCondition(schema, tableName, filterColumn, filterOperator, filterValue, null, queryParams);
    }

    /**
     * Retorna el número total de filas de una tabla de manera segura.
     * Cacheado con TTL corto (ver application.yaml): evita recalcular un COUNT(*)
     * costoso en tablas grandes en cada cambio de página de la misma tabla.
     */
    @Cacheable(value = "tableCount", key = "#schema + '.' + #tableName", condition = "#filterColumn == null || #filterColumn.isEmpty()")
    public long getTableCount(String schema, String tableName, String filterColumn, String filterOperator, String filterValue, String filterValue2) {
        if (!isTableValid(schema, tableName)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + tableName + ")");
        }
        // Escapado seguro con corchetes SQL Server
        String safeTableName = buildSafeTableName(schema, tableName);
        List<Object> params = new ArrayList<>();
        String condition = buildFilterCondition(schema, tableName, filterColumn, filterOperator, filterValue, filterValue2, params);
        
        String sql = "SELECT COUNT(*) FROM " + safeTableName;
        if (condition != null) {
            sql += " WHERE " + condition;
        }

        Long count = jdbcTemplate.queryForObject(sql, Long.class, params.toArray());
        return count != null ? count : 0L;
    }

    public long getTableCount(String schema, String tableName, String filterColumn, String filterOperator, String filterValue) {
        return self.getTableCount(schema, tableName, filterColumn, filterOperator, filterValue, null);
    }

    public long getTableCount(String schema, String tableName) {
        return self.getTableCount(schema, tableName, null, null, null, null);
    }

    /**
     * Obtiene los datos paginados de una tabla de manera segura y optimizada.
     * Utiliza OFFSET / FETCH NEXT nativo de SQL Server. Si se solicitan columnas
     * específicas (validadas contra whitelist), proyecta solo esas en vez de SELECT *.
     */
    public List<Map<String, Object>> getTableData(
            String schema, 
            String tableName, 
            int limit, 
            int offset, 
            List<String> columns,
            String filterColumn,
            String filterOperator,
            String filterValue,
            String filterValue2) {
        
        if (!isTableValid(schema, tableName)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + tableName + ")");
        }

        String safeTableName = buildSafeTableName(schema, tableName);
        String columnList = (columns == null || columns.isEmpty())
                ? "*"
                : buildSafeColumnList(resolveValidColumns(schema, tableName, columns));

        List<Object> queryParams = new ArrayList<>();
        String condition = buildFilterCondition(schema, tableName, filterColumn, filterOperator, filterValue, filterValue2, queryParams);
        
        String sql = "SELECT " + columnList + " FROM " + safeTableName;
        if (condition != null) {
            sql += " WHERE " + condition;
        }
        
        // SQL Server requiere un ORDER BY para usar OFFSET ... FETCH.
        sql += " ORDER BY (SELECT NULL) OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        
        queryParams.add(offset);
        queryParams.add(limit);

        log.debug("Ejecutando consulta paginada en {}.{} con limit={} y offset={} y filtro={}", schema, tableName, limit, offset, condition);
        return jdbcTemplate.queryForList(sql, queryParams.toArray());
    }

    public List<Map<String, Object>> getTableData(String schema, String tableName, int limit, int offset, List<String> columns, String filterColumn, String filterOperator, String filterValue) {
        return getTableData(schema, tableName, limit, offset, columns, filterColumn, filterOperator, filterValue, null);
    }

    public List<Map<String, Object>> getTableData(String schema, String tableName, int limit, int offset, List<String> columns) {
        return getTableData(schema, tableName, limit, offset, columns, null, null, null, null);
    }

    // Nombres de columna candidatos a "valor descriptivo" de una tabla, en orden de prioridad.
    // Es una heurística: no existe metadata JDBC que indique cuál es la columna "legible por humanos"
    // de una tabla, a diferencia de las FK (que sí vienen de DatabaseMetaData.getImportedKeys()).
    private static final List<String> DISPLAY_COLUMN_PRIORITY = List.of(
            "nombre", "name", "descripcion", "description", "titulo", "title",
            "razon_social", "label", "etiqueta", "email"
    );
    private static final Set<String> TEXT_TYPE_NAMES = Set.of(
            "VARCHAR", "NVARCHAR", "CHAR", "NCHAR", "TEXT", "NTEXT", "VARCHAR2", "CLOB"
    );

    /**
     * Detecta las Foreign Keys reales de una tabla usando DatabaseMetaData.getImportedKeys()
     * (metadata del motor, no convenciones de nombres). Las FK compuestas (más de una columna
     * por restricción) se omiten: resolverlas requeriría igualar varias columnas a la vez y
     * no es el caso de uso principal de esta vista.
     */
    @Cacheable(value = "foreignKeys", key = "#schema + '.' + #tableName")
    public List<ForeignKeyInfo> getForeignKeys(String schema, String tableName) {
        if (!isTableValid(schema, tableName)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + tableName + ")");
        }

        List<ForeignKeyInfo> nativeFks = new ArrayList<>();
        Map<String, List<Object[]>> byConstraint = new LinkedHashMap<>();
        try (Connection conn = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getImportedKeys(null, schema, tableName)) {
                while (rs.next()) {
                    String fkName = rs.getString("FK_NAME");
                    String key = (fkName != null) ? fkName : UUID.randomUUID().toString();
                    byConstraint.computeIfAbsent(key, k -> new ArrayList<>()).add(new Object[]{
                            rs.getString("FKCOLUMN_NAME"),
                            rs.getString("PKTABLE_SCHEM"),
                            rs.getString("PKTABLE_NAME"),
                            rs.getString("PKCOLUMN_NAME")
                    });
                }
            }
        } catch (SQLException e) {
            log.error("Error al recuperar foreign keys de {}.{}", schema, tableName, e);
        }

        for (List<Object[]> cols : byConstraint.values()) {
            if (cols.size() == 1) {
                Object[] c = cols.get(0);
                nativeFks.add(new ForeignKeyInfo((String) c[0], (String) c[1], (String) c[2], (String) c[3]));
            }
        }

        // Fusionar claves nativas con configuraciones personalizadas / anuladas
        // (persistidas en dbo.push_custom_fks, no en un archivo local: ver saveCustomFks/loadCustomFks)
        List<ForeignKeyInfo> merged = new ArrayList<>(nativeFks);
        for (ForeignKeyInfo cfk : loadCustomFks(schema, tableName)) {
            // Eliminar la FK nativa existente en esa columna si la hay
            merged.removeIf(nf -> nf.fkColumn().equalsIgnoreCase(cfk.fkColumn()));
            // Añadir la configuración personalizada sólo si está activa
            if (cfk.enabled()) {
                merged.add(cfk);
            }
        }

        return merged;
    }

    /**
     * Heurística de columna descriptiva para una tabla referenciada: prioriza nombres comunes
     * (nombre, descripcion, ...) y si ninguno calza, la primera columna de tipo texto que no
     * sea la propia PK. Si la tabla no tiene columnas de texto, se usa la propia PK (el valor
     * "real" termina siendo igual al id, mejor que no mostrar nada).
     */
    @Cacheable(value = "fkDisplayColumn", key = "#schema + '.' + #tableName + '.' + #primaryKeyColumn")
    public String resolveDisplayColumn(String schema, String tableName, String primaryKeyColumn) {
        List<ColumnInfo> cols = self.getColumns(schema, tableName);

        for (String candidate : DISPLAY_COLUMN_PRIORITY) {
            Optional<ColumnInfo> match = cols.stream()
                    .filter(c -> !c.name().equalsIgnoreCase(primaryKeyColumn))
                    .filter(c -> c.name().equalsIgnoreCase(candidate))
                    .findFirst();
            if (match.isPresent()) {
                return match.get().name();
            }
        }

        return cols.stream()
                .filter(c -> !c.name().equalsIgnoreCase(primaryKeyColumn))
                .filter(c -> TEXT_TYPE_NAMES.contains(c.type().toUpperCase(Locale.ROOT)))
                .findFirst()
                .map(ColumnInfo::name)
                .orElse(primaryKeyColumn);
    }

    /**
     * Resuelve los valores reales de las columnas FK presentes en una página ya cargada.
     * Por cada columna FK se dispara UNA sola consulta por lote (WHERE pk IN (...)) sobre
     * los valores distintos de esa página, en vez de una consulta por fila: evita el N+1 y
     * mantiene el costo independiente del tamaño de la tabla (depende solo del tamaño de página).
     * No modifica ni reordena "rows"; el resultado se devuelve aparte para no alterar el
     * contrato de getTableData().
     */
    public ForeignKeyResolution resolveForeignKeys(String schema, String tableName, List<Map<String, Object>> rows) {
        if (!isTableValid(schema, tableName)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + tableName + ")");
        }

        List<ForeignKeyColumnInfo> columnsInfo = new ArrayList<>();
        Map<String, List<FkCellResolution>> resolutions = new LinkedHashMap<>();

        if (rows.isEmpty()) {
            return new ForeignKeyResolution(columnsInfo, resolutions);
        }

        for (ForeignKeyInfo fk : self.getForeignKeys(schema, tableName)) {
            if (rows.stream().noneMatch(r -> r.containsKey(fk.fkColumn()))) {
                continue; // la proyección actual no incluye esta columna (SELECT de columnas específicas)
            }

            // Caso 1: La tabla de destino no es válida (ej: fue borrada de la base de datos o no existe)
            if (!isTableValid(fk.referencedSchema(), fk.referencedTable())) {
                log.warn("FK de {}.{} en columna {} apunta a tabla inexistente/inválida {}.{}", 
                    schema, tableName, fk.fkColumn(), fk.referencedSchema(), fk.referencedTable());
                
                // Mapeamos resoluciones como nulas/errores
                List<FkCellResolution> cellResults = new ArrayList<>(rows.size());
                for (Map<String, Object> row : rows) {
                    cellResults.add(new FkCellResolution(FkStatus.ORPHAN, "[Tabla no encontrada]"));
                }
                resolutions.put(fk.fkColumn(), cellResults);
                
                // Agregamos a columnsInfo para que sea visible y modificable/eliminable en el frontend
                columnsInfo.add(new ForeignKeyColumnInfo(
                    fk.fkColumn(), 
                    fk.referencedSchema(), 
                    fk.referencedTable(), 
                    fk.referencedColumn(), 
                    fk.referencedColumn(), 
                    fk.filterColumn(),
                    fk.filterValue(),
                    false, 
                    fk.custom()
                ));
                continue;
            }

            // Caso 2: Resolver displayColumn de forma segura (con soporte opcional multi-columna)
            String displayColumn = fk.referencedColumn(); // fallback por defecto
            if (fk.displayColumn() != null && !fk.displayColumn().trim().isEmpty()) {
                displayColumn = fk.displayColumn();
            } else {
                try {
                    displayColumn = self.resolveDisplayColumn(fk.referencedSchema(), fk.referencedTable(), fk.referencedColumn());
                } catch (Exception e) {
                    log.warn("No se pudo obtener displayColumn para la relación en {}.{} apuntando a {}.{}({}): {}", 
                        schema, tableName, fk.referencedSchema(), fk.referencedTable(), fk.referencedColumn(), e.getMessage());
                }
            }

            // Solo resolvemos la consulta por lote si la FK está habilitada
            if (fk.enabled()) {
                List<Object> distinctValues = rows.stream()
                        .map(r -> r.get(fk.fkColumn()))
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();

                // Normalizamos las claves del lookup a String
                Map<String, String> lookup = new HashMap<>();
                boolean querySuccess = true;

                // Parseamos las columnas a proyectar
                final List<String> dispCols = new ArrayList<>();
                if (displayColumn != null && !displayColumn.trim().isEmpty()) {
                    dispCols.addAll(Arrays.stream(displayColumn.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList());
                }
                if (dispCols.isEmpty()) {
                    dispCols.add(fk.referencedColumn());
                }

                if (!distinctValues.isEmpty()) {
                    List<String> selectCols = new ArrayList<>();
                    selectCols.add(fk.referencedColumn());
                    for (String c : dispCols) {
                        if (!c.equalsIgnoreCase(fk.referencedColumn())) {
                            selectCols.add(c);
                        }
                    }

                    String placeholders = String.join(", ", Collections.nCopies(distinctValues.size(), "?"));
                    String sql = "SELECT " + buildSafeColumnList(selectCols)
                            + " FROM " + buildSafeTableName(fk.referencedSchema(), fk.referencedTable())
                            + " WHERE " + buildSafeColumnList(List.of(fk.referencedColumn())) + " IN (" + placeholders + ")";

                    List<Object> queryParams = new ArrayList<>(distinctValues);
                    if (fk.filterColumn() != null && !fk.filterColumn().trim().isEmpty() && fk.filterValue() != null) {
                        sql += " AND " + buildSafeColumnList(List.of(fk.filterColumn())) + " = ?";
                        queryParams.add(fk.filterValue());
                    }

                    try {
                        RowCallbackHandler handler = rs -> {
                            Object key = rs.getObject(1);
                            
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < dispCols.size(); i++) {
                                String colName = dispCols.get(i);
                                int selectIdx = selectCols.indexOf(colName);
                                Object val = null;
                                if (selectIdx != -1) {
                                    val = rs.getObject(selectIdx + 1);
                                }
                                if (val != null) {
                                    if (sb.length() > 0) {
                                        sb.append(" - ");
                                    }
                                    sb.append(val.toString().trim());
                                }
                            }
                            lookup.put(String.valueOf(key), sb.length() > 0 ? sb.toString() : null);
                        };
                        jdbcTemplate.query(sql, handler, queryParams.toArray());
                    } catch (Exception e) {
                        log.error("Error al consultar lote de relación FK en {}.{} columna {} apuntando a {}.{}({}): {}", 
                            schema, tableName, fk.fkColumn(), fk.referencedSchema(), fk.referencedTable(), fk.referencedColumn(), e.getMessage());
                        querySuccess = false;
                    }
                }

                List<FkCellResolution> cellResults = new ArrayList<>(rows.size());
                for (Map<String, Object> row : rows) {
                    Object raw = row.get(fk.fkColumn());
                    if (raw == null) {
                        cellResults.add(new FkCellResolution(FkStatus.NULL, null));
                    } else {
                        String rawKey = String.valueOf(raw);
                        if (!querySuccess) {
                            cellResults.add(new FkCellResolution(FkStatus.ORPHAN, "[Error de Relación]"));
                        } else if (lookup.containsKey(rawKey)) {
                            cellResults.add(new FkCellResolution(FkStatus.RESOLVED, lookup.get(rawKey)));
                        } else {
                            cellResults.add(new FkCellResolution(FkStatus.ORPHAN, null));
                        }
                    }
                }
                resolutions.put(fk.fkColumn(), cellResults);
            } else {
                // Si la FK está deshabilitada, mapeamos las celdas como deshabilitadas o nulas para que no intenten resolverse
                List<FkCellResolution> cellResults = new ArrayList<>(rows.size());
                for (Map<String, Object> row : rows) {
                    cellResults.add(new FkCellResolution(FkStatus.NULL, null));
                }
                resolutions.put(fk.fkColumn(), cellResults);
            }

            columnsInfo.add(new ForeignKeyColumnInfo(fk.fkColumn(), fk.referencedSchema(), fk.referencedTable(), fk.referencedColumn(), displayColumn, fk.filterColumn(), fk.filterValue(), fk.enabled(), fk.custom()));
        }

        return new ForeignKeyResolution(columnsInfo, resolutions);
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

    /**
     * Excepción específica para cuando ya hay demasiadas exportaciones en curso
     * (ver exportSemaphore): cada exportación retiene una conexión de HikariCP durante
     * todo el streaming, así que se limita la concurrencia en vez de dejar que agoten el pool.
     */
    public static class TooManyExportsException extends RuntimeException {
        public TooManyExportsException(String message) {
            super(message);
        }
    }
    public void exportTableToExcel(String schema, String tableName, List<String> selectedColumns, java.io.OutputStream outputStream) throws Exception {
        exportTableToExcel(schema, tableName, selectedColumns, null, null, null, null, outputStream);
    }

    public void exportTableToExcel(
            String schema, 
            String tableName, 
            List<String> selectedColumns, 
            String filterColumn,
            String filterOperator,
            String filterValue,
            java.io.OutputStream outputStream) throws Exception {
        exportTableToExcel(schema, tableName, selectedColumns, filterColumn, filterOperator, filterValue, null, outputStream);
    }

    public void exportTableToExcel(
            String schema, 
            String tableName, 
            List<String> selectedColumns, 
            String filterColumn,
            String filterOperator,
            String filterValue,
            String filterValue2,
            java.io.OutputStream outputStream) throws Exception {
        
        if (!exportSemaphore.tryAcquire()) {
            throw new TooManyExportsException(
                "Hay demasiadas exportaciones en curso (máximo " + maxConcurrentExports + " en paralelo permitido). " +
                "Intenta nuevamente en unos segundos.");
        }
        try {
            if (!isTableValid(schema, tableName)) {
                throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + tableName + ")");
            }

            List<String> validatedColumns = resolveValidColumns(schema, tableName, selectedColumns);

            long totalRows = self.getTableCount(schema, tableName, filterColumn, filterOperator, filterValue, filterValue2);
            if (totalRows > maxExportRows) {
                throw new IllegalArgumentException(
                    "La tabla contiene " + totalRows + " filas, por encima del máximo exportable de "
                        + maxExportRows + ". Aplique filtros o contacte al administrador.");
            }

            List<Object> queryParams = new ArrayList<>();
            String condition = buildFilterCondition(schema, tableName, filterColumn, filterOperator, filterValue, filterValue2, queryParams);

            String columnsBuilder = buildSafeColumnList(validatedColumns);
            String safeTableName = buildSafeTableName(schema, tableName);
            String sql = "SELECT " + columnsBuilder + " FROM " + safeTableName;
            if (condition != null) {
                sql += " WHERE " + condition;
            }

            log.info("Iniciando exportación .xlsx de reporte completo por streaming para la tabla {}.{} con filtro={} y valor2={}", schema, tableName, condition, filterValue2);

            // Cargar mapas de traducción de claves foráneas activas y válidas
            Map<String, Map<String, String>> fkLookups = new HashMap<>();
            try {
                for (ForeignKeyInfo fk : self.getForeignKeys(schema, tableName)) {
                    if (fk.enabled() && isTableValid(fk.referencedSchema(), fk.referencedTable())) {
                        
                        List<Object> distinctParams = new ArrayList<>();
                        String distinctCondition = buildFilterCondition(schema, tableName, filterColumn, filterOperator, filterValue, filterValue2, distinctParams);
                        
                        String distinctSql = "SELECT DISTINCT [" + fk.fkColumn().replace("]", "]]") + "] "
                                + "FROM " + safeTableName;
                        
                        if (distinctCondition != null) {
                            distinctSql += " WHERE " + distinctCondition + " AND [" + fk.fkColumn().replace("]", "]]") + "] IS NOT NULL";
                        } else {
                            distinctSql += " WHERE [" + fk.fkColumn().replace("]", "]]") + "] IS NOT NULL";
                        }
                        
                        List<Object> distinctValues = jdbcTemplate.query(distinctSql, (rs, rowNum) -> rs.getObject(1), distinctParams.toArray())
                                .stream()
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList();

                        if (!distinctValues.isEmpty()) {
                            String displayColumn = fk.referencedColumn();
                            if (fk.displayColumn() != null && !fk.displayColumn().trim().isEmpty()) {
                                displayColumn = fk.displayColumn();
                            } else {
                                try {
                                    displayColumn = self.resolveDisplayColumn(fk.referencedSchema(), fk.referencedTable(), fk.referencedColumn());
                                } catch (Exception e) {
                                    log.warn("Exportación: No se pudo obtener displayColumn para {}.{}: {}", 
                                        fk.referencedSchema(), fk.referencedTable(), e.getMessage());
                                }
                            }

                            final List<String> dispCols = new ArrayList<>();
                            if (displayColumn != null && !displayColumn.trim().isEmpty()) {
                                dispCols.addAll(Arrays.stream(displayColumn.split(","))
                                        .map(String::trim)
                                        .filter(s -> !s.isEmpty())
                                        .toList());
                            }
                            if (dispCols.isEmpty()) {
                                dispCols.add(fk.referencedColumn());
                            }

                            List<String> selectCols = new ArrayList<>();
                            selectCols.add(fk.referencedColumn());
                            for (String c : dispCols) {
                                if (!c.equalsIgnoreCase(fk.referencedColumn())) {
                                    selectCols.add(c);
                                }
                            }

                            String placeholders = String.join(", ", Collections.nCopies(distinctValues.size(), "?"));
                            String lookupSql = "SELECT " + buildSafeColumnList(selectCols)
                                    + " FROM " + buildSafeTableName(fk.referencedSchema(), fk.referencedTable())
                                    + " WHERE " + buildSafeColumnList(List.of(fk.referencedColumn())) + " IN (" + placeholders + ")";

                            List<Object> lookupQueryParams = new ArrayList<>(distinctValues);
                            if (fk.filterColumn() != null && !fk.filterColumn().trim().isEmpty() && fk.filterValue() != null) {
                                lookupSql += " AND " + buildSafeColumnList(List.of(fk.filterColumn())) + " = ?";
                                lookupQueryParams.add(fk.filterValue());
                            }

                            Map<String, String> lookupMap = new HashMap<>();
                            try {
                                RowCallbackHandler handler = rs -> {
                                    Object key = rs.getObject(1);
                                    if (key == null) return;

                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < dispCols.size(); i++) {
                                        String colName = dispCols.get(i);
                                        int selectIdx = selectCols.indexOf(colName);
                                        Object targetVal = null;
                                        if (selectIdx != -1) {
                                            targetVal = rs.getObject(selectIdx + 1);
                                        }
                                        if (targetVal != null) {
                                            if (sb.length() > 0) {
                                                sb.append(" - ");
                                            }
                                            sb.append(targetVal.toString().trim());
                                        }
                                    }
                                    String kStr = String.valueOf(key);
                                    String translation = sb.length() > 0 ? sb.toString() : null;
                                    lookupMap.put(kStr, translation);
                                    lookupMap.put(kStr.trim(), translation);
                                };
                                jdbcTemplate.query(lookupSql, handler, lookupQueryParams.toArray());
                            } catch (Exception e) {
                                log.error("Exportación: Error al resolver FK de la columna {} apuntando a {}.{}: {}", 
                                    fk.fkColumn(), fk.referencedSchema(), fk.referencedTable(), e.getMessage());
                            }

                            fkLookups.put(fk.fkColumn(), lookupMap);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Exportación: Error al pre-procesar lookups de claves foráneas", e);
            }

            try (org.apache.poi.xssf.streaming.SXSSFWorkbook workbook = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100)) {
                workbook.setCompressTempFiles(true);
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Reporte");

                org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
                for (int i = 0; i < validatedColumns.size(); i++) {
                    headerRow.createCell(i).setCellValue(validatedColumns.get(i));
                }

                int[] nextRowIndex = {1};
                jdbcTemplate.query(sql, rs -> {
                    org.apache.poi.ss.usermodel.Row row = sheet.createRow(nextRowIndex[0]++);
                    for (int i = 1; i <= validatedColumns.size(); i++) {
                        String colName = validatedColumns.get(i - 1);
                        Object val = rs.getObject(i);
                        org.apache.poi.ss.usermodel.Cell cell = row.createCell(i - 1);
                        
                        if (val == null) {
                            cell.setBlank();
                        } else {
                            String valStr = val.toString();
                            String trimmedValStr = valStr.trim();
                            Map<String, String> lookup = fkLookups.get(colName);
                            boolean isResolved = false;
                            
                            if (lookup != null) {
                                if (lookup.containsKey(valStr)) {
                                    String resolved = lookup.get(valStr);
                                    if (resolved != null) {
                                        valStr = resolved;
                                        isResolved = true;
                                    }
                                } else if (lookup.containsKey(trimmedValStr)) {
                                    String resolved = lookup.get(trimmedValStr);
                                    if (resolved != null) {
                                        valStr = resolved;
                                        isResolved = true;
                                    }
                                }
                            }
                            
                            if (!isResolved && val instanceof Number number) {
                                cell.setCellValue(number.doubleValue());
                            } else {
                                cell.setCellValue(sanitizeForSpreadsheet(valStr));
                            }
                        }
                    }
                }, queryParams.toArray());

                workbook.write(outputStream);
                workbook.dispose();
            }
            outputStream.flush();
        } finally {
            exportSemaphore.release();
        }
    }

    /**
     * Valida una lista de columnas solicitadas contra el esquema real de la tabla (whitelist).
     */
    private List<String> resolveValidColumns(String schema, String tableName, List<String> requestedColumns) {
        List<String> validColNames = self.getColumns(schema, tableName).stream().map(ColumnInfo::name).toList();
        for (String col : requestedColumns) {
            boolean isValid = validColNames.stream().anyMatch(name -> name.equalsIgnoreCase(col));
            if (!isValid) {
                throw new SecurityException("Acceso denegado: Intento de inyección detectado en columna '" + col + "'");
            }
        }
        return requestedColumns;
    }

    private String buildSafeTableName(String schema, String tableName) {
        return "[" + schema.replace("]", "]]") + "].[" + tableName.replace("]", "]]") + "]";
    }

    private String buildSafeColumnList(List<String> columns) {
        StringBuilder columnsBuilder = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) columnsBuilder.append(", ");
            columnsBuilder.append("[").append(columns.get(i).replace("]", "]]")).append("]");
        }
        return columnsBuilder.toString();
    }

    // Caracteres que Excel/Sheets puede interpretar como inicio de fórmula al abrir un
    // CSV/XLS exportado ("CSV/Formula Injection"). Se neutralizan con un apóstrofe inicial.
    private static final char[] RISKY_SPREADSHEET_PREFIXES = {'=', '+', '-', '@', '\t', '\r'};

    private String sanitizeForSpreadsheet(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        for (char risky : RISKY_SPREADSHEET_PREFIXES) {
            if (first == risky) {
                return "'" + value;
            }
        }
        return value;
    }

    // =========================================================================
    // --- GESTIÓN DE REPORTES PERSONALIZADOS MULTI-TABLA (CUSTOM REPORTS) ---
    // =========================================================================

    public List<ReportTemplate> getReportTemplates() {
        String sql = "SELECT id, name, description, config_json, " +
                "CONVERT(NVARCHAR(30), created_at, 126) AS created_at, " +
                "CONVERT(NVARCHAR(30), updated_at, 126) AS updated_at " +
                "FROM " + CUSTOM_REPORTS_TABLE + " ORDER BY updated_at DESC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ReportTemplate(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("config_json"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        ));
    }

    public ReportTemplate saveReportTemplate(ReportTemplate template) {
        if (template.name() == null || template.name().trim().isEmpty()) {
            throw new IllegalArgumentException("El nombre del reporte es obligatorio.");
        }
        String id = (template.id() != null && !template.id().trim().isEmpty())
                ? template.id().trim()
                : UUID.randomUUID().toString();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + CUSTOM_REPORTS_TABLE + " WHERE id = ?", Integer.class, id);

        if (count != null && count > 0) {
            jdbcTemplate.update(
                    "UPDATE " + CUSTOM_REPORTS_TABLE + " SET name = ?, description = ?, config_json = ?, updated_at = GETDATE() WHERE id = ?",
                    template.name().trim(), template.description(), template.configJson(), id);
        } else {
            jdbcTemplate.update(
                    "INSERT INTO " + CUSTOM_REPORTS_TABLE + " (id, name, description, config_json, created_at, updated_at) VALUES (?, ?, ?, ?, GETDATE(), GETDATE())",
                    id, template.name().trim(), template.description(), template.configJson());
        }

        return new ReportTemplate(id, template.name().trim(), template.description(), template.configJson(), null, null);
    }

    public void deleteReportTemplate(String id) {
        if (id != null && !id.trim().isEmpty()) {
            jdbcTemplate.update("DELETE FROM " + CUSTOM_REPORTS_TABLE + " WHERE id = ?", id.trim());
        }
    }

    /**
     * Detecta y sugiere cruces (Joins) basados en las relaciones de Foreign Keys (físicas y virtuales)
     * salientes y entrantes para la tabla indicada.
     */
    public List<SuggestedJoin> suggestJoinsForTable(String schema, String table) {
        if (!isTableValid(schema, table)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + table + ")");
        }
        List<SuggestedJoin> suggestions = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. Claves foráneas salientes (la tabla seleccionada apunta a una tabla referenciada)
        try {
            List<ForeignKeyInfo> fks = self.getForeignKeys(schema, table);
            for (ForeignKeyInfo fk : fks) {
                if (fk.enabled() && isTableValid(fk.referencedSchema(), fk.referencedTable())) {
                    String sig = schema + "." + table + "." + fk.fkColumn() + "->" + fk.referencedSchema() + "." + fk.referencedTable() + "." + fk.referencedColumn();
                    if (seen.add(sig)) {
                        suggestions.add(new SuggestedJoin(
                                "LEFT",
                                schema, table, fk.fkColumn(),
                                fk.referencedSchema(), fk.referencedTable(), fk.referencedColumn(),
                                "Cruzar con [" + fk.referencedSchema() + "].[" + fk.referencedTable() + "] vía " + fk.fkColumn() + " → " + fk.referencedColumn()
                        ));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error al recuperar FKs salientes para {}.{}: {}", schema, table, e.getMessage());
        }

        // 2. Claves foráneas entrantes (otras tablas del catálogo apuntan a esta tabla)
        try {
            List<TableInfo> allTables = self.getTables();
            for (TableInfo other : allTables) {
                if (other.schema().equalsIgnoreCase(schema) && other.name().equalsIgnoreCase(table)) {
                    continue; // omitir la misma tabla
                }
                try {
                    List<ForeignKeyInfo> otherFks = self.getForeignKeys(other.schema(), other.name());
                    for (ForeignKeyInfo fk : otherFks) {
                        if (fk.enabled() &&
                                fk.referencedSchema().equalsIgnoreCase(schema) &&
                                fk.referencedTable().equalsIgnoreCase(table)) {
                            String sig = other.schema() + "." + other.name() + "." + fk.fkColumn() + "->" + schema + "." + table + "." + fk.referencedColumn();
                            if (seen.add(sig)) {
                                suggestions.add(new SuggestedJoin(
                                        "LEFT",
                                        schema, table, fk.referencedColumn(),
                                        other.schema(), other.name(), fk.fkColumn(),
                                        "Cruzar con [" + other.schema() + "].[" + other.name() + "] vía " + fk.referencedColumn() + " ← " + fk.fkColumn()
                                ));
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warn("Error al buscar FKs entrantes para {}.{}: {}", schema, table, e.getMessage());
        }

        return suggestions;
    }

    private static final Set<String> ALLOWED_JOIN_TYPES = Set.of("INNER", "LEFT", "RIGHT", "FULL");
    private static final Set<String> ALLOWED_REPORT_OPERATORS = Set.of("=", "!=", "<>", ">", "<", ">=", "<=", "LIKE", "NOT LIKE", "IS NULL", "IS NOT NULL", "BETWEEN", "IN");
    private static final Set<String> ALLOWED_LOGICS = Set.of("AND", "OR");

    private record BuiltReportQuery(
            String countSql,
            String selectPagedSql,
            String selectAllSql,
            List<Object> countParams,
            List<Object> selectPagedParams,
            List<Object> selectAllParams,
            List<ReportColumn> validatedColumns,
            String prettySqlPreview
    ) {}

    private BuiltReportQuery buildReportSql(CustomReportQuery query) {
        if (query == null || query.baseTable() == null) {
            throw new IllegalArgumentException("Se requiere una tabla base válida para construir el reporte.");
        }

        TableRef base = query.baseTable();
        if (!isTableValid(base.schema(), base.name())) {
            throw new SecurityException("Acceso denegado: Tabla base no válida (" + base.schema() + "." + base.name() + ")");
        }

        String baseAlias = (base.alias() != null && !base.alias().trim().isEmpty()) ? base.alias().trim() : "t0";
        validateIdentifier(baseAlias, "Alias de tabla base");

        Map<String, TableRef> tableMap = new LinkedHashMap<>();
        tableMap.put(baseAlias, new TableRef(base.schema(), base.name(), baseAlias));

        StringBuilder fromClause = new StringBuilder();
        fromClause.append(buildSafeTableName(base.schema(), base.name())).append(" AS [").append(baseAlias).append("]");

        // Validar y construir Joins
        if (query.joins() != null) {
            for (JoinDefinition join : query.joins()) {
                if (join == null || join.table() == null || join.onLeft() == null || join.onRight() == null) {
                    continue;
                }
                TableRef jTable = join.table();
                if (!isTableValid(jTable.schema(), jTable.name())) {
                    throw new SecurityException("Acceso denegado: Tabla unida no válida (" + jTable.schema() + "." + jTable.name() + ")");
                }
                String jAlias = (jTable.alias() != null && !jTable.alias().trim().isEmpty()) ? jTable.alias().trim() : ("t" + tableMap.size());
                validateIdentifier(jAlias, "Alias de tabla unida");
                if (tableMap.containsKey(jAlias)) {
                    throw new IllegalArgumentException("Alias de tabla duplicado: '" + jAlias + "'");
                }
                tableMap.put(jAlias, new TableRef(jTable.schema(), jTable.name(), jAlias));

                String jType = (join.type() != null ? join.type().trim().toUpperCase(Locale.ROOT) : "LEFT");
                if (jType.endsWith(" JOIN")) {
                    jType = jType.substring(0, jType.length() - 5).trim();
                }
                if (!ALLOWED_JOIN_TYPES.contains(jType)) {
                    throw new IllegalArgumentException("Tipo de Join no permitido: '" + join.type() + "'");
                }

                JoinOn left = join.onLeft();
                JoinOn right = join.onRight();

                TableRef leftTable = tableMap.get(left.tableAlias());
                TableRef rightTable = tableMap.get(right.tableAlias());
                if (leftTable == null) {
                    throw new IllegalArgumentException("Alias de tabla '" + left.tableAlias() + "' en condición ON no encontrado.");
                }
                if (rightTable == null) {
                    throw new IllegalArgumentException("Alias de tabla '" + right.tableAlias() + "' en condición ON no encontrado.");
                }

                validateColumnExists(leftTable.schema(), leftTable.name(), left.column());
                validateColumnExists(rightTable.schema(), rightTable.name(), right.column());

                fromClause.append("\n  ").append(jType).append(" JOIN ")
                        .append(buildSafeTableName(jTable.schema(), jTable.name())).append(" AS [").append(jAlias).append("]\n    ON [")
                        .append(left.tableAlias()).append("].[").append(left.column().replace("]", "]]")).append("] = [")
                        .append(right.tableAlias()).append("].[").append(right.column().replace("]", "]]")).append("]");
            }
        }

        // Validar y construir columnas proyectadas
        List<ReportColumn> validatedColumns = new ArrayList<>();
        StringBuilder selectClause = new StringBuilder();

        if (query.columns() == null || query.columns().isEmpty()) {
            // Default a todas las columnas de la tabla base
            List<ColumnInfo> baseCols = self.getColumns(base.schema(), base.name());
            for (ColumnInfo col : baseCols) {
                validatedColumns.add(new ReportColumn(baseAlias, col.name(), col.name()));
            }
        } else {
            for (ReportColumn col : query.columns()) {
                if (col == null || col.column() == null) continue;
                String tAlias = (col.tableAlias() != null && !col.tableAlias().trim().isEmpty()) ? col.tableAlias().trim() : baseAlias;
                TableRef tRef = tableMap.get(tAlias);
                if (tRef == null) {
                    throw new IllegalArgumentException("Alias de tabla '" + tAlias + "' para la columna '" + col.column() + "' no existe.");
                }
                validateColumnExists(tRef.schema(), tRef.name(), col.column());

                String label = (col.label() != null && !col.label().trim().isEmpty()) ? col.label().trim() : (tAlias + "_" + col.column());
                validatedColumns.add(new ReportColumn(tAlias, col.column(), label));
            }
        }

        if (validatedColumns.isEmpty()) {
            throw new IllegalArgumentException("Debe seleccionar al menos una columna para el reporte.");
        }

        for (int i = 0; i < validatedColumns.size(); i++) {
            if (i > 0) selectClause.append(",\n  ");
            ReportColumn rc = validatedColumns.get(i);
            selectClause.append("[").append(rc.tableAlias()).append("].[").append(rc.column().replace("]", "]]")).append("] AS [")
                    .append(rc.label().replace("]", "]]")).append("]");
        }

        // Validar y construir Filtros (WHERE)
        StringBuilder whereClause = new StringBuilder();
        List<Object> filterParams = new ArrayList<>();

        if (query.filters() != null && !query.filters().isEmpty()) {
            int validFilterIndex = 0;
            for (ReportFilter f : query.filters()) {
                if (f == null || f.column() == null || f.column().trim().isEmpty()) continue;
                String tAlias = (f.tableAlias() != null && !f.tableAlias().trim().isEmpty()) ? f.tableAlias().trim() : baseAlias;
                TableRef tRef = tableMap.get(tAlias);
                if (tRef == null) {
                    throw new IllegalArgumentException("Alias de tabla '" + tAlias + "' en filtro no existe.");
                }
                validateColumnExists(tRef.schema(), tRef.name(), f.column());

                String op = (f.operator() != null ? f.operator().trim().toUpperCase(Locale.ROOT) : "=");
                if (!ALLOWED_REPORT_OPERATORS.contains(op)) {
                    throw new IllegalArgumentException("Operador de filtro no permitido: '" + f.operator() + "'");
                }

                String logic = (f.logic() != null ? f.logic().trim().toUpperCase(Locale.ROOT) : "AND");
                if (!ALLOWED_LOGICS.contains(logic)) {
                    logic = "AND";
                }

                String safeCol = "[" + tAlias + "].[" + f.column().replace("]", "]]") + "]";

                if (validFilterIndex > 0) {
                    whereClause.append(" ").append(logic).append(" ");
                }

                if (op.equals("IS NULL") || op.equals("IS NOT NULL")) {
                    whereClause.append(safeCol).append(" ").append(op);
                } else if (op.equals("BETWEEN")) {
                    if (f.value() == null || f.value2() == null) continue;
                    whereClause.append(safeCol).append(" BETWEEN ? AND ?");
                    filterParams.add(f.value());
                    filterParams.add(f.value2());
                } else if (op.equals("LIKE") || op.equals("NOT LIKE")) {
                    if (f.value() == null) continue;
                    whereClause.append(safeCol).append(" ").append(op).append(" ?");
                    filterParams.add("%" + f.value() + "%");
                } else if (op.equals("IN")) {
                    if (f.value() == null || f.value().trim().isEmpty()) continue;
                    String[] parts = Arrays.stream(f.value().split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toArray(String[]::new);
                    if (parts.length == 0) continue;
                    String inPlaceholders = String.join(", ", Collections.nCopies(parts.length, "?"));
                    whereClause.append(safeCol).append(" IN (").append(inPlaceholders).append(")");
                    Collections.addAll(filterParams, (Object[]) parts);
                } else {
                    if (f.value() == null) continue;
                    whereClause.append(safeCol).append(" ").append(op).append(" ?");
                    filterParams.add(f.value());
                }
                validFilterIndex++;
            }
        }

        boolean isDistinct = Boolean.TRUE.equals(query.distinct());
        String distinctKeyword = isDistinct ? "DISTINCT " : "";

        // Validar y construir Ordenamiento (ORDER BY)
        StringBuilder orderByClause = new StringBuilder();
        if (query.sorts() != null && !query.sorts().isEmpty()) {
            for (ReportSort sort : query.sorts()) {
                if (sort == null || sort.column() == null || sort.column().trim().isEmpty()) continue;
                String tAlias = (sort.tableAlias() != null && !sort.tableAlias().trim().isEmpty()) ? sort.tableAlias().trim() : baseAlias;
                TableRef tRef = tableMap.get(tAlias);
                if (tRef == null) continue;
                validateColumnExists(tRef.schema(), tRef.name(), sort.column());

                // En SQL Server con DISTINCT, la columna del ORDER BY debe estar presente en el SELECT
                if (isDistinct) {
                    boolean isInSelect = validatedColumns.stream().anyMatch(c ->
                            c.tableAlias().equalsIgnoreCase(tAlias) && c.column().equalsIgnoreCase(sort.column())
                    );
                    if (!isInSelect) {
                        continue;
                    }
                }

                String dir = ("DESC".equalsIgnoreCase(sort.direction())) ? "DESC" : "ASC";
                if (orderByClause.length() > 0) orderByClause.append(", ");
                orderByClause.append("[").append(tAlias).append("].[").append(sort.column().replace("]", "]]")).append("] ").append(dir);
            }
        }
        if (orderByClause.length() == 0) {
            if (isDistinct && !validatedColumns.isEmpty()) {
                ReportColumn firstCol = validatedColumns.get(0);
                orderByClause.append("[").append(firstCol.tableAlias()).append("].[").append(firstCol.column().replace("]", "]]")).append("] ASC");
            } else {
                orderByClause.append("(SELECT NULL)");
            }
        }

        // Armar consultas finales
        String whereSql = whereClause.length() > 0 ? "\nWHERE " + whereClause : "";
        
        String countSql;
        if (isDistinct) {
            StringBuilder distinctCountCols = new StringBuilder();
            for (int i = 0; i < validatedColumns.size(); i++) {
                if (i > 0) distinctCountCols.append(", ");
                ReportColumn rc = validatedColumns.get(i);
                distinctCountCols.append("[").append(rc.tableAlias()).append("].[").append(rc.column().replace("]", "]]")).append("] AS [c").append(i).append("]");
            }
            countSql = "SELECT COUNT(*)\nFROM (\n  SELECT DISTINCT " + distinctCountCols + "\n  FROM " + fromClause + whereSql + "\n) AS [__distinct_count_wrapper]";
        } else {
            countSql = "SELECT COUNT(*)\nFROM " + fromClause + whereSql;
        }

        String selectAllSql = "SELECT " + distinctKeyword + "\n  " + selectClause + "\nFROM " + fromClause + whereSql + "\nORDER BY " + orderByClause;

        int limit = (query.limit() != null && query.limit() > 0) ? Math.min(query.limit(), 100) : 15;
        int offset = (query.offset() != null && query.offset() >= 0) ? query.offset() : 0;

        String selectPagedSql = selectAllSql + "\nOFFSET ? ROWS FETCH NEXT ? ROWS ONLY";

        List<Object> selectPagedParams = new ArrayList<>(filterParams);
        selectPagedParams.add(offset);
        selectPagedParams.add(limit);

        List<Object> countParams = new ArrayList<>(filterParams);
        List<Object> selectAllParams = new ArrayList<>(filterParams);

        // Preview descriptivo de la consulta SQL
        String prettySqlPreview = selectAllSql;

        return new BuiltReportQuery(
                countSql,
                selectPagedSql,
                selectAllSql,
                countParams,
                selectPagedParams,
                selectAllParams,
                validatedColumns,
                prettySqlPreview
        );
    }

    private void validateIdentifier(String identifier, String desc) {
        if (identifier == null || !identifier.matches("^[a-zA-Z0-9_]{1,30}$")) {
            throw new SecurityException("Identificador no válido para " + desc + ": '" + identifier + "'");
        }
    }

    private void validateColumnExists(String schema, String tableName, String column) {
        List<ColumnInfo> cols = self.getColumns(schema, tableName);
        boolean exists = cols.stream().anyMatch(c -> c.name().equalsIgnoreCase(column));
        if (!exists) {
            throw new SecurityException("Columna '" + column + "' no existe en tabla " + schema + "." + tableName);
        }
    }

    /**
     * Ejecuta la vista previa paginada de un reporte personalizado multi-tabla.
     */
    public CustomReportResult executeCustomReportPreview(CustomReportQuery query) {
        long startTime = System.currentTimeMillis();
        BuiltReportQuery built = buildReportSql(query);

        Long total = jdbcTemplate.queryForObject(built.countSql(), Long.class, built.countParams().toArray());
        long totalRows = total != null ? total : 0L;

        List<Map<String, Object>> data = jdbcTemplate.queryForList(built.selectPagedSql(), built.selectPagedParams().toArray());
        long elapsed = System.currentTimeMillis() - startTime;

        int limit = (query.limit() != null && query.limit() > 0) ? Math.min(query.limit(), 100) : 15;
        int offset = (query.offset() != null && query.offset() >= 0) ? query.offset() : 0;
        int currentPage = (limit == 0) ? 1 : (offset / limit) + 1;
        int totalPages = (limit == 0) ? 1 : (int) Math.ceil((double) totalRows / limit);

        return new CustomReportResult(
                data,
                totalRows,
                limit,
                offset,
                currentPage,
                totalPages,
                elapsed,
                built.prettySqlPreview(),
                built.validatedColumns()
        );
    }

    /**
     * Exporta el reporte personalizado multi-tabla a un archivo Excel (.xlsx) por streaming.
     */
    public void exportCustomReportToExcel(CustomReportQuery query, java.io.OutputStream outputStream) throws Exception {
        if (!exportSemaphore.tryAcquire()) {
            throw new TooManyExportsException(
                    "Hay demasiadas exportaciones en curso (máximo " + maxConcurrentExports + " en paralelo permitido). " +
                    "Intenta nuevamente en unos segundos.");
        }
        try {
            BuiltReportQuery built = buildReportSql(query);

            Long total = jdbcTemplate.queryForObject(built.countSql(), Long.class, built.countParams().toArray());
            long totalRows = total != null ? total : 0L;
            if (totalRows > maxExportRows) {
                throw new IllegalArgumentException(
                        "El reporte contiene " + totalRows + " filas, por encima del máximo exportable de "
                        + maxExportRows + ". Aplique filtros más específicos o contacte al administrador.");
            }

            try (org.apache.poi.xssf.streaming.SXSSFWorkbook workbook = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100)) {
                workbook.setCompressTempFiles(true);
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Reporte");

                // Encabezados
                org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
                List<ReportColumn> cols = built.validatedColumns();
                for (int i = 0; i < cols.size(); i++) {
                    headerRow.createCell(i).setCellValue(cols.get(i).label());
                }

                int[] nextRowIndex = {1};
                jdbcTemplate.query(built.selectAllSql(), rs -> {
                    org.apache.poi.ss.usermodel.Row row = sheet.createRow(nextRowIndex[0]++);
                    for (int i = 1; i <= cols.size(); i++) {
                        Object val = rs.getObject(i);
                        org.apache.poi.ss.usermodel.Cell cell = row.createCell(i - 1);
                        if (val == null) {
                            cell.setBlank();
                        } else if (val instanceof Number number) {
                            cell.setCellValue(number.doubleValue());
                        } else {
                            cell.setCellValue(sanitizeForSpreadsheet(val.toString()));
                        }
                    }
                }, built.selectAllParams().toArray());

                workbook.write(outputStream);
                workbook.dispose();
            }
            outputStream.flush();
        } finally {
            exportSemaphore.release();
        }
    }
}
