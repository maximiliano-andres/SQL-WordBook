package com.LectorDBTemplate.PushDbTemplate.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias puras (sin contexto Spring ni base de datos real) de la
 * validación por whitelist que protege contra inyección SQL.
 */
@ExtendWith(MockitoExtension.class)
class DatabaseServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private DatabaseMetaData metaData;
    @Mock private ResultSet tablesResultSet;

    private DatabaseService databaseService;

    @BeforeEach
    void setUp() {
        databaseService = new DatabaseService(jdbcTemplate, 50_000, 2);
        // En producción Spring inyecta el proxy cacheado en el campo "self";
        // en el test lo apuntamos a la propia instancia (sin caché real, mismo comportamiento funcional).
        ReflectionTestUtils.setField(databaseService, "self", databaseService);
    }

    private void mockTables(String schema, String name) throws Exception {
        when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getTables(null, null, "%", new String[]{"TABLE"})).thenReturn(tablesResultSet);

        when(tablesResultSet.next()).thenReturn(true, false);
        when(tablesResultSet.getString("TABLE_SCHEM")).thenReturn(schema);
        when(tablesResultSet.getString("TABLE_NAME")).thenReturn(name);
    }

    @Test
    void isTableValid_returnsTrue_forTableReturnedByMetadata() throws Exception {
        mockTables("dbo", "Empleados");

        assertThat(databaseService.isTableValid("dbo", "Empleados")).isTrue();
    }

    @Test
    void isTableValid_isCaseInsensitive() throws Exception {
        mockTables("dbo", "Empleados");

        assertThat(databaseService.isTableValid("DBO", "empleados")).isTrue();
    }

    @Test
    void isTableValid_returnsFalse_forTableNotInWhitelist() throws Exception {
        mockTables("dbo", "Empleados");

        assertThat(databaseService.isTableValid("dbo", "Usuarios; DROP TABLE Empleados; --")).isFalse();
    }

    @Test
    void isTableValid_returnsFalse_forNullSchemaOrName() {
        assertThat(databaseService.isTableValid(null, "Empleados")).isFalse();
        assertThat(databaseService.isTableValid("dbo", null)).isFalse();
    }

    @Test
    void getColumns_rejectsTableNotInWhitelist() throws Exception {
        mockTables("dbo", "Empleados");

        assertThatThrownBy(() -> databaseService.getColumns("dbo", "TablaInventada"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void getTableCount_rejectsTableNotInWhitelist() throws Exception {
        mockTables("dbo", "Empleados");

        assertThatThrownBy(() -> databaseService.getTableCount("dbo", "TablaInventada"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void getTableData_rejectsTableNotInWhitelist() throws Exception {
        mockTables("dbo", "Empleados");

        assertThatThrownBy(() -> databaseService.getTableData("dbo", "TablaInventada", 10, 0, null))
                .isInstanceOf(SecurityException.class);
    }
}
