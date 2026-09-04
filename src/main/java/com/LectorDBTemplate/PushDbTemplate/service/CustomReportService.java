package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.SchemaMetadataService.ColumnInfo;
import com.LectorDBTemplate.PushDbTemplate.service.dialect.DbDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Motor de reportes personalizados multi-tabla: construcción segura de SQL dinámico (JOINs,
 * filtros, ordenamiento, DISTINCT) adaptado al dialecto del motor activo (SQL Server, MySQL, PostgreSQL, Oracle, SQLite, MariaDB)
 * y persistencia resiliente de plantillas.
 */
@Service
public class CustomReportService {

    private static final Logger log = LoggerFactory.getLogger(CustomReportService.class);
    private final DynamicDataSourceService dynamicDataSourceService;
    private final SchemaMetadataService schemaMetadataService;

    private static final String CUSTOM_REPORTS_TABLE = "push_custom_reports";
    private final Map<String, ReportTemplate> inMemoryTemplates = new ConcurrentHashMap<>();

    public CustomReportService(DynamicDataSourceService dynamicDataSourceService, SchemaMetadataService schemaMetadataService) {
        this.dynamicDataSourceService = dynamicDataSourceService;
        this.schemaMetadataService = schemaMetadataService;
    }

    private JdbcTemplate getJdbcTemplate() {
        return dynamicDataSourceService.getJdbcTemplate();
    }

    private DbDialect getDialect() {
        return dynamicDataSourceService.getDialect();
    }

    // --- Records para Reportes Personalizados Multi-Tabla (Custom Reports) ---
    public record TableRef(String schema, String name, String alias) {}
    public record JoinOn(String tableAlias, String column) {}
    public record JoinDefinition(
        String type,
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
        String operator,
        String value,
        String value2,
        String logic
    ) {}
    public record ReportSort(
        String tableAlias,
        String column,
        String direction
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

    public List<ReportTemplate> getReportTemplates() {
        if (schemaMetadataService.isTableValid(null, CUSTOM_REPORTS_TABLE)) {
            try {
                JdbcTemplate jdbc = getJdbcTemplate();
                String sql = "SELECT id, name, description, config_json, " +
                        "CAST(created_at AS VARCHAR(30)) AS created_at, " +
                        "CAST(updated_at AS VARCHAR(30)) AS updated_at " +
                        "FROM " + CUSTOM_REPORTS_TABLE + " ORDER BY updated_at DESC";
                List<ReportTemplate> list = jdbc.query(sql, (rs, rowNum) -> new ReportTemplate(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("config_json"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")
                ));
                if (list != null && !list.isEmpty()) {
                    return list;
                }
            } catch (Exception ignored) {}
        }

        List<ReportTemplate> templates = new ArrayList<>(inMemoryTemplates.values());
        templates.sort((a, b) -> Objects.compare(b.updatedAt(), a.updatedAt(), Comparator.nullsLast(String::compareTo)));
        return templates;
    }

    public ReportTemplate saveReportTemplate(ReportTemplate template) {
        if (template.name() == null || template.name().trim().isEmpty()) {
            throw new IllegalArgumentException("El nombre del reporte es obligatorio.");
        }
        String id = (template.id() != null && !template.id().trim().isEmpty())
                ? template.id().trim()
                : UUID.randomUUID().toString();

        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        ReportTemplate saved = new ReportTemplate(id, template.name().trim(), template.description(), template.configJson(), now, now);
        inMemoryTemplates.put(id, saved);

        if (schemaMetadataService.isTableValid(null, CUSTOM_REPORTS_TABLE)) {
            try {
                JdbcTemplate jdbc = getJdbcTemplate();
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + CUSTOM_REPORTS_TABLE + " WHERE id = ?", Integer.class, id);

                if (count != null && count > 0) {
                    jdbc.update(
                            "UPDATE " + CUSTOM_REPORTS_TABLE + " SET name = ?, description = ?, config_json = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                            template.name().trim(), template.description(), template.configJson(), id);
                } else {
                    jdbc.update(
                            "INSERT INTO " + CUSTOM_REPORTS_TABLE + " (id, name, description, config_json, created_at, updated_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                            id, template.name().trim(), template.description(), template.configJson());
                }
            } catch (Exception e) {
                log.debug("Persistencia local en memoria de plantilla de reporte {}: {}", id, e.getMessage());
            }
        }

        return saved;
    }

    public void deleteReportTemplate(String id) {
        if (id != null && !id.trim().isEmpty()) {
            inMemoryTemplates.remove(id.trim());
            if (schemaMetadataService.isTableValid(null, CUSTOM_REPORTS_TABLE)) {
                try {
                    getJdbcTemplate().update("DELETE FROM " + CUSTOM_REPORTS_TABLE + " WHERE id = ?", id.trim());
                } catch (Exception ignored) {}
            }
        }
    }

    private static final Set<String> ALLOWED_JOIN_TYPES = Set.of("INNER", "LEFT", "RIGHT", "FULL");
    private static final Set<String> ALLOWED_REPORT_OPERATORS = Set.of("=", "!=", "<>", ">", "<", ">=", "<=", "LIKE", "NOT LIKE", "IS NULL", "IS NOT NULL", "BETWEEN", "IN");
    private static final Set<String> ALLOWED_LOGICS = Set.of("AND", "OR");

    record BuiltReportQuery(
            String countSql,
            String selectPagedSql,
            String selectAllSql,
            List<Object> countParams,
            List<Object> selectPagedParams,
            List<Object> selectAllParams,
            List<ReportColumn> validatedColumns,
            String prettySqlPreview,
            String fromClauseSql,
            String whereSql,
            Map<String, TableRef> tableAliasMap
    ) {}

    BuiltReportQuery buildReportSql(CustomReportQuery query) {
        if (query == null || query.baseTable() == null) {
            throw new IllegalArgumentException("Se requiere una tabla base válida para construir el reporte.");
        }

        DbDialect dialect = getDialect();
        TableRef base = query.baseTable();
        if (!schemaMetadataService.isTableValid(base.schema(), base.name())) {
            throw new SecurityException("Acceso denegado: Tabla base no válida (" + base.schema() + "." + base.name() + ")");
        }

        String baseAlias = (base.alias() != null && !base.alias().trim().isEmpty()) ? base.alias().trim() : "t0";
        validateIdentifier(baseAlias, "Alias de tabla base");

        Map<String, TableRef> tableMap = new LinkedHashMap<>();
        tableMap.put(baseAlias, new TableRef(base.schema(), base.name(), baseAlias));

        StringBuilder fromClause = new StringBuilder();
        fromClause.append(SqlSafe.buildSafeTableName(dialect, base.schema(), base.name()))
                  .append(" AS ").append(dialect.escapeIdentifier(baseAlias));

        // Validar y construir Joins
        if (query.joins() != null) {
            for (JoinDefinition join : query.joins()) {
                if (join == null || join.table() == null || join.onLeft() == null || join.onRight() == null) {
                    continue;
                }
                TableRef jTable = join.table();
                if (!schemaMetadataService.isTableValid(jTable.schema(), jTable.name())) {
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
                        .append(SqlSafe.buildSafeTableName(dialect, jTable.schema(), jTable.name()))
                        .append(" AS ").append(dialect.escapeIdentifier(jAlias)).append("\n    ON ")
                        .append(dialect.escapeIdentifier(left.tableAlias())).append(".").append(dialect.escapeIdentifier(left.column()))
                        .append(" = ")
                        .append(dialect.escapeIdentifier(right.tableAlias())).append(".").append(dialect.escapeIdentifier(right.column()));
            }
        }

        // Validar y construir columnas proyectadas
        List<ReportColumn> validatedColumns = new ArrayList<>();
        StringBuilder selectClause = new StringBuilder();

        if (query.columns() == null || query.columns().isEmpty()) {
            List<ColumnInfo> baseCols = schemaMetadataService.getColumns(base.schema(), base.name());
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
            selectClause.append(dialect.escapeIdentifier(rc.tableAlias())).append(".").append(dialect.escapeIdentifier(rc.column()))
                        .append(" AS ").append(dialect.escapeIdentifier(rc.label()));
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

                String safeCol = dialect.escapeIdentifier(tAlias) + "." + dialect.escapeIdentifier(f.column());

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

                if (isDistinct) {
                    boolean isInSelect = validatedColumns.stream().anyMatch(c ->
                            c.tableAlias().equalsIgnoreCase(tAlias) && c.column().equalsIgnoreCase(sort.column())
                    );
                    if (!isInSelect) continue;
                }

                String dir = ("DESC".equalsIgnoreCase(sort.direction())) ? "DESC" : "ASC";
                if (orderByClause.length() > 0) orderByClause.append(", ");
                orderByClause.append(dialect.escapeIdentifier(tAlias)).append(".")
                             .append(dialect.escapeIdentifier(sort.column())).append(" ").append(dir);
            }
        }
        if (orderByClause.length() == 0) {
            if (isDistinct && !validatedColumns.isEmpty()) {
                ReportColumn firstCol = validatedColumns.get(0);
                orderByClause.append(dialect.escapeIdentifier(firstCol.tableAlias())).append(".")
                             .append(dialect.escapeIdentifier(firstCol.column())).append(" ASC");
            } else {
                orderByClause.append(dialect.buildDefaultOrderBy(isDistinct, List.of()));
            }
        }

        String whereSql = whereClause.length() > 0 ? "\nWHERE " + whereClause : "";

        String countSql;
        if (isDistinct) {
            StringBuilder distinctCountCols = new StringBuilder();
            for (int i = 0; i < validatedColumns.size(); i++) {
                if (i > 0) distinctCountCols.append(", ");
                ReportColumn rc = validatedColumns.get(i);
                distinctCountCols.append(dialect.escapeIdentifier(rc.tableAlias())).append(".")
                                  .append(dialect.escapeIdentifier(rc.column()))
                                  .append(" AS ").append(dialect.escapeIdentifier("c" + i));
            }
            countSql = "SELECT COUNT(*)\nFROM (\n  SELECT DISTINCT " + distinctCountCols + "\n  FROM " + fromClause + whereSql + "\n) AS " + dialect.escapeIdentifier("__distinct_count_wrapper");
        } else {
            countSql = "SELECT COUNT(*)\nFROM " + fromClause + whereSql;
        }

        String selectAllSql = "SELECT " + distinctKeyword + "\n  " + selectClause + "\nFROM " + fromClause + whereSql + "\nORDER BY " + orderByClause;

        int limit = (query.limit() != null && query.limit() > 0) ? Math.min(query.limit(), 100) : 15;
        int offset = (query.offset() != null && query.offset() >= 0) ? query.offset() : 0;

        String selectPagedSql = dialect.buildPaginationSql(selectAllSql, limit, offset);

        List<Object> selectPagedParams = new ArrayList<>(filterParams);
        dialect.appendPaginationParams(selectPagedParams, limit, offset);

        List<Object> countParams = new ArrayList<>(filterParams);
        List<Object> selectAllParams = new ArrayList<>(filterParams);

        String prettySqlPreview = selectAllSql;

        return new BuiltReportQuery(
                countSql,
                selectPagedSql,
                selectAllSql,
                countParams,
                selectPagedParams,
                selectAllParams,
                validatedColumns,
                prettySqlPreview,
                fromClause.toString(),
                whereSql,
                Map.copyOf(tableMap)
        );
    }

    private void validateIdentifier(String identifier, String desc) {
        if (identifier == null || !identifier.matches("^[a-zA-Z0-9_]{1,30}$")) {
            throw new SecurityException("Identificador no válido para " + desc + ": '" + identifier + "'");
        }
    }

    private void validateColumnExists(String schema, String tableName, String column) {
        List<ColumnInfo> cols = schemaMetadataService.getColumns(schema, tableName);
        boolean exists = cols.stream().anyMatch(c -> c.name().equalsIgnoreCase(column));
        if (!exists) {
            throw new SecurityException("Columna '" + column + "' no existe en tabla " + schema + "." + tableName);
        }
    }

    public CustomReportResult executeCustomReportPreview(CustomReportQuery query) {
        long startTime = System.currentTimeMillis();
        BuiltReportQuery built = buildReportSql(query);

        Long total = getJdbcTemplate().queryForObject(built.countSql(), Long.class, built.countParams().toArray());
        long totalRows = total != null ? total : 0L;

        List<Map<String, Object>> data = getJdbcTemplate().queryForList(built.selectPagedSql(), built.selectPagedParams().toArray());
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
                validatedColumns(built)
        );
    }

    private List<ReportColumn> validatedColumns(BuiltReportQuery built) {
        return built.validatedColumns();
    }
}
