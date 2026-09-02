package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.SchemaMetadataService.ColumnInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Detección y resolución de relaciones de Foreign Key, tanto físicas (DatabaseMetaData) como
 * virtuales/personalizadas (persistidas en dbo.push_custom_fks). Extraído de la antigua
 * DatabaseService (ver auditoría, hallazgo F6).
 */
@Service
public class ForeignKeyService {

    private static final Logger log = LoggerFactory.getLogger(ForeignKeyService.class);
    private final JdbcTemplate jdbcTemplate;
    private final SchemaMetadataService schemaMetadataService;
    private final org.springframework.cache.CacheManager cacheManager;

    // Ver comentario equivalente en SchemaMetadataService: acotado a los métodos @Cacheable
    // de esta clase (getForeignKeys, resolveDisplayColumn) que se auto-invocan desde
    // resolveForeignKeys/suggestJoinsForTable.
    @Autowired
    @Lazy
    private ForeignKeyService self;

    // Nombre del archivo plano legado: las FKs personalizadas ahora se persisten en la
    // tabla dbo.push_custom_fks para sobrevivir a reinicios y múltiples instancias. Este
    // archivo solo se lee una vez, al arrancar, para migrar configuraciones ya existentes.
    private static final String LEGACY_CUSTOM_FKS_FILE = "custom-fks.json";
    private static final String CUSTOM_FKS_TABLE = "dbo.push_custom_fks";

    public ForeignKeyService(JdbcTemplate jdbcTemplate, SchemaMetadataService schemaMetadataService,
                              org.springframework.cache.CacheManager cacheManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.schemaMetadataService = schemaMetadataService;
        this.cacheManager = cacheManager;
    }

    // dbo.push_custom_fks ya no se crea aquí con DDL condicional: la gestiona Flyway (ver
    // src/main/resources/db/migration/V1__create_push_tables.sql), que corre automáticamente
    // antes de que este bean se inicialice. Solo queda la migración de datos, de una sola
    // vez, del archivo JSON legado.
    @PostConstruct
    public void initCustomFksStorage() {
        migrateLegacyJsonFileIfNeeded();
    }

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

    // FK personalizada vista "desde el otro lado": qué tabla.columna la declara (ownerSchema/
    // ownerTable), usado por suggestJoinsForTable() para hallar relaciones virtuales entrantes
    // sin tener que recorrer loadCustomFks() de cada tabla del catálogo.
    private record OwnedCustomFk(String ownerSchema, String ownerTable, ForeignKeyInfo fk) {}

    private List<OwnedCustomFk> loadCustomFksReferencing(String referencedSchema, String referencedTable) {
        String sql = "SELECT schema_name, table_name, fk_column, referenced_column, " +
                "display_column, filter_column, filter_value, enabled " +
                "FROM " + CUSTOM_FKS_TABLE + " WHERE referenced_schema = ? AND referenced_table = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new OwnedCustomFk(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                new ForeignKeyInfo(
                        rs.getString("fk_column"),
                        referencedSchema,
                        referencedTable,
                        rs.getString("referenced_column"),
                        rs.getString("display_column"),
                        rs.getString("filter_column"),
                        rs.getString("filter_value"),
                        rs.getBoolean("enabled"),
                        true
                )
        ), referencedSchema, referencedTable);
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
        if (!schemaMetadataService.isTableValid(schema, tableName)) {
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
        List<ColumnInfo> cols = schemaMetadataService.getColumns(schema, tableName);

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
     * contrato de SchemaMetadataService.getTableData().
     */
    public ForeignKeyResolution resolveForeignKeys(String schema, String tableName, List<Map<String, Object>> rows) {
        if (!schemaMetadataService.isTableValid(schema, tableName)) {
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
            if (!schemaMetadataService.isTableValid(fk.referencedSchema(), fk.referencedTable())) {
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

                    String extraCondition = (fk.filterColumn() != null && !fk.filterColumn().trim().isEmpty() && fk.filterValue() != null)
                            ? SqlSafe.buildSafeColumnList(List.of(fk.filterColumn())) + " = ?"
                            : null;

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
                        SqlSafe.queryInBatches(
                                jdbcTemplate,
                                SqlSafe.buildSafeColumnList(selectCols),
                                SqlSafe.buildSafeTableName(fk.referencedSchema(), fk.referencedTable()),
                                SqlSafe.buildSafeColumnList(List.of(fk.referencedColumn())),
                                distinctValues, extraCondition, fk.filterValue(),
                                handler
                        );
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
     * Detecta y sugiere cruces (Joins) basados en las relaciones de Foreign Keys (físicas y virtuales)
     * salientes y entrantes para la tabla indicada.
     */
    public List<SuggestedJoin> suggestJoinsForTable(String schema, String table) {
        if (!schemaMetadataService.isTableValid(schema, table)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + table + ")");
        }
        List<SuggestedJoin> suggestions = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. Claves foráneas salientes (la tabla seleccionada apunta a una tabla referenciada)
        try {
            List<ForeignKeyInfo> fks = self.getForeignKeys(schema, table);
            for (ForeignKeyInfo fk : fks) {
                if (fk.enabled() && schemaMetadataService.isTableValid(fk.referencedSchema(), fk.referencedTable())) {
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

        // 2. Claves foráneas entrantes (otras tablas apuntan a esta tabla).
        // Resuelve en O(1): una consulta dirigida a push_custom_fks para las relaciones
        // virtuales entrantes, y una sola llamada a DatabaseMetaData.getExportedKeys() —el
        // reverso de getImportedKeys()— para las FKs nativas entrantes. Antes recorría TODAS
        // las tablas del catálogo llamando a getForeignKeys() por cada una (ver auditoría F3).
        try {
            Set<String> ownerColumnsWithOverride = new HashSet<>();

            // 2a. Relaciones virtuales (o overrides de nativas) que apuntan a esta tabla.
            for (OwnedCustomFk owned : loadCustomFksReferencing(schema, table)) {
                String ownerKey = owned.ownerSchema().toLowerCase(Locale.ROOT) + "." + owned.ownerTable().toLowerCase(Locale.ROOT) + "." + owned.fk().fkColumn().toLowerCase(Locale.ROOT);
                ownerColumnsWithOverride.add(ownerKey);
                if (!owned.fk().enabled() || !schemaMetadataService.isTableValid(owned.ownerSchema(), owned.ownerTable())) {
                    continue; // override deshabilitado: se omite, igual que hacía getForeignKeys() antes
                }
                String sig = owned.ownerSchema() + "." + owned.ownerTable() + "." + owned.fk().fkColumn() + "->" + schema + "." + table + "." + owned.fk().referencedColumn();
                if (seen.add(sig)) {
                    suggestions.add(new SuggestedJoin(
                            "LEFT",
                            schema, table, owned.fk().referencedColumn(),
                            owned.ownerSchema(), owned.ownerTable(), owned.fk().fkColumn(),
                            "Cruzar con [" + owned.ownerSchema() + "].[" + owned.ownerTable() + "] vía " + owned.fk().referencedColumn() + " ← " + owned.fk().fkColumn()
                    ));
                }
            }

            // 2b. FKs nativas entrantes vía metadata del motor.
            try (Connection conn = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
                DatabaseMetaData metaData = conn.getMetaData();
                Map<String, List<Object[]>> byConstraint = new LinkedHashMap<>();
                try (ResultSet rs = metaData.getExportedKeys(null, schema, table)) {
                    while (rs.next()) {
                        String fkName = rs.getString("FK_NAME");
                        String key = (fkName != null) ? fkName : UUID.randomUUID().toString();
                        byConstraint.computeIfAbsent(key, k -> new ArrayList<>()).add(new Object[]{
                                rs.getString("FKTABLE_SCHEM"), rs.getString("FKTABLE_NAME"),
                                rs.getString("FKCOLUMN_NAME"), rs.getString("PKCOLUMN_NAME")
                        });
                    }
                }
                for (List<Object[]> cols : byConstraint.values()) {
                    if (cols.size() != 1) {
                        continue; // FKs compuestas fuera de alcance, igual que en getForeignKeys()
                    }
                    Object[] c = cols.get(0);
                    String ownerSchema = (String) c[0];
                    String ownerTable = (String) c[1];
                    String fkColumn = (String) c[2];
                    String referencedColumn = (String) c[3];
                    if (!schemaMetadataService.isTableValid(ownerSchema, ownerTable)) {
                        continue;
                    }

                    String ownerKey = ownerSchema.toLowerCase(Locale.ROOT) + "." + ownerTable.toLowerCase(Locale.ROOT) + "." + fkColumn.toLowerCase(Locale.ROOT);
                    if (ownerColumnsWithOverride.contains(ownerKey)) {
                        continue; // esa columna tiene una configuración personalizada; ya se resolvió (o suprimió) en 2a
                    }

                    String sig = ownerSchema + "." + ownerTable + "." + fkColumn + "->" + schema + "." + table + "." + referencedColumn;
                    if (seen.add(sig)) {
                        suggestions.add(new SuggestedJoin(
                                "LEFT",
                                schema, table, referencedColumn,
                                ownerSchema, ownerTable, fkColumn,
                                "Cruzar con [" + ownerSchema + "].[" + ownerTable + "] vía " + referencedColumn + " ← " + fkColumn
                        ));
                    }
                }
            } catch (SQLException e) {
                log.warn("Error al recuperar FKs nativas entrantes de {}.{}: {}", schema, table, e.getMessage());
            }
        } catch (Exception e) {
            log.warn("Error al buscar FKs entrantes para {}.{}: {}", schema, table, e.getMessage());
        }

        return suggestions;
    }
}
