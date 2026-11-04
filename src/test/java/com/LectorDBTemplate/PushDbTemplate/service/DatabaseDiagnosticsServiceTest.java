package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.dialect.PostgreSqlDialect;
import com.LectorDBTemplate.PushDbTemplate.service.dialect.SqlServerDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * GET /api/db/info (Consola DBA). Sin tests antes de la auditoría 2026-11.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DatabaseDiagnosticsServiceTest {

    @Mock private DynamicDataSourceService dynamicDataSourceService;
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private DatabaseMetaData meta;
    @Mock private ResultSet tables;
    @Mock private JdbcTemplate jdbcTemplate;

    private DatabaseDiagnosticsService service;

    @BeforeEach
    void setUp() throws Exception {
        when(dynamicDataSourceService.getDataSource()).thenReturn(dataSource);
        when(dynamicDataSourceService.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(dynamicDataSourceService.getDialect()).thenReturn(new SqlServerDialect());
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(meta);
        when(connection.getCatalog()).thenReturn("CL3530BD01MAP");
        when(meta.getDatabaseProductName()).thenReturn("Microsoft SQL Server");
        when(meta.getDatabaseProductVersion()).thenReturn("16.00");
        when(meta.getTables(any(), any(), any(), any())).thenReturn(tables);
        service = new DatabaseDiagnosticsService(dynamicDataSourceService);
    }

    @Test
    void getDatabaseInfo_countsTablesAndViews_excludingSystemObjects() throws Exception {
        when(tables.next()).thenReturn(true, true, true, true, false);
        when(tables.getString("TABLE_TYPE")).thenReturn("TABLE", "VIEW", "TABLE", "TABLE");
        when(tables.getString("TABLE_SCHEM")).thenReturn("dbo", "dbo", "sys", "dbo");
        when(tables.getString("TABLE_NAME")).thenReturn("Empleados", "vEmpleados", "objects", "flyway_schema_history");

        Map<String, Object> info = service.getDatabaseInfo();

        assertThat(info.get("totalTables")).isEqualTo(1);
        assertThat(info.get("totalViews")).isEqualTo(1);
        assertThat(info.get("currentDatabase")).isEqualTo("CL3530BD01MAP");
        assertThat(info.get("engineType")).isEqualTo("SQLSERVER");
    }

    // La versión anterior solo enmascaraba "password=" y "user=": se escapaban "pwd=" y las
    // credenciales en formato userinfo (user:pass@host) de URLs personalizadas.
    @Test
    void getDatabaseInfo_masksEveryCredentialFormatInJdbcUrl() throws Exception {
        when(meta.getURL()).thenReturn("jdbc:postgresql://admin:Secreta1@host:5432/db?pwd=Otra2&user=root");

        String url = (String) service.getDatabaseInfo().get("jdbcUrl");

        assertThat(url).doesNotContain("Secreta1").doesNotContain("Otra2").doesNotContain("root").contains("host:5432/db");
    }

    @Test
    void getDatabaseInfo_sqlServer_enrichesWithSessionsAndDatabaseState() {
        when(jdbcTemplate.queryForObject(contains("sys.dm_exec_sessions"), eq(Integer.class))).thenReturn(7);
        when(jdbcTemplate.queryForMap(contains("sys.databases"))).thenReturn(Map.of(
                "state_desc", "ONLINE", "recovery_model_desc", "FULL", "collation_name", "Modern_Spanish_CI_AS"));

        Map<String, Object> info = service.getDatabaseInfo();

        assertThat(info.get("activeConnections")).isEqualTo(7);
        assertThat(info.get("dbRecoveryModel")).isEqualTo("FULL");
        assertThat(info.get("dbCollation")).isEqualTo("Modern_Spanish_CI_AS");
    }

    @Test
    void getDatabaseInfo_withoutViewServerStatePermission_keepsDefaultsInsteadOfFailing() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException("x", "SELECT", new SQLException("permiso denegado")));
        when(jdbcTemplate.queryForMap(anyString())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("x"));

        Map<String, Object> info = service.getDatabaseInfo();

        assertThat(info).doesNotContainKey("error");
        assertThat(info.get("activeConnections")).isEqualTo(1);
        assertThat(info.get("dbState")).isEqualTo("ONLINE");
    }

    @Test
    void getDatabaseInfo_postgres_reportsSizeInMb() {
        when(dynamicDataSourceService.getDialect()).thenReturn(new PostgreSqlDialect());
        when(jdbcTemplate.queryForObject(contains("pg_database_size"), eq(Long.class))).thenReturn(50L * 1024 * 1024);

        assertThat(service.getDatabaseInfo().get("totalSizeMb")).isEqualTo(50);
    }

    @Test
    void getDatabaseInfo_connectionFailure_returnsGenericErrorWithoutDriverDetails() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException(
                "No se pudo realizar la conexión TCP/IP al host 10.0.0.5, puerto 1433, usuario sa"));

        Map<String, Object> info = service.getDatabaseInfo();

        assertThat((String) info.get("error")).doesNotContain("10.0.0.5").doesNotContain("usuario");
        assertThat(info.get("engineType")).isEqualTo("SQLSERVER");
    }
}
