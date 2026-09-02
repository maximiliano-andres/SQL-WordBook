package com.LectorDBTemplate.PushDbTemplate.service;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Acceso seguro y whitelisted a la estructura y datos crudos del esquema: qué tablas existen,
 * qué columnas tiene cada una, y lectura paginada/filtrada de filas. Es la base de la que
 * dependen ForeignKeyService, CustomReportService y ExcelExportService para validar cualquier
 * identificador antes de interpolarlo en SQL (nunca al revés): aislar esta validación aquí
 * evita que la defensa contra inyección SQL quede dispersa por todo el código de negocio.
 *
 * Extraído de la antigua DatabaseService (God Class de ~1.700 líneas que mezclaba esto con
 * resolución de FKs, reportes multi-tabla y exportación a Excel; ver auditoría, hallazgo F6).
 */
@Service
public class SchemaMetadataService {

    private static final Logger log = LoggerFactory.getLogger(SchemaMetadataService.class);
    private final JdbcTemplate jdbcTemplate;

    // Referencia al propio bean (proxy de Spring): las llamadas internas a métodos @Cacheable
    // deben pasar por el proxy o la caché nunca se activa (limitación conocida de AOP por
    // auto-invocación). Se inyecta por campo (en vez de por constructor) para romper el ciclo
    // auto-referencial; @Lazy evita que Spring intente crear el proxy antes de tiempo.
    // A diferencia de la DatabaseService original, este truco ahora queda acotado a esta única
    // clase pequeña y enfocada: los demás servicios llaman a este bean real vía inyección
    // normal (getTables/getColumns aquí), sin necesitar su propio "self".
    @Autowired
    @Lazy
    private SchemaMetadataService self;

    public SchemaMetadataService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record TableInfo(String schema, String name) {}
    public record ColumnInfo(String name, String type, int size, boolean nullable) {}

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

                    // Excluimos esquemas del sistema propios de SQL Server, y la tabla de
                    // historial de Flyway (metadata de la propia app, no dato de negocio).
                    if (!schema.equalsIgnoreCase("sys") &&
                        !schema.equalsIgnoreCase("INFORMATION_SCHEMA") &&
                        !schema.equalsIgnoreCase("db_owner") &&
                        !schema.equalsIgnoreCase("db_securityadmin") &&
                        !schema.equalsIgnoreCase("db_ddladmin") &&
                        !name.equalsIgnoreCase("flyway_schema_history")) {
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
     * Valida una lista de columnas solicitadas contra el esquema real de la tabla (whitelist).
     * Paquete-visible: la usa ExcelExportService para validar las columnas pedidas antes de
     * proyectarlas en el SELECT de exportación.
     */
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

    /**
     * Construye una condición WHERE segura para filtrar filas de una sola tabla, validando la
     * columna contra el esquema real y el operador contra una whitelist. Paquete-visible:
     * la usa también ExcelExportService para aplicar el mismo filtro de fila en la exportación.
     */
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

    String buildFilterCondition(
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
     *
     * Cacheado también cuando hay filtro (clave incluye columna/operador/valor/valor2), con
     * el mismo TTL corto: antes solo se cacheaba sin filtro, así que cada cambio de página con
     * un filtro activo repetía el COUNT(*) completo (ver auditoría, hallazgo F9). El TTL corto
     * (ver CacheConfig) acota cuánto puede desactualizarse un conteo filtrado frente a cambios
     * concurrentes de datos.
     */
    @Cacheable(value = "tableCount", key = "#schema + '.' + #tableName + '.' + #filterColumn + '.' + #filterOperator + '.' + #filterValue + '.' + #filterValue2")
    public long getTableCount(String schema, String tableName, String filterColumn, String filterOperator, String filterValue, String filterValue2) {
        if (!isTableValid(schema, tableName)) {
            throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + tableName + ")");
        }
        // Escapado seguro con corchetes SQL Server
        String safeTableName = SqlSafe.buildSafeTableName(schema, tableName);
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

        String safeTableName = SqlSafe.buildSafeTableName(schema, tableName);
        String columnList = (columns == null || columns.isEmpty())
                ? "*"
                : SqlSafe.buildSafeColumnList(resolveValidColumns(schema, tableName, columns));

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
}
