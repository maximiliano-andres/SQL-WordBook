package com.LectorDBTemplate.PushDbTemplate.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias puras (sin contexto Spring ni base de datos real) de la
 * validación por whitelist que protege contra inyección SQL.
 *
 * Antes vivía en DatabaseServiceTest, cuando esto y el resto de la app compartían una sola
 * DatabaseService de ~1.700 líneas (ver auditoría, hallazgo F6).
 */
@ExtendWith(MockitoExtension.class)
class SchemaMetadataServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private DatabaseMetaData metaData;
    @Mock private ResultSet tablesResultSet;

    private SchemaMetadataService schemaMetadataService;

    @BeforeEach
    void setUp() {
        schemaMetadataService = new SchemaMetadataService(jdbcTemplate);
        // En producción Spring inyecta el proxy cacheado en el campo "self"; en el test lo
        // apuntamos a la propia instancia (sin caché real, mismo comportamiento funcional).
        ReflectionTestUtils.setField(schemaMetadataService, "self", schemaMetadataService);
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

        assertThat(schemaMetadataService.isTableValid("dbo", "Empleados")).isTrue();
    }

    @Test
    void isTableValid_isCaseInsensitive() throws Exception {
        mockTables("dbo", "Empleados");

        assertThat(schemaMetadataService.isTableValid("DBO", "empleados")).isTrue();
    }

    @Test
    void isTableValid_returnsFalse_forTableNotInWhitelist() throws Exception {
        mockTables("dbo", "Empleados");

        assertThat(schemaMetadataService.isTableValid("dbo", "Usuarios; DROP TABLE Empleados; --")).isFalse();
    }

    @Test
    void isTableValid_returnsFalse_forNullSchemaOrName() {
        assertThat(schemaMetadataService.isTableValid(null, "Empleados")).isFalse();
        assertThat(schemaMetadataService.isTableValid("dbo", null)).isFalse();
    }

    @Test
    void getColumns_rejectsTableNotInWhitelist() throws Exception {
        mockTables("dbo", "Empleados");

        assertThatThrownBy(() -> schemaMetadataService.getColumns("dbo", "TablaInventada"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void getTableCount_rejectsTableNotInWhitelist() throws Exception {
        mockTables("dbo", "Empleados");

        assertThatThrownBy(() -> schemaMetadataService.getTableCount("dbo", "TablaInventada"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void getTableData_rejectsTableNotInWhitelist() throws Exception {
        mockTables("dbo", "Empleados");

        assertThatThrownBy(() -> schemaMetadataService.getTableData("dbo", "TablaInventada", 10, 0, null))
                .isInstanceOf(SecurityException.class);
    }

    // --- Filtro de filas (buildFilterCondition, vía getTableCount/getTableData) ---
    // Se usa un spy (en vez de mockear DatabaseMetaData) para aislar la lógica de
    // construcción segura del filtro SQL de la resolución de metadata, ya cubierta arriba.

    private SchemaMetadataService spyServiceWithColumns(SchemaMetadataService.ColumnInfo... columns) {
        SchemaMetadataService spy = Mockito.spy(new SchemaMetadataService(jdbcTemplate));
        ReflectionTestUtils.setField(spy, "self", spy);
        doReturn(true).when(spy).isTableValid("dbo", "Empleados");
        doReturn(List.of(columns)).when(spy).getColumns("dbo", "Empleados");
        return spy;
    }

    @Test
    void getTableData_likeFilter_wrapsValueWithWildcardsAndBindsAsParam() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("nombre", "VARCHAR", 100, true));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        spy.getTableData("dbo", "Empleados", 15, 0, null, "nombre", "LIKE", "ana", null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("WHERE [nombre] LIKE ?");
        assertThat(params.getValue()).containsExactly("%ana%", 0, 15);
    }

    @Test
    void getTableData_betweenOperator_bindsTwoParamsInOrder() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("edad", "INT", 10, true));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        spy.getTableData("dbo", "Empleados", 15, 0, null, "edad", "BETWEEN", "18", "30");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("WHERE [edad] BETWEEN ? AND ?");
        assertThat(params.getValue()).containsExactly("18", "30", 0, 15);
    }

    @Test
    void getTableData_isNullOperator_needsNoBoundValue() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("email", "VARCHAR", 100, true));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        spy.getTableData("dbo", "Empleados", 15, 0, null, "email", "IS NULL", null, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("WHERE [email] IS NULL");
        assertThat(params.getValue()).containsExactly(0, 15);
    }

    @Test
    void getTableData_filterColumnWithoutOperator_appliesNoFilter() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("nombre", "VARCHAR", 100, true));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        spy.getTableData("dbo", "Empleados", 15, 0, null, "nombre", null, null, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).doesNotContain("WHERE");
    }

    @Test
    void getTableCount_rejectsFilterColumnNotInWhitelist() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("nombre", "VARCHAR", 100, true));

        assertThatThrownBy(() -> spy.getTableCount("dbo", "Empleados", "nombre]; DROP TABLE Empleados; --", "=", "x", null))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void getTableCount_rejectsFilterOperatorNotWhitelisted() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("nombre", "VARCHAR", 100, true));

        assertThatThrownBy(() -> spy.getTableCount("dbo", "Empleados", "nombre", "1=1; DROP TABLE Empleados; --", "x", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getTableCount_appliesFilterCondition_andBindsParams() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("nombre", "VARCHAR", 100, true));
        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
                .thenReturn(3L);

        long count = spy.getTableCount("dbo", "Empleados", "nombre", "=", "ana", null);

        assertThat(count).isEqualTo(3L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(sql.capture(), org.mockito.ArgumentMatchers.eq(Long.class), params.capture());
        assertThat(sql.getValue()).contains("WHERE [nombre] = ?");
        assertThat(params.getValue()).containsExactly("ana");
    }
}
