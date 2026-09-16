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

    @Test
    void sanitizeJdbcUrl_handlesNullGracefully() {
        String sanitized = ReflectionTestUtils.invokeMethod(dynamicDataSourceService, "sanitizeJdbcUrl", (Object) null);
        assertEquals("N/A", sanitized);
    }
}
