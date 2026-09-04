package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.dialect.SqlServerDialect;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaMetadataServiceTest {

    @Mock private DynamicDataSourceService dynamicDataSourceService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private DatabaseMetaData metaData;
    @Mock private ResultSet tablesResultSet;

    private SchemaMetadataService schemaMetadataService;

    @BeforeEach
    void setUp() {
        lenient().when(dynamicDataSourceService.getDataSource()).thenReturn(dataSource);
        lenient().when(dynamicDataSourceService.getJdbcTemplate()).thenReturn(jdbcTemplate);
        lenient().when(dynamicDataSourceService.getDialect()).thenReturn(new SqlServerDialect());

        schemaMetadataService = new SchemaMetadataService(dynamicDataSourceService);
        ReflectionTestUtils.setField(schemaMetadataService, "self", schemaMetadataService);
    }

    private void mockTables(String schema, String name) throws Exception {
        when(dynamicDataSourceService.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getTables(any(), any(), any(), any())).thenReturn(tablesResultSet);

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

    private SchemaMetadataService spyServiceWithColumns(SchemaMetadataService.ColumnInfo... columns) {
        SchemaMetadataService spy = Mockito.spy(new SchemaMetadataService(dynamicDataSourceService));
        ReflectionTestUtils.setField(spy, "self", spy);
        doReturn(true).when(spy).isTableValid("dbo", "Empleados");
        doReturn(List.of(columns)).when(spy).getColumns("dbo", "Empleados");
        return spy;
    }

    @Test
    void getTableData_likeFilter_wrapsValueWithWildcardsAndBindsAsParam() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("nombre", "VARCHAR", 100, true));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        spy.getTableData("dbo", "Empleados", 15, 0, null, "nombre", "LIKE", "ana");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), paramsCaptor.capture());

        assertThat(sqlCaptor.getValue()).contains("WHERE [nombre] LIKE ?");
        assertThat(paramsCaptor.getValue()[0]).isEqualTo("%ana%");
    }

    @Test
    void getTableData_betweenFilter_bindsBothValuesAsParams() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("edad", "INT", 4, false));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        spy.getTableData("dbo", "Empleados", 15, 0, null, "edad", "BETWEEN", "18", "65");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), paramsCaptor.capture());

        assertThat(sqlCaptor.getValue()).contains("WHERE [edad] BETWEEN ? AND ?");
        assertThat(paramsCaptor.getValue()[0]).isEqualTo("18");
        assertThat(paramsCaptor.getValue()[1]).isEqualTo("65");
    }

    @Test
    void getTableData_isNullFilter_appendsOperatorWithoutParam() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("email", "VARCHAR", 100, true));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        spy.getTableData("dbo", "Empleados", 15, 0, null, "email", "IS NULL", null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), paramsCaptor.capture());

        assertThat(sqlCaptor.getValue()).contains("WHERE [email] IS NULL");
        assertThat(paramsCaptor.getValue()).containsExactly(0, 15);
    }

    @Test
    void getTableData_filterRejectsColumnNotInTable() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("nombre", "VARCHAR", 100, true));

        assertThatThrownBy(() -> spy.getTableData("dbo", "Empleados", 15, 0, null, "columnaInexistente", "=", "valor"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void getTableData_filterRejectsDisallowedOperator() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("nombre", "VARCHAR", 100, true));

        assertThatThrownBy(() -> spy.getTableData("dbo", "Empleados", 15, 0, null, "nombre", "RLIKE", "valor"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getTableCount_usesSameFilterConditionAsGetTableData() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("nombre", "VARCHAR", 100, true));
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class))).thenReturn(42L);

        long count = spy.getTableCount("dbo", "Empleados", "nombre", "=", "ana", null);

        assertThat(count).isEqualTo(42L);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), any(Class.class), paramsCaptor.capture());

        assertThat(sqlCaptor.getValue()).isEqualTo("SELECT COUNT(*) FROM [dbo].[Empleados] WHERE [nombre] = ?");
        assertThat(paramsCaptor.getValue()).containsExactly("ana");
    }
}
