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
        migrateLegacyJsonFileIfNeeded();
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

    /**
     * Retorna el número total de filas de una tabla de manera segura.
     * Cacheado con TTL corto (ver application.yaml): evita recalcular un COUNT(*)
     * costoso en tablas grandes en cada cambio de página de la misma tabla.
     */
    @Cacheable(value = "tableCount", key = "#schema + '.' + #tableName")
    public long getTableCount(String schema, String tableName) {
        if (!isTableValid(schema, tableName)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + tableName + ")");
        }
        // Escapado seguro con corchetes SQL Server
        String safeTableName = buildSafeTableName(schema, tableName);
        String sql = "SELECT COUNT(*) FROM " + safeTableName;

        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count != null ? count : 0L;
    }

    /**
     * Obtiene los datos paginados de una tabla de manera segura y optimizada.
     * Utiliza OFFSET / FETCH NEXT nativo de SQL Server. Si se solicitan columnas
     * específicas (validadas contra whitelist), proyecta solo esas en vez de SELECT *.
     */
    public List<Map<String, Object>> getTableData(String schema, String tableName, int limit, int offset, List<String> columns) {
        if (!isTableValid(schema, tableName)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + tableName + ")");
        }

        String safeTableName = buildSafeTableName(schema, tableName);
        String columnList = (columns == null || columns.isEmpty())
                ? "*"
                : buildSafeColumnList(resolveValidColumns(schema, tableName, columns));

        // SQL Server requiere un ORDER BY para usar OFFSET ... FETCH.
        // Usamos ORDER BY (SELECT NULL) como truco para ordenar por el orden físico y evitar un ordenamiento pesado.
        String sql = "SELECT " + columnList + " FROM " + safeTableName + " ORDER BY (SELECT NULL) OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";

        log.debug("Ejecutando consulta paginada en {}.{} con limit={} y offset={}", schema, tableName, limit, offset);
        return jdbcTemplate.queryForList(sql, offset, limit);
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

    /**
     * Exporta el reporte completo de una tabla (columnas elegidas) a un .xlsx real,
     * transmitiendo los datos con SXSSFWorkbook (ventana de filas en memoria + volcado a
     * disco temporal) en vez de mantener todo el libro en RAM. Comparado con el SpreadsheetML
     * (XML sin comprimir) anterior, genera archivos varias veces más livianos y rápidos de
     * transferir. Rechaza tablas que superen app.export.max-rows para evitar exfiltración
     * masiva / DoS, y limita cuántas exportaciones corren en paralelo (app.export.max-concurrent)
     * para no agotar el pool de conexiones con exportaciones largas.
     */
    public void exportTableToExcel(String schema, String tableName, List<String> selectedColumns, java.io.OutputStream outputStream) throws Exception {
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

            long totalRows = self.getTableCount(schema, tableName);
            if (totalRows > maxExportRows) {
                throw new IllegalArgumentException(
                    "La tabla contiene " + totalRows + " filas, por encima del máximo exportable de "
                        + maxExportRows + ". Aplique filtros o contacte al administrador.");
            }

            String columnsBuilder = buildSafeColumnList(validatedColumns);
            String safeTableName = buildSafeTableName(schema, tableName);
            String sql = "SELECT " + columnsBuilder + " FROM " + safeTableName;

            log.info("Iniciando exportación .xlsx de reporte completo por streaming para la tabla {}.{}", schema, tableName);

            // Cargar mapas de traducción de claves foráneas activas y válidas
            Map<String, Map<String, String>> fkLookups = new HashMap<>();
            try {
                for (ForeignKeyInfo fk : self.getForeignKeys(schema, tableName)) {
                    if (fk.enabled() && isTableValid(fk.referencedSchema(), fk.referencedTable())) {
                        String distinctSql = "SELECT DISTINCT [" + fk.fkColumn().replace("]", "]]") + "] "
                                + "FROM " + safeTableName + " WHERE [" + fk.fkColumn().replace("]", "]]") + "] IS NOT NULL";
                        
                        List<Object> distinctValues = jdbcTemplate.query(distinctSql, (rs, rowNum) -> rs.getObject(1))
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

                            List<Object> queryParams = new ArrayList<>(distinctValues);
                            if (fk.filterColumn() != null && !fk.filterColumn().trim().isEmpty() && fk.filterValue() != null) {
                                lookupSql += " AND " + buildSafeColumnList(List.of(fk.filterColumn())) + " = ?";
                                queryParams.add(fk.filterValue());
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
                                jdbcTemplate.query(lookupSql, handler, queryParams.toArray());
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
                });

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
}
