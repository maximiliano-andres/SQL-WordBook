package com.LectorDBTemplate.PushDbTemplate.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utilidades de construcción segura de SQL compartidas por los servicios de acceso a datos
 * (SchemaMetadataService, ForeignKeyService, CustomReportService, ExcelExportService).
 * Separadas en una clase propia porque no pertenecen a ninguno de esos servicios en
 * particular: son funciones puras sobre nombres de identificadores y ejecución de consultas
 * por lotes, sin estado ni dependencia de metadata de esquema.
 */
final class SqlSafe {

    private SqlSafe() {
    }

    static String buildSafeTableName(String schema, String tableName) {
        return "[" + schema.replace("]", "]]") + "].[" + tableName.replace("]", "]]") + "]";
    }

    static String buildSafeColumnList(List<String> columns) {
        StringBuilder columnsBuilder = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) columnsBuilder.append(", ");
            columnsBuilder.append("[").append(columns.get(i).replace("]", "]]")).append("]");
        }
        return columnsBuilder.toString();
    }

    // SQL Server limita a 2.100 parámetros por consulta. Un lookup de FK con un parámetro
    // por valor distinto (WHERE pk IN (?, ?, ...)) puede superar ese límite cuando la
    // columna tiene alta cardinalidad: no ocurre en una página de grilla (máx. 100 filas),
    // pero sí al exportar una tabla completa (hasta app.export.max-rows filas). Se trocea
    // en lotes seguros y se ejecuta una consulta por lote, fusionando los resultados a
    // través del mismo RowCallbackHandler.
    private static final int MAX_IN_CLAUSE_BATCH_SIZE = 2000;

    static void queryInBatches(JdbcTemplate jdbcTemplate, String selectColsSql, String fromTableSql, String keyColumnSql,
                                List<Object> distinctValues, String extraCondition, Object extraConditionParam,
                                RowCallbackHandler handler) {
        for (int start = 0; start < distinctValues.size(); start += MAX_IN_CLAUSE_BATCH_SIZE) {
            List<Object> batch = distinctValues.subList(start, Math.min(start + MAX_IN_CLAUSE_BATCH_SIZE, distinctValues.size()));
            String placeholders = String.join(", ", Collections.nCopies(batch.size(), "?"));
            String sql = "SELECT " + selectColsSql + " FROM " + fromTableSql
                    + " WHERE " + keyColumnSql + " IN (" + placeholders + ")";
            List<Object> params = new ArrayList<>(batch);
            if (extraCondition != null) {
                sql += " AND " + extraCondition;
                params.add(extraConditionParam);
            }
            jdbcTemplate.query(sql, handler, params.toArray());
        }
    }
}
