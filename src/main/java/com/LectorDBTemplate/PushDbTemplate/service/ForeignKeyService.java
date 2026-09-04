package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.SchemaMetadataService.ColumnInfo;
import com.LectorDBTemplate.PushDbTemplate.service.dialect.DbDialect;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detección y resolución de relaciones de Foreign Key, tanto físicas (DatabaseMetaData) como
 * virtuales/personalizadas con soporte multi-motor universal.
 */
@Service
public class ForeignKeyService {

    private static final Logger log = LoggerFactory.getLogger(ForeignKeyService.class);
    private final DynamicDataSourceService dynamicDataSourceService;
    private final SchemaMetadataService schemaMetadataService;
    private final org.springframework.cache.CacheManager cacheManager;

    @Autowired
    @Lazy
    private ForeignKeyService self;

    private static final String LEGACY_CUSTOM_FKS_FILE = "custom-fks.json";
    private static final String CUSTOM_FKS_TABLE = "push_custom_fks";

    // Almacenamiento en memoria para respaldar custom FKs en bases de datos sin tabla push_custom_fks
    private final Map<String, List<ForeignKeyInfo>> inMemoryCustomFks = new ConcurrentHashMap<>();

    public ForeignKeyService(DynamicDataSourceService dynamicDataSourceService,
                             SchemaMetadataService schemaMetadataService,
                             org.springframework.cache.CacheManager cacheManager) {
        this.dynamicDataSourceService = dynamicDataSourceService;
        this.schemaMetadataService = schemaMetadataService;
        this.cacheManager = cacheManager;
    }

    private JdbcTemplate getJdbcTemplate() {
        return dynamicDataSourceService.getJdbcTemplate();
    }

    private DbDialect getDialect() {
        return dynamicDataSourceService.getDialect();
    }

    @PostConstruct
    public void initCustomFksStorage() {
        migrateLegacyJsonFileIfNeeded();
    }

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
            if (enabled == null) enabled = true;
            if (custom == null) custom = false;
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

    private void migrateLegacyJsonFileIfNeeded() {
        File legacyFile = new File(LEGACY_CUSTOM_FKS_FILE);
        if (!legacyFile.exists()) return;
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, List<ForeignKeyInfo>> legacy = mapper.readValue(legacyFile, new TypeReference<Map<String, List<ForeignKeyInfo>>>() {});
            for (Map.Entry<String, List<ForeignKeyInfo>> entry : legacy.entrySet()) {
                String[] parts = entry.getKey().split("\\.", 2);
                if (parts.length == 2) {
                    inMemoryCustomFks.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                }
            }
        } catch (Exception e) {
            log.warn("Error al leer {} legado: {}", LEGACY_CUSTOM_FKS_FILE, e.getMessage());
        }
    }

    public synchronized void saveCustomFks(String schema, String tableName, List<ForeignKeyInfo> fks) {
        String key = ((schema != null ? schema : "dbo") + "." + tableName).toLowerCase(Locale.ROOT);
        Map<String, ForeignKeyInfo> byColumn = new LinkedHashMap<>();
        if (fks != null) {
            for (ForeignKeyInfo fk : fks) {
                byColumn.put(fk.fkColumn().toLowerCase(Locale.ROOT), new ForeignKeyInfo(
                        fk.fkColumn(), fk.referencedSchema(), fk.referencedTable(), fk.referencedColumn(),
                        fk.displayColumn(), fk.filterColumn(), fk.filterValue(), fk.enabled(), true));
            }
        }

        inMemoryCustomFks.put(key, new ArrayList<>(byColumn.values()));

        // Intentamos guardar en la BD conectada si existe la tabla
        if (schemaMetadataService.isTableValid(null, CUSTOM_FKS_TABLE)) {
            try {
                JdbcTemplate jdbc = getJdbcTemplate();
                jdbc.update("DELETE FROM " + CUSTOM_FKS_TABLE + " WHERE schema_name = ? AND table_name = ?", schema, tableName);
                for (ForeignKeyInfo fk : byColumn.values()) {
                    jdbc.update(
                            "INSERT INTO " + CUSTOM_FKS_TABLE +
                            " (schema_name, table_name, fk_column, referenced_schema, referenced_table, referenced_column, display_column, filter_column, filter_value, enabled)" +
                            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            schema, tableName, fk.fkColumn(), fk.referencedSchema(), fk.referencedTable(), fk.referencedColumn(),
                            fk.displayColumn(), fk.filterColumn(), fk.filterValue(), fk.enabled() ? 1 : 0);
                }
            } catch (Exception e) {
                log.debug("Persistencia local en memoria para {}: {}", key, e.getMessage());
            }
        }

        evictCacheForTable(schema, tableName);
    }

    private List<ForeignKeyInfo> loadCustomFks(String schema, String tableName) {
        String key = ((schema != null ? schema : "dbo") + "." + tableName).toLowerCase(Locale.ROOT);
        if (schemaMetadataService.isTableValid(null, CUSTOM_FKS_TABLE)) {
            try {
                JdbcTemplate jdbc = getJdbcTemplate();
                String sql = "SELECT fk_column, referenced_schema, referenced_table, referenced_column, " +
                        "display_column, filter_column, filter_value, enabled " +
                        "FROM " + CUSTOM_FKS_TABLE + " WHERE schema_name = ? AND table_name = ?";
                List<ForeignKeyInfo> fromDb = jdbc.query(sql, (rs, rowNum) -> new ForeignKeyInfo(
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
                if (fromDb != null && !fromDb.isEmpty()) {
                    return fromDb;
                }
            } catch (Exception ignored) {}
        }

        return inMemoryCustomFks.getOrDefault(key, Collections.emptyList());
    }

    private record OwnedCustomFk(String ownerSchema, String ownerTable, ForeignKeyInfo fk) {}

    private List<OwnedCustomFk> loadCustomFksReferencing(String referencedSchema, String referencedTable) {
        List<OwnedCustomFk> result = new ArrayList<>();
        if (schemaMetadataService.isTableValid(null, CUSTOM_FKS_TABLE)) {
            try {
                JdbcTemplate jdbc = getJdbcTemplate();
                String sql = "SELECT schema_name, table_name, fk_column, referenced_column, " +
                        "display_column, filter_column, filter_value, enabled " +
                        "FROM " + CUSTOM_FKS_TABLE + " WHERE referenced_schema = ? AND referenced_table = ?";
                List<OwnedCustomFk> fromDb = jdbc.query(sql, (rs, rowNum) -> new OwnedCustomFk(
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
                if (fromDb != null && !fromDb.isEmpty()) {
                    return fromDb;
                }
            } catch (Exception ignored) {}
        }

        for (Map.Entry<String, List<ForeignKeyInfo>> entry : inMemoryCustomFks.entrySet()) {
            String[] parts = entry.getKey().split("\\.", 2);
            String ownerSchema = parts.length == 2 ? parts[0] : "dbo";
            String ownerTable = parts.length == 2 ? parts[1] : parts[0];
            for (ForeignKeyInfo fk : entry.getValue()) {
                if (fk.referencedTable().equalsIgnoreCase(referencedTable) &&
                   (referencedSchema == null || fk.referencedSchema() == null || fk.referencedSchema().equalsIgnoreCase(referencedSchema))) {
                    result.add(new OwnedCustomFk(ownerSchema, ownerTable, fk));
                }
            }
        }
        return result;
    }

    public void evictCacheForTable(String schema, String tableName) {
        String cacheKey = schema + "." + tableName;
        org.springframework.cache.Cache fkCache = cacheManager.getCache("foreignKeys");
        if (fkCache != null) fkCache.evict(cacheKey);
        org.springframework.cache.Cache colCache = cacheManager.getCache("columns");
        if (colCache != null) colCache.evict(cacheKey);
        org.springframework.cache.Cache displayCache = cacheManager.getCache("fkDisplayColumn");
        if (displayCache != null) displayCache.clear();
    }

    private static final List<String> DISPLAY_COLUMN_PRIORITY = List.of(
            "nombre", "name", "descripcion", "description", "titulo", "title",
            "razon_social", "label", "etiqueta", "email"
    );
    private static final Set<String> TEXT_TYPE_NAMES = Set.of(
            "VARCHAR", "NVARCHAR", "CHAR", "NCHAR", "TEXT", "NTEXT", "VARCHAR2", "CLOB"
    );

    @Cacheable(value = "foreignKeys", key = "#schema + '.' + #tableName")
    public List<ForeignKeyInfo> getForeignKeys(String schema, String tableName) {
        if (!schemaMetadataService.isTableValid(schema, tableName)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + tableName + ")");
        }

        List<ForeignKeyInfo> nativeFks = new ArrayList<>();
        Map<String, List<Object[]>> byConstraint = new LinkedHashMap<>();
        try (Connection conn = Objects.requireNonNull(dynamicDataSourceService.getDataSource()).getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schemaPattern = (schema != null && !schema.isBlank() && !schema.equalsIgnoreCase("dbo") && !schema.equalsIgnoreCase(catalog))
                    ? schema : null;

            try (ResultSet rs = metaData.getImportedKeys(catalog, schemaPattern, tableName)) {
                while (rs.next()) {
                    String fkName = rs.getString("FK_NAME");
                    String key = (fkName != null) ? fkName : UUID.randomUUID().toString();
                    byConstraint.computeIfAbsent(key, k -> new ArrayList<>()).add(new Object[]{
                            rs.getString("FKCOLUMN_NAME"),
                            rs.getString("PKTABLE_SCHEM") != null ? rs.getString("PKTABLE_SCHEM") : rs.getString("PKTABLE_CAT"),
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

        List<ForeignKeyInfo> merged = new ArrayList<>(nativeFks);
        for (ForeignKeyInfo cfk : loadCustomFks(schema, tableName)) {
            merged.removeIf(nf -> nf.fkColumn().equalsIgnoreCase(cfk.fkColumn()));
            if (cfk.enabled()) {
                merged.add(cfk);
            }
        }

        return merged;
    }

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

    public ForeignKeyResolution resolveForeignKeys(String schema, String tableName, List<Map<String, Object>> rows) {
        if (!schemaMetadataService.isTableValid(schema, tableName)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + tableName + ")");
        }

        List<ForeignKeyColumnInfo> columnsInfo = new ArrayList<>();
        Map<String, List<FkCellResolution>> resolutions = new LinkedHashMap<>();
        DbDialect dialect = getDialect();

        if (rows.isEmpty()) {
            return new ForeignKeyResolution(columnsInfo, resolutions);
        }

        for (ForeignKeyInfo fk : self.getForeignKeys(schema, tableName)) {
            if (rows.stream().noneMatch(r -> r.containsKey(fk.fkColumn()))) {
                continue;
            }

            if (!schemaMetadataService.isTableValid(fk.referencedSchema(), fk.referencedTable())) {
                List<FkCellResolution> cellResults = new ArrayList<>(rows.size());
                for (Map<String, Object> row : rows) {
                    cellResults.add(new FkCellResolution(FkStatus.ORPHAN, "[Tabla no encontrada]"));
                }
                resolutions.put(fk.fkColumn(), cellResults);
                columnsInfo.add(new ForeignKeyColumnInfo(
                    fk.fkColumn(), fk.referencedSchema(), fk.referencedTable(), fk.referencedColumn(),
                    fk.referencedColumn(), fk.filterColumn(), fk.filterValue(), false, fk.custom()
                ));
                continue;
            }

            String displayColumn = fk.referencedColumn();
            if (fk.displayColumn() != null && !fk.displayColumn().trim().isEmpty()) {
                displayColumn = fk.displayColumn();
            } else {
                try {
                    displayColumn = self.resolveDisplayColumn(fk.referencedSchema(), fk.referencedTable(), fk.referencedColumn());
                } catch (Exception e) {
                    log.warn("No se pudo obtener displayColumn para relación en {}.{}: {}", schema, tableName, e.getMessage());
                }
            }

            if (fk.enabled()) {
                List<Object> distinctValues = rows.stream()
                        .map(r -> r.get(fk.fkColumn()))
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();

                Map<String, String> lookup = new HashMap<>();
                boolean querySuccess = true;

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
                            ? SqlSafe.buildSafeColumnList(dialect, List.of(fk.filterColumn())) + " = ?"
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
                                    if (sb.length() > 0) sb.append(" - ");
                                    sb.append(val.toString().trim());
                                }
                            }
                            lookup.put(String.valueOf(key), sb.length() > 0 ? sb.toString() : null);
                        };

                        SqlSafe.queryInBatches(
                                getJdbcTemplate(),
                                SqlSafe.buildSafeColumnList(dialect, selectCols),
                                SqlSafe.buildSafeTableName(dialect, fk.referencedSchema(), fk.referencedTable()),
                                SqlSafe.buildSafeColumnList(dialect, List.of(fk.referencedColumn())),
                                distinctValues, extraCondition, fk.filterValue(),
                                handler
                        );
                    } catch (Exception e) {
                        log.error("Error al consultar lote de relación FK en {}.{} columna {}: {}",
                            schema, tableName, fk.fkColumn(), e.getMessage());
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

    public List<SuggestedJoin> suggestJoinsForTable(String schema, String table) {
        if (!schemaMetadataService.isTableValid(schema, table)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + table + ")");
        }
        List<SuggestedJoin> suggestions = new ArrayList<>();
        Set<String> seen = new HashSet<>();

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

        try {
            Set<String> ownerColumnsWithOverride = new HashSet<>();
            for (OwnedCustomFk owned : loadCustomFksReferencing(schema, table)) {
                String ownerKey = owned.ownerSchema().toLowerCase(Locale.ROOT) + "." + owned.ownerTable().toLowerCase(Locale.ROOT) + "." + owned.fk().fkColumn().toLowerCase(Locale.ROOT);
                ownerColumnsWithOverride.add(ownerKey);
                if (!owned.fk().enabled() || !schemaMetadataService.isTableValid(owned.ownerSchema(), owned.ownerTable())) {
                    continue;
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

            try (Connection conn = Objects.requireNonNull(dynamicDataSourceService.getDataSource()).getConnection()) {
                DatabaseMetaData metaData = conn.getMetaData();
                String catalog = conn.getCatalog();
                String schemaPattern = (schema != null && !schema.isBlank() && !schema.equalsIgnoreCase("dbo") && !schema.equalsIgnoreCase(catalog))
                        ? schema : null;

                Map<String, List<Object[]>> byConstraint = new LinkedHashMap<>();
                try (ResultSet rs = metaData.getExportedKeys(catalog, schemaPattern, table)) {
                    while (rs.next()) {
                        String fkName = rs.getString("FK_NAME");
                        String key = (fkName != null) ? fkName : UUID.randomUUID().toString();
                        byConstraint.computeIfAbsent(key, k -> new ArrayList<>()).add(new Object[]{
                                rs.getString("FKTABLE_SCHEM") != null ? rs.getString("FKTABLE_SCHEM") : rs.getString("FKTABLE_CAT"),
                                rs.getString("FKTABLE_NAME"),
                                rs.getString("FKCOLUMN_NAME"),
                                rs.getString("PKCOLUMN_NAME")
                        });
                    }
                }
                for (List<Object[]> cols : byConstraint.values()) {
                    if (cols.size() != 1) continue;
                    Object[] c = cols.get(0);
                    String ownerSchema = (String) c[0];
                    String ownerTable = (String) c[1];
                    String fkColumn = (String) c[2];
                    String referencedColumn = (String) c[3];
                    if (!schemaMetadataService.isTableValid(ownerSchema, ownerTable)) continue;

                    String ownerKey = (ownerSchema != null ? ownerSchema.toLowerCase(Locale.ROOT) : "") + "." + ownerTable.toLowerCase(Locale.ROOT) + "." + fkColumn.toLowerCase(Locale.ROOT);
                    if (ownerColumnsWithOverride.contains(ownerKey)) continue;

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
