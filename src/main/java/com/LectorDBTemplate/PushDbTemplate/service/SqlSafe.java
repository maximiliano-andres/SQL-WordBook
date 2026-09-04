package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.dialect.DbDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utilidades de construcción segura de SQL compartidas por los servicios de acceso a datos
 * (SchemaMetadataService, ForeignKeyService, CustomReportService, ExcelExportService),
 * adaptadas a los dialectos multi-base de datos.
 */
public final class SqlSafe {

    private SqlSafe() {
    }

    public static String buildSafeTableName(DbDialect dialect, String schema, String tableName) {
        return dialect.escapeTableName(schema, tableName);
    }

    public static String buildSafeColumnList(DbDialect dialect, List<String> columns) {
        return dialect.escapeColumnList(columns);
    }

    public static String buildSafeIdentifier(DbDialect dialect, String identifier) {
        return dialect.escapeIdentifier(identifier);
    }

    // Límite de 999 parámetros por lote para compatibilidad universal con Oracle (límite 1.000),
    // SQLite (límite 999 en versiones clásicas) y SQL Server (límite 2.100).
    private static final int MAX_IN_CLAUSE_BATCH_SIZE = 900;

    public static void queryInBatches(JdbcTemplate jdbcTemplate, String selectColsSql, String fromTableSql, String keyColumnSql,
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
