package com.LectorDBTemplate.PushDbTemplate.service.dialect;

import java.util.List;
import java.util.Map;

/**
 * Interfaz de abstracción para manejar las particularidades de sintaxis SQL,
 * delimitadores de identificadores, paginación y filtrado de metadatos del sistema
 * para cada motor de base de datos soportado.
 */
public interface DbDialect {

    String getEngineType();

    String getDisplayName();

    String getDriverClassName();

    int getDefaultPort();

    String buildJdbcUrl(String host, Integer port, String databaseName, Map<String, String> additionalParams);

    String escapeIdentifier(String identifier);

    String escapeTableName(String schema, String tableName);

    String escapeColumnList(List<String> columns);

    String buildPaginationSql(String baseSql, int limit, int offset);

    void appendPaginationParams(List<Object> queryParams, int limit, int offset);

    String buildDefaultOrderBy(boolean isDistinct, List<String> selectColumns);

    boolean isSystemTable(String schema, String tableName);

    String getSampleQuery();
}
