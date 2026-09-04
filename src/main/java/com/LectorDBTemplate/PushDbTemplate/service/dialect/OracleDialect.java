package com.LectorDBTemplate.PushDbTemplate.service.dialect;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class OracleDialect implements DbDialect {

    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "SYS", "SYSTEM", "OUTLN", "MDSYS", "CTXSYS", "XDB", "WMSYS",
            "LBACSYS", "DVSYS", "DBSNMP", "APPQOSSYS", "GSMADMIN_INTERNAL",
            "OLAPSYS", "FLOWS_FILES", "AUDSYS", "ANONYMOUS", "APEX_PUBLIC_USER"
    );

    @Override
    public String getEngineType() {
        return "ORACLE";
    }

    @Override
    public String getDisplayName() {
        return "Oracle Database";
    }

    @Override
    public String getDriverClassName() {
        return "oracle.jdbc.OracleDriver";
    }

    @Override
    public int getDefaultPort() {
        return 1521;
    }

    @Override
    public String buildJdbcUrl(String host, Integer port, String databaseName, Map<String, String> params) {
        int p = (port != null && port > 0) ? port : getDefaultPort();
        String h = (host != null && !host.isBlank()) ? host.trim() : "localhost";
        String db = (databaseName != null && !databaseName.isBlank()) ? databaseName.trim() : "XE";

        return "jdbc:oracle:thin:@//" + h + ":" + p + "/" + db;
    }

    @Override
    public String escapeIdentifier(String identifier) {
        if (identifier == null) return "";
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
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
        // Oracle 12c+ soporte estándar
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
        return "1";
    }

    @Override
    public boolean isSystemTable(String schema, String tableName) {
        if (tableName != null && tableName.equalsIgnoreCase("flyway_schema_history")) {
            return true;
        }
        if (schema != null) {
            String upper = schema.toUpperCase(Locale.ROOT);
            if (SYSTEM_SCHEMAS.contains(upper) || upper.startsWith("APEX_")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getSampleQuery() {
        return "SELECT 1 FROM DUAL";
    }
}
