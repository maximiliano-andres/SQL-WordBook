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
    // unless: si la lectura de metadatos falla (SQLException capturada abajo) se devuelve una
    // lista vacía. Antes esa lista vacía quedaba cacheada 10 minutos, y durante ese tiempo
    // TODOS los endpoints respondían 403 "tabla no válida" aunque la BD ya se hubiera
    // recuperado de una caída momentánea. Ahora un resultado vacío simplemente no se cachea.
    @Cacheable(value = "tables", unless = "#result == null || #result.isEmpty()")
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

    // Quita un nivel de delimitadores ([x], "x", 'x') que el cliente pueda enviar. Precompilado:
    // String.replaceAll recompila la regex en cada llamada, y esto se ejecuta varias veces por
    // request (whitelist de tabla, columnas, filtro, cada FK resuelta).
    static final java.util.regex.Pattern IDENTIFIER_QUOTES = java.util.regex.Pattern.compile("^\\[|\\]$|^\"|\"$|^'|'$");

    static String stripQuotes(String identifier) {
        return IDENTIFIER_QUOTES.matcher(identifier.trim()).replaceAll("").trim();
    }

    /**
     * Valida si un esquema y tabla existen en la base de datos conectada (Whitelist Validation).
     */
    public boolean isTableValid(String schema, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        String cleanTable = stripQuotes(tableName);
        String cleanSchema = (schema != null) ? stripQuotes(schema) : null;

        // El emparejamiento flexible de abajo acepta el esquema "dbo" como comodín (necesario
        // para motores donde el frontend no conoce el esquema real). Sin esta guarda, ese
        // comodín permitía consultar vistas de sistema que compartieran nombre con una tabla de
        // dbo (p. ej. sys.<X> o INFORMATION_SCHEMA.<X> si existe dbo.<X>): el SQL final usa el
        // esquema pedido por el cliente, no el de la tabla que pasó la whitelist.
        if (cleanSchema != null && !cleanSchema.isBlank() && getDialect().isSystemTable(cleanSchema, cleanTable)) {
            return false;
        }

        return self.getTables().stream().anyMatch(t -> {
            boolean nameMatches = t.name().trim().equalsIgnoreCase(cleanTable);
            if (!nameMatches) return false;
            if (cleanSchema == null || cleanSchema.isBlank() || t.schema() == null || t.schema().isBlank()) return true;
            return t.schema().trim().equalsIgnoreCase(cleanSchema)
                    || cleanSchema.equalsIgnoreCase("dbo")
                    || t.schema().equalsIgnoreCase("dbo");
        });
    }

    /**
     * Obtiene la definición de columnas de una tabla seleccionada usando DatabaseMetaData
     * con fallback automático por ResultSetMetaData directo (SELECT * WHERE 1=0).
     */
    // unless: igual que en getTables(), un fallo de metadatos devuelve lista vacía y no debe
    // cachearse (con columnas vacías la validación cae al modo permisivo por regex).
    @Cacheable(value = "columns", key = "#schema + '.' + #tableName", unless = "#result == null || #result.isEmpty()")
    public List<ColumnInfo> getColumns(String schema, String tableName) {
        if (!isTableValid(schema, tableName)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + tableName + ")");
        }

        List<ColumnInfo> columns = new ArrayList<>();
        DbDialect dialect = getDialect();

        try (Connection conn = Objects.requireNonNull(dynamicDataSourceService.getDataSource()).getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            String catalog = conn.getCatalog();

            // Intento 1: Catálogo y esquema indicados
            try (ResultSet rs = metaData.getColumns(catalog, schema, tableName, "%")) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    String dataType = rs.getString("TYPE_NAME");
                    int columnSize = rs.getInt("COLUMN_SIZE");
                    String isNullable = rs.getString("IS_NULLABLE");
                    columns.add(new ColumnInfo(columnName, dataType, columnSize, "YES".equalsIgnoreCase(isNullable)));
                }
            }
            // Intento 2: Catálogo con esquema null (común en SQL Server / MySQL)
            if (columns.isEmpty()) {
                try (ResultSet rs = metaData.getColumns(catalog, null, tableName, "%")) {
                    while (rs.next()) {
                        String columnName = rs.getString("COLUMN_NAME");
                        String dataType = rs.getString("TYPE_NAME");
                        int columnSize = rs.getInt("COLUMN_SIZE");
                        String isNullable = rs.getString("IS_NULLABLE");
                        columns.add(new ColumnInfo(columnName, dataType, columnSize, "YES".equalsIgnoreCase(isNullable)));
                    }
                }
            }
            // Intento 3: Catálogo null con esquema
            if (columns.isEmpty()) {
                try (ResultSet rs = metaData.getColumns(null, schema, tableName, "%")) {
                    while (rs.next()) {
                        String columnName = rs.getString("COLUMN_NAME");
                        String dataType = rs.getString("TYPE_NAME");
                        int columnSize = rs.getInt("COLUMN_SIZE");
                        String isNullable = rs.getString("IS_NULLABLE");
                        columns.add(new ColumnInfo(columnName, dataType, columnSize, "YES".equalsIgnoreCase(isNullable)));
                    }
                }
            }
            // Intento 4: Fallback comodín
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
            log.warn("Error al recuperar columnas por DatabaseMetaData para la tabla {}.{}: {}", schema, tableName, e.getMessage());
        }

        // Intento 5 (Fallback Infalible): Consulta directa de estructura SELECT * FROM table WHERE 1=0
        if (columns.isEmpty()) {
            try {
                String safeTableName = SqlSafe.buildSafeTableName(dialect, schema, tableName);
                getJdbcTemplate().query("SELECT * FROM " + safeTableName + " WHERE 1 = 0", rs -> {
                    var rsmd = rs.getMetaData();
                    int count = rsmd.getColumnCount();
                    for (int i = 1; i <= count; i++) {
                        String colName = rsmd.getColumnLabel(i);
                        if (colName == null || colName.isBlank()) {
                            colName = rsmd.getColumnName(i);
                        }
                        String typeName = rsmd.getColumnTypeName(i);
                        int colSize = rsmd.getColumnDisplaySize(i);
                        boolean isNullable = rsmd.isNullable(i) != java.sql.ResultSetMetaData.columnNoNulls;
                        columns.add(new ColumnInfo(colName, typeName, colSize, isNullable));
                    }
                    return null;
                });
                log.info("Columnas recuperadas mediante fallback directo de ResultSetMetaData para {}.{} (total: {})", schema, tableName, columns.size());
            } catch (Exception e) {
                log.warn("Fallback de ResultSetMetaData falló para {}.{}: {}", schema, tableName, e.getMessage());
            }
        }

        return columns;
    }

    List<String> resolveValidColumns(String schema, String tableName, List<String> requestedColumns) {
        if (requestedColumns == null || requestedColumns.isEmpty()) {
            return Collections.emptyList();
        }

        List<ColumnInfo> tableCols = self.getColumns(schema, tableName);
        List<String> validColNames = tableCols.stream().map(ColumnInfo::name).toList();

        // Desempaquetar elementos que puedan contener comas internas (ej: ?columns=Rut,RazonSocial,Giro)
        List<String> flattenedRequested = new ArrayList<>();
        for (String item : requestedColumns) {
            if (item == null) continue;
            if (item.contains(",")) {
                for (String part : item.split(",")) {
                    if (!part.trim().isEmpty()) {
                        flattenedRequested.add(part.trim());
                    }
                }
            } else if (!item.trim().isEmpty()) {
                flattenedRequested.add(item.trim());
            }
        }

        List<String> resolved = new ArrayList<>();
        for (String rawCol : flattenedRequested) {
            String col = stripQuotes(rawCol);
            Optional<String> match = validColNames.stream()
                    .filter(name -> name.trim().equalsIgnoreCase(col))
                    .findFirst();

            if (match.isPresent()) {
                resolved.add(match.get());
            } else {
                // Si la tabla no devolvió columnas por metadatos pero el nombre de columna es sintácticamente limpio y seguro
                if (validColNames.isEmpty() && col.matches("^[a-zA-Z0-9_#$áéíóúÁÉÍÓÚñÑ ]{1,128}$")) {
                    resolved.add(col);
                } else {
                    throw new SecurityException("Acceso denegado: Intento de inyección detectado en columna '" + rawCol + "'");
                }
            }
        }
        return resolved;
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

        String cleanCol = stripQuotes(filterColumn);
        List<ColumnInfo> tableCols = self.getColumns(schema, tableName);
        Optional<String> match = tableCols.stream()
                .map(ColumnInfo::name)
                .filter(name -> name.trim().equalsIgnoreCase(cleanCol))
                .findFirst();

        String colToUse;
        if (match.isPresent()) {
            colToUse = match.get();
        } else if (tableCols.isEmpty() && cleanCol.matches("^[a-zA-Z0-9_#$áéíóúÁÉÍÓÚñÑ ]{1,128}$")) {
            colToUse = cleanCol;
        } else {
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
        String safeColName = dialect.escapeIdentifier(colToUse);

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

    // Clave por defecto (SimpleKey de todos los argumentos) en vez de concatenar con '.': con la
    // concatenación, filterValue=null y filterValue="null" generaban la misma clave, y el conteo
    // sin filtro se servía para un filtro "= 'null'" (y viceversa) durante el TTL de la caché.
    @Cacheable(value = "tableCount")
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
