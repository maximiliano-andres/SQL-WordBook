package com.LectorDBTemplate.PushDbTemplate.service.dialect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cobertura de los 6 dialectos soportados (ver auditoría, hallazgo: 0 tests dedicados pese a
 * ser el código con mayor riesgo real al agregar un motor nuevo o modificar el escapado/
 * paginación de uno existente). DynamicDataSourceServiceTest ya cubre escapeTableName y
 * buildPaginationSql por motor; esta clase se enfoca en lo que faltaba: contrato genérico
 * (todo dialecto debe cumplirlo) + buildJdbcUrl específico por motor (formato de URL,
 * puerto/base de datos por defecto cuando no se especifican).
 */
class DbDialectTest {

    static List<DbDialect> allDialects() {
        return DbDialectFactory.getAllDialects();
    }

    // --- Contrato genérico: válido para cualquier dialecto presente y futuro ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDialects")
    void escapeIdentifier_wrapsAndNeverReturnsNullOrBlank(DbDialect dialect) {
        String escaped = dialect.escapeIdentifier("columna");
        assertThat(escaped).isNotBlank().contains("columna");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDialects")
    void escapeIdentifier_neutralizesEmbeddedDelimiterInsteadOfBreakingOut(DbDialect dialect) {
        // Un identificador que contenga el propio carácter delimitador del dialecto (comilla
        // doble, corchete de cierre o backtick, según cuál use cada uno) no debe permitir
        // "escapar" del identificador hacia SQL libre: el delimitador embebido debe quedar
        // duplicado/escapado, y el resultado debe seguir envuelto por los delimitadores.
        String wrapped = dialect.escapeIdentifier("x");
        char open = wrapped.charAt(0);
        char close = wrapped.charAt(wrapped.length() - 1);

        String malicious = "a" + close + "b";
        String escaped = dialect.escapeIdentifier(malicious);

        assertThat(escaped).startsWith(String.valueOf(open)).endsWith(String.valueOf(close));
        String inner = escaped.substring(1, escaped.length() - 1);
        long innerCloseCount = inner.chars().filter(c -> c == close).count();
        assertThat(innerCloseCount).isEqualTo(2);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDialects")
    void getDriverClassName_andEngineType_areNeverBlank(DbDialect dialect) {
        assertThat(dialect.getDriverClassName()).isNotBlank();
        assertThat(dialect.getEngineType()).isNotBlank();
        assertThat(dialect.getDisplayName()).isNotBlank();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDialects")
    void buildJdbcUrl_withNullHostAndDatabase_stillProducesAUsableUrl(DbDialect dialect) {
        String url = dialect.buildJdbcUrl(null, null, null, Map.of());
        assertThat(url).startsWith("jdbc:");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDialects")
    void appendPaginationParams_addsExactlyTwoParams(DbDialect dialect) {
        List<Object> params = new ArrayList<>();
        dialect.appendPaginationParams(params, 15, 30);
        assertThat(params).hasSize(2).containsOnly(15, 30);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDialects")
    void isSystemTable_flagsOwnFlywayHistoryTable(DbDialect dialect) {
        assertThat(dialect.isSystemTable(null, "flyway_schema_history")).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDialects")
    void getByEngineType_roundTripsWithGetEngineType(DbDialect dialect) {
        assertThat(DbDialectFactory.getByEngineType(dialect.getEngineType())).isSameAs(dialect);
    }

    // --- buildJdbcUrl: formato específico por motor ---

    @Test
    void sqlServer_buildJdbcUrl_usesSemicolonParamsAndDefaultsToMaster() {
        DbDialect dialect = DbDialectFactory.getByEngineType("SQLSERVER");
        assertThat(dialect.buildJdbcUrl("db.example.com", 1433, null, Map.of()))
                .isEqualTo("jdbc:sqlserver://db.example.com:1433;databaseName=master;encrypt=true;trustServerCertificate=true");
    }

    @Test
    void postgres_buildJdbcUrl_usesSlashPathAndSslModeQueryParam() {
        DbDialect dialect = DbDialectFactory.getByEngineType("POSTGRESQL");
        assertThat(dialect.buildJdbcUrl("db.example.com", 5432, "ventas", Map.of("sslmode", "require")))
                .isEqualTo("jdbc:postgresql://db.example.com:5432/ventas?sslmode=require");
    }

    @Test
    void mysql_buildJdbcUrl_defaultsToDefaultPortAndMysqlDatabaseWhenUnset() {
        DbDialect dialect = DbDialectFactory.getByEngineType("MYSQL");
        String url = dialect.buildJdbcUrl(null, null, null, Map.of());
        assertThat(url).startsWith("jdbc:mysql://localhost:3306/mysql?");
    }

    @Test
    void mariadb_buildJdbcUrl_usesMariadbScheme() {
        DbDialect dialect = DbDialectFactory.getByEngineType("MARIADB");
        assertThat(dialect.buildJdbcUrl("db.example.com", 3306, "ventas", Map.of()))
                .isEqualTo("jdbc:mariadb://db.example.com:3306/ventas");
    }

    @Test
    void oracle_buildJdbcUrl_usesThinAtDoubleSlashSidFormat() {
        DbDialect dialect = DbDialectFactory.getByEngineType("ORACLE");
        assertThat(dialect.buildJdbcUrl("db.example.com", 1521, "ORCL", Map.of()))
                .isEqualTo("jdbc:oracle:thin:@//db.example.com:1521/ORCL");
    }

    @Test
    void sqlite_buildJdbcUrl_usesDatabaseNameAsFilePathIgnoringHostAndPort() {
        DbDialect dialect = DbDialectFactory.getByEngineType("SQLITE");
        assertThat(dialect.buildJdbcUrl("ignored-host", 9999, "/tmp/datos.sqlite", Map.of()))
                .isEqualTo("jdbc:sqlite:/tmp/datos.sqlite");
    }

    // --- Detección automática por producto/URL (usada al reconectar) ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("allDialects")
    void detectFromProductNameOrUrl_resolvesBackToTheSameDialectFromItsOwnJdbcUrl(DbDialect dialect) {
        String url = dialect.buildJdbcUrl("localhost", dialect.getDefaultPort(), null, Map.of());
        assertThat(DbDialectFactory.detectFromProductNameOrUrl(null, url)).isSameAs(dialect);
    }

    @Test
    void detectFromProductNameOrUrl_fallsBackToSqlServerWhenUnrecognized() {
        assertThat(DbDialectFactory.detectFromProductNameOrUrl("Motor Desconocido S.A.", "jdbc:desconocido://x"))
                .isEqualTo(DbDialectFactory.getByEngineType("SQLSERVER"));
    }
}
