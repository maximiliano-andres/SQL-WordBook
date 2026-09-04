package com.LectorDBTemplate.PushDbTemplate.service.dialect;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SqlServerDialect implements DbDialect {

    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "SYS", "INFORMATION_SCHEMA", "DB_OWNER", "DB_SECURITYADMIN",
            "DB_DDLADMIN", "DB_ACCESSADMIN", "DB_DATAREADER", "DB_DATAWRITER",
            "DB_DENYDATAREADER", "DB_DENYDATAWRITER", "GUEST"
    );

    @Override
    public String getEngineType() {
        return "SQLSERVER";
    }

    @Override
    public String getDisplayName() {
        return "Microsoft SQL Server";
    }

    @Override
    public String getDriverClassName() {
        return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    }

    @Override
    public int getDefaultPort() {
        return 1433;
    }

    @Override
    public String buildJdbcUrl(String host, Integer port, String databaseName, Map<String, String> params) {
        int p = (port != null && port > 0) ? port : getDefaultPort();
        String h = (host != null && !host.isBlank()) ? host.trim() : "localhost";
        String db = (databaseName != null && !databaseName.isBlank()) ? databaseName.trim() : "master";

        StringBuilder sb = new StringBuilder();
        sb.append("jdbc:sqlserver://").append(h).append(":").append(p)
          .append(";databaseName=").append(db)
          .append(";encrypt=").append(params != null ? params.getOrDefault("encrypt", "true") : "true")
          .append(";trustServerCertificate=").append(params != null ? params.getOrDefault("trustServerCertificate", "true") : "true");
        return sb.toString();
    }

    @Override
    public String escapeIdentifier(String identifier) {
        if (identifier == null) return "";
        return "[" + identifier.replace("]", "]]") + "]";
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
        return baseSql + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
    }

    @Override
    public void appendPaginationParams(List<Object> queryParams, int limit, int offset) {
        queryParams.add(offset);
        queryParams.add(limit);
    }

    @Override
    public String buildDefaultOrderBy(boolean isDistinct, List<String> selectColumns) {
        if (isDistinct && selectColumns != null && !selectColumns.isEmpty()) {
            return escapeIdentifier(selectColumns.get(0)) + " ASC";
        }
        return "(SELECT NULL)";
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
