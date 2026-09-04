package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.dialect.DbDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Acceso seguro y whitelisted a la estructura y datos del esquema activo en cualquier
 * motor de base de datos soportado (SQL Server, MySQL, PostgreSQL, Oracle, SQLite, MariaDB).
 * Se adapta automáticamente al dialecto del motor seleccionado en DynamicDataSourceService.
 */
@Service
public class SchemaMetadataService {

    private static final Logger log = LoggerFactory.getLogger(SchemaMetadataService.class);
    private final DynamicDataSourceService dynamicDataSourceService;

    @Autowired
    @Lazy
    private SchemaMetadataService self;

    public SchemaMetadataService(DynamicDataSourceService dynamicDataSourceService) {
        this.dynamicDataSourceService = dynamicDataSourceService;
    }

    private JdbcTemplate getJdbcTemplate() {
        return dynamicDataSourceService.getJdbcTemplate();
    }

    private DbDialect getDialect() {
        return dynamicDataSourceService.getDialect();
    }

    public record TableInfo(String schema, String name) {}
    public record ColumnInfo(String name, String type, int size, boolean nullable) {}

    /**
     * Obtiene la lista de tablas disponibles del esquema de manera segura usando DatabaseMetaData
     * y filtrando esquemas de sistema según el dialecto activo.
     */
    @Cacheable("tables")
    public List<TableInfo> getTables() {
        List<TableInfo> tables = new ArrayList<>();
        DbDialect dialect = getDialect();

        try (Connection conn = Objects.requireNonNull(dynamicDataSourceService.getDataSource()).getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            String catalog = conn.getCatalog();

            try (ResultSet rs = metaData.getTables(catalog, null, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    String name = rs.getString("TABLE_NAME");

                    if (schema == null || schema.isBlank()) {
                        String cat = rs.getString("TABLE_CAT");
                        schema = (cat != null && !cat.isBlank()) ? cat : "dbo";
                    }

                    if (!dialect.isSystemTable(schema, name)) {
                        tables.add(new TableInfo(schema, name));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Error al recuperar metadatos de las tablas para el motor {}", dialect.getDisplayName(), e);
        }

        tables.sort(Comparator.comparing(TableInfo::schema, Comparator.nullsFirst(String::compareToIgnoreCase))
                .thenComparing(TableInfo::name, String::compareToIgnoreCase));
        return tables;
    }

    /**
     * Valida si un esquema y tabla existen en la base de datos conectada (Whitelist Validation).
     */
    public boolean isTableValid(String schema, String tableName) {
        if (tableName == null) {
            return false;
        }
        return self.getTables().stream().anyMatch(t ->
            (schema == null || t.schema() == null || t.schema().equalsIgnoreCase(schema)) &&
            t.name().equalsIgnoreCase(tableName)
        );
    }

    /**
     * Obtiene la definición de columnas de una tabla seleccionada usando DatabaseMetaData.
     */
    @Cacheable(value = "columns", key = "#schema + '.' + #tableName")
    public List<ColumnInfo> getColumns(String schema, String tableName) {
        if (!isTableValid(schema, tableName)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + tableName + ")");
        }

        List<ColumnInfo> columns = new ArrayList<>();
        try (Connection conn = Objects.requireNonNull(dynamicDataSourceService.getDataSource()).getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schemaPattern = (schema != null && !schema.isBlank() && !schema.equalsIgnoreCase("dbo") && !schema.equalsIgnoreCase(catalog))
                    ? schema : null;

            try (ResultSet rs = metaData.getColumns(catalog, schemaPattern, tableName, "%")) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    String dataType = rs.getString("TYPE_NAME");
                    int columnSize = rs.getInt("COLUMN_SIZE");
                    String isNullable = rs.getString("IS_NULLABLE");
                    columns.add(new ColumnInfo(columnName, dataType, columnSize, "YES".equalsIgnoreCase(isNullable)));
                }
            }
            // Fallback si no trajo columnas por catalog/schema mismatch
            if (columns.isEmpty()) {
                try (ResultSet rs = metaData.getColumns(null, null, tableName, "%")) {
                    while (rs.next()) {
                        String columnName = rs.getString("COLUMN_NAME");
                        String dataType = rs.getString("TYPE_NAME");
                        int columnSize = rs.getInt("COLUMN_SIZE");
                        String isNullable = rs.getString("IS_NULLABLE");
                        columns.add(new ColumnInfo(columnName, dataType, columnSize, "YES".equalsIgnoreCase(isNullable)));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Error al recuperar columnas para la tabla {}.{}", schema, tableName, e);
        }
        return columns;
    }

    List<String> resolveValidColumns(String schema, String tableName, List<String> requestedColumns) {
        List<String> validColNames = self.getColumns(schema, tableName).stream().map(ColumnInfo::name).toList();
        for (String col : requestedColumns) {
            boolean isValid = validColNames.stream().anyMatch(name -> name.equalsIgnoreCase(col));
            if (!isValid) {
                throw new SecurityException("Acceso denegado: Intento de inyección detectado en columna '" + col + "'");
            }
        }
        return requestedColumns;
    }

    String buildFilterCondition(
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

        List<ColumnInfo> tableCols = self.getColumns(schema, tableName);
        boolean isColValid = tableCols.stream().anyMatch(c -> c.name().equalsIgnoreCase(filterColumn));
        if (!isColValid) {
            throw new SecurityException("Acceso denegado: Nombre de columna no válido para el filtro: '" + filterColumn + "'");
        }

        if (filterOperator == null || filterOperator.trim().isEmpty()) {
            return null;
        }
        String operator = filterOperator.trim().toUpperCase(Locale.ROOT);
        Set<String> allowedOperators = Set.of("=", "LIKE", ">", "<", ">=", "<=", "IS NULL", "IS NOT NULL", "BETWEEN");
        if (!allowedOperators.contains(operator)) {
            throw new IllegalArgumentException("Operador de filtro no permitido: '" + filterOperator + "'");
        }

        DbDialect dialect = getDialect();
        String safeColName = dialect.escapeIdentifier(filterColumn);

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

    @Cacheable(value = "tableCount", key = "#schema + '.' + #tableName + '.' + #filterColumn + '.' + #filterOperator + '.' + #filterValue + '.' + #filterValue2")
    public long getTableCount(String schema, String tableName, String filterColumn, String filterOperator, String filterValue, String filterValue2) {
        if (!isTableValid(schema, tableName)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + tableName + ")");
        }
        DbDialect dialect = getDialect();
        String safeTableName = SqlSafe.buildSafeTableName(dialect, schema, tableName);
        List<Object> params = new ArrayList<>();
        String condition = buildFilterCondition(schema, tableName, filterColumn, filterOperator, filterValue, filterValue2, params);

        String sql = "SELECT COUNT(*) FROM " + safeTableName;
        if (condition != null) {
            sql += " WHERE " + condition;
        }

        Long count = getJdbcTemplate().queryForObject(sql, Long.class, params.toArray());
        return count != null ? count : 0L;
    }

    public long getTableCount(String schema, String tableName, String filterColumn, String filterOperator, String filterValue) {
        return self.getTableCount(schema, tableName, filterColumn, filterOperator, filterValue, null);
    }

    public long getTableCount(String schema, String tableName) {
        return self.getTableCount(schema, tableName, null, null, null, null);
    }

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

        DbDialect dialect = getDialect();
        String safeTableName = SqlSafe.buildSafeTableName(dialect, schema, tableName);
        String columnList = (columns == null || columns.isEmpty())
                ? "*"
                : SqlSafe.buildSafeColumnList(dialect, resolveValidColumns(schema, tableName, columns));

        List<Object> queryParams = new ArrayList<>();
        String condition = buildFilterCondition(schema, tableName, filterColumn, filterOperator, filterValue, filterValue2, queryParams);

        String sql = "SELECT " + columnList + " FROM " + safeTableName;
        if (condition != null) {
            sql += " WHERE " + condition;
        }

        // Paginación adaptativa según motor de base de datos
        if (dialect.getEngineType().equals("SQLSERVER")) {
            sql += " ORDER BY (SELECT NULL)";
        }
        sql = dialect.buildPaginationSql(sql, limit, offset);
        dialect.appendPaginationParams(queryParams, limit, offset);

        log.debug("Ejecutando consulta paginada en {}.{} (Dialecto: {}) con limit={} y offset={}",
                schema, tableName, dialect.getDisplayName(), limit, offset);
        return getJdbcTemplate().queryForList(sql, queryParams.toArray());
    }

    public List<Map<String, Object>> getTableData(String schema, String tableName, int limit, int offset, List<String> columns, String filterColumn, String filterOperator, String filterValue) {
        return getTableData(schema, tableName, limit, offset, columns, filterColumn, filterOperator, filterValue, null);
    }

    public List<Map<String, Object>> getTableData(String schema, String tableName, int limit, int offset, List<String> columns) {
        return getTableData(schema, tableName, limit, offset, columns, null, null, null, null);
    }
}
