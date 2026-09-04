package com.LectorDBTemplate.PushDbTemplate.service.dialect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DbDialectFactory {

    private static final Map<String, DbDialect> DIALECTS = new LinkedHashMap<>();

    static {
        register(new SqlServerDialect());
        register(new PostgreSqlDialect());
        register(new MySqlDialect());
        register(new MariaDbDialect());
        register(new OracleDialect());
        register(new SqliteDialect());
    }

    private static void register(DbDialect dialect) {
        DIALECTS.put(dialect.getEngineType().toUpperCase(Locale.ROOT), dialect);
    }

    public static List<DbDialect> getAllDialects() {
        return List.copyOf(DIALECTS.values());
    }

    public static DbDialect getByEngineType(String engineType) {
        if (engineType == null || engineType.isBlank()) {
            return DIALECTS.get("SQLSERVER");
        }
        DbDialect dialect = DIALECTS.get(engineType.trim().toUpperCase(Locale.ROOT));
        return dialect != null ? dialect : DIALECTS.get("SQLSERVER");
    }

    public static DbDialect detectFromProductNameOrUrl(String productName, String url) {
        if (productName != null) {
            String p = productName.toLowerCase(Locale.ROOT);
            if (p.contains("microsoft") || p.contains("sql server") || p.contains("mssql")) {
                return DIALECTS.get("SQLSERVER");
            }
            if (p.contains("postgres")) {
                return DIALECTS.get("POSTGRESQL");
            }
            if (p.contains("mariadb")) {
                return DIALECTS.get("MARIADB");
            }
            if (p.contains("mysql")) {
                return DIALECTS.get("MYSQL");
            }
            if (p.contains("oracle")) {
                return DIALECTS.get("ORACLE");
            }
            if (p.contains("sqlite")) {
                return DIALECTS.get("SQLITE");
            }
        }

        if (url != null) {
            String u = url.toLowerCase(Locale.ROOT);
            if (u.startsWith("jdbc:sqlserver:")) return DIALECTS.get("SQLSERVER");
            if (u.startsWith("jdbc:postgresql:")) return DIALECTS.get("POSTGRESQL");
            if (u.startsWith("jdbc:mariadb:")) return DIALECTS.get("MARIADB");
            if (u.startsWith("jdbc:mysql:")) return DIALECTS.get("MYSQL");
            if (u.startsWith("jdbc:oracle:")) return DIALECTS.get("ORACLE");
            if (u.startsWith("jdbc:sqlite:")) return DIALECTS.get("SQLITE");
        }

        return DIALECTS.get("SQLSERVER");
    }
}
