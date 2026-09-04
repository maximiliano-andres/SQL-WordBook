package com.LectorDBTemplate.PushDbTemplate.service.dialect;

import java.util.List;
import java.util.Map;

public class SqliteDialect implements DbDialect {

    @Override
    public String getEngineType() {
        return "SQLITE";
    }

    @Override
    public String getDisplayName() {
        return "SQLite";
    }

    @Override
    public String getDriverClassName() {
        return "org.sqlite.JDBC";
    }

    @Override
    public int getDefaultPort() {
        return 0; // Archivo local
    }

    @Override
    public String buildJdbcUrl(String host, Integer port, String databaseName, Map<String, String> params) {
        String path = (databaseName != null && !databaseName.isBlank()) ? databaseName.trim() : (host != null && !host.isBlank() ? host.trim() : "database.sqlite");
        return "jdbc:sqlite:" + path;
    }

    @Override
    public String escapeIdentifier(String identifier) {
        if (identifier == null) return "";
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String escapeTableName(String schema, String tableName) {
        return escapeIdentifier(tableName);
    }

    @Override
    public String escapeColumnList(List<String> columns) {
        if (columns == null || columns.isEmpty()) return "*";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(escapeIdentifier(columns.get(i)));
        }
        return sb.toString();
    }

    @Override
    public String buildPaginationSql(String baseSql, int limit, int offset) {
        return baseSql + " LIMIT ? OFFSET ?";
    }

    @Override
    public void appendPaginationParams(List<Object> queryParams, int limit, int offset) {
        queryParams.add(limit);
        queryParams.add(offset);
    }

    @Override
    public String buildDefaultOrderBy(boolean isDistinct, List<String> selectColumns) {
        if (isDistinct && selectColumns != null && !selectColumns.isEmpty()) {
            return escapeIdentifier(selectColumns.get(0)) + " ASC";
        }
        return "1";
    }

    @Override
    public boolean isSystemTable(String schema, String tableName) {
        if (tableName == null) return false;
        String lower = tableName.toLowerCase();
        return lower.startsWith("sqlite_") || lower.equalsIgnoreCase("flyway_schema_history");
    }

    @Override
    public String getSampleQuery() {
        return "SELECT 1";
    }
}
