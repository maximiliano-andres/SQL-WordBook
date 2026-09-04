package com.LectorDBTemplate.PushDbTemplate.service.dialect;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MariaDbDialect implements DbDialect {

    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "MYSQL", "INFORMATION_SCHEMA", "PERFORMANCE_SCHEMA", "SYS"
    );

    @Override
    public String getEngineType() {
        return "MARIADB";
    }

    @Override
    public String getDisplayName() {
        return "MariaDB";
    }

    @Override
    public String getDriverClassName() {
        return "org.mariadb.jdbc.Driver";
    }

    @Override
    public int getDefaultPort() {
        return 3306;
    }

    @Override
    public String buildJdbcUrl(String host, Integer port, String databaseName, Map<String, String> params) {
        int p = (port != null && port > 0) ? port : getDefaultPort();
        String h = (host != null && !host.isBlank()) ? host.trim() : "localhost";
        String db = (databaseName != null && !databaseName.isBlank()) ? databaseName.trim() : "mariadb";

        return "jdbc:mariadb://" + h + ":" + p + "/" + db;
    }

    @Override
    public String escapeIdentifier(String identifier) {
        if (identifier == null) return "";
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    public String escapeTableName(String schema, String tableName) {
        if (schema != null && !schema.isBlank()) {
            return escapeIdentifier(schema) + "." + escapeIdentifier(tableName);
        }
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
        if (tableName != null && tableName.equalsIgnoreCase("flyway_schema_history")) {
            return true;
        }
        if (schema != null && SYSTEM_SCHEMAS.contains(schema.toUpperCase(Locale.ROOT))) {
            return true;
        }
        return false;
    }

    @Override
    public String getSampleQuery() {
        return "SELECT 1";
    }
}
