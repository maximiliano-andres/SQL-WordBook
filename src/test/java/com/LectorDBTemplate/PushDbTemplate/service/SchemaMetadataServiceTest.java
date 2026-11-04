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
        lenient().doReturn(true).when(spy).isTableValid("dbo", "Empleados");
        lenient().doReturn(List.of(columns)).when(spy).getColumns("dbo", "Empleados");
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

    @Test
    void resolveValidColumns_handlesCommaSeparatedAndUntrimmedColumnNames() {
        SchemaMetadataService spy = spyServiceWithColumns(
                new SchemaMetadataService.ColumnInfo("Rut", "VARCHAR", 20, false),
                new SchemaMetadataService.ColumnInfo("RazonSocial", "VARCHAR", 100, false),
                new SchemaMetadataService.ColumnInfo("Giro", "VARCHAR", 100, true)
        );

        List<String> resolved = spy.resolveValidColumns("dbo", "Empleados", List.of("Rut, RazonSocial", " [Giro] "));

        assertThat(resolved).containsExactly("Rut", "RazonSocial", "Giro");
    }

    @Test
    void resolveValidColumns_throwsSecurityException_whenColumnIsSuspiciousOrNotInTable() {
        SchemaMetadataService spy = spyServiceWithColumns(
                new SchemaMetadataService.ColumnInfo("Rut", "VARCHAR", 20, false)
        );

        assertThatThrownBy(() -> spy.resolveValidColumns("dbo", "Empleados", List.of("Giro")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Giro");
    }

    // --- Auditoría 2026-11: whitelist de esquemas de sistema ---

    // El comodín "dbo" de isTableValid dejaba pasar sys.<X> / INFORMATION_SCHEMA.<X> si existía
    // una tabla de negocio dbo.<X>; el SQL final usa el esquema pedido por el cliente.
    @Test
    void isTableValid_rejectsSystemSchema_evenWhenDboTableWithSameNameExists() throws Exception {
        mockTables("dbo", "objects");

        assertThat(schemaMetadataService.isTableValid("sys", "objects")).isFalse();
        assertThat(schemaMetadataService.isTableValid("[INFORMATION_SCHEMA]", "objects")).isFalse();
        assertThat(schemaMetadataService.isTableValid("dbo", "objects")).isTrue();
    }

    @Test
    void isTableValid_dboWildcard_stillMatchesTableInAnotherBusinessSchema() throws Exception {
        // Flujo existente que se debe conservar: el frontend puede mandar "dbo" para motores
        // donde no conoce el esquema real.
        mockTables("ventas", "Facturas");
        when(tablesResultSet.next()).thenReturn(true, false, true, false); // sin caché: 2 lecturas

        assertThat(schemaMetadataService.isTableValid("dbo", "Facturas")).isTrue();
        assertThat(schemaMetadataService.isTableValid("ventas", "Facturas")).isTrue();
    }

    @Test
    void isTableValid_acceptsQuotedIdentifiers() throws Exception {
        mockTables("dbo", "Empleados");
        when(tablesResultSet.next()).thenReturn(true, false, true, false); // sin caché: 2 lecturas

        assertThat(schemaMetadataService.isTableValid("[dbo]", "[Empleados]")).isTrue();
        assertThat(schemaMetadataService.isTableValid("\"dbo\"", "'Empleados'")).isTrue();
    }

    @Test
    void isTableValid_blankTableName_isFalse() {
        assertThat(schemaMetadataService.isTableValid("dbo", "   ")).isFalse();
    }

    @Test
    void getTables_excludesSystemSchemasAndFlywayHistory_andSortsByName() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getTables(any(), any(), any(), any())).thenReturn(tablesResultSet);
        when(tablesResultSet.next()).thenReturn(true, true, true, true, false);
        when(tablesResultSet.getString("TABLE_SCHEM")).thenReturn("dbo", "sys", "dbo", "dbo");
        when(tablesResultSet.getString("TABLE_NAME")).thenReturn("zeta", "objects", "flyway_schema_history", "Alfa");

        assertThat(schemaMetadataService.getTables()).containsExactly(
                new SchemaMetadataService.TableInfo("dbo", "Alfa"),
                new SchemaMetadataService.TableInfo("dbo", "zeta"));
    }

    @Test
    void getTables_metadataFailure_returnsEmptyListInsteadOfThrowing() throws Exception {
        when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("Connection refused"));

        assertThat(schemaMetadataService.getTables()).isEmpty();
    }

    // --- Filtros y paginación: casos reales ---

    @Test
    void getTableData_selectedColumns_areEscapedAndRestrictedToRealColumns() {
        SchemaMetadataService spy = spyServiceWithColumns(
                new SchemaMetadataService.ColumnInfo("Rut", "VARCHAR", 20, false),
                new SchemaMetadataService.ColumnInfo("Nombre Completo", "VARCHAR", 100, true));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        spy.getTableData("dbo", "Empleados", 15, 30, List.of("rut", "nombre completo"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sql.capture(), params.capture());
        assertThat(sql.getValue()).isEqualTo(
                "SELECT [Rut], [Nombre Completo] FROM [dbo].[Empleados] ORDER BY (SELECT NULL) OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        assertThat(params.getValue()).containsExactly(30, 15);
    }

    @Test
    void getTableData_injectionAttemptInFilterValue_isBoundAsParameterNeverConcatenated() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("nombre", "VARCHAR", 100, true));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        String payload = "x' OR '1'='1'; DROP TABLE Empleados; --";

        spy.getTableData("dbo", "Empleados", 15, 0, null, "nombre", "=", payload);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sql.capture(), params.capture());
        assertThat(sql.getValue()).doesNotContain("DROP").contains("WHERE [nombre] = ?");
        assertThat(params.getValue()[0]).isEqualTo(payload);
    }

    @Test
    void getTableData_operatorIsCaseInsensitive_andIsNotNullNeedsNoValue() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("email", "VARCHAR", 100, true));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        spy.getTableData("dbo", "Empleados", 15, 0, null, "email", " is not null ", null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("WHERE [email] IS NOT NULL");
    }

    @Test
    void getTableData_incompleteFilters_areIgnoredInsteadOfFailing() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("edad", "INT", 4, false));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        spy.getTableData("dbo", "Empleados", 15, 0, null, "edad", "BETWEEN", "18", null); // falta el 2º valor
        spy.getTableData("dbo", "Empleados", 15, 0, null, "edad", "=", null);              // falta el valor
        spy.getTableData("dbo", "Empleados", 15, 0, null, "edad", null, "18");             // falta el operador
        spy.getTableData("dbo", "Empleados", 15, 0, null, "  ", "=", "18");                // columna vacía

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(4)).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).allSatisfy(q -> assertThat(q).doesNotContain("WHERE"));
    }

    @Test
    void getTableData_comparisonOperators_areAllAccepted() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("edad", "INT", 4, false));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        for (String op : List.of("=", ">", "<", ">=", "<=")) {
            spy.getTableData("dbo", "Empleados", 15, 0, null, "edad", op, "30");
        }

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(5)).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).extracting(q -> q.substring(q.indexOf("WHERE"), q.indexOf(" ?")))
                .containsExactly("WHERE [edad] =", "WHERE [edad] >", "WHERE [edad] <", "WHERE [edad] >=", "WHERE [edad] <=");
    }

    @Test
    void getTableData_postgresDialect_usesLimitOffsetWithoutSqlServerOrderBy() {
        when(dynamicDataSourceService.getDialect()).thenReturn(new com.LectorDBTemplate.PushDbTemplate.service.dialect.PostgreSqlDialect());
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("nombre", "VARCHAR", 100, true));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        spy.getTableData("dbo", "Empleados", 20, 40, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sql.capture(), params.capture());
        assertThat(sql.getValue()).isEqualTo("SELECT * FROM \"dbo\".\"Empleados\" LIMIT ? OFFSET ?");
        assertThat(params.getValue()).containsExactly(20, 40);
    }

    @Test
    void getTableCount_nullResult_isTreatedAsZero() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("nombre", "VARCHAR", 100, true));
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class))).thenReturn(null);

        assertThat(spy.getTableCount("dbo", "Empleados", null, null, null, null)).isZero();
    }

    @Test
    void resolveValidColumns_whenMetadataUnavailable_acceptsOnlySyntacticallySafeNames() {
        SchemaMetadataService spy = spyServiceWithColumns(); // sin columnas (metadatos no disponibles)

        assertThat(spy.resolveValidColumns("dbo", "Empleados", List.of("Razón Social", "rut_2"))).containsExactly("Razón Social", "rut_2");
        assertThatThrownBy(() -> spy.resolveValidColumns("dbo", "Empleados", List.of("a]; DROP TABLE x;--")))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> spy.resolveValidColumns("dbo", "Empleados", List.of("*")))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void resolveValidColumns_nullOrEmptyRequest_returnsEmpty() {
        SchemaMetadataService spy = spyServiceWithColumns(new SchemaMetadataService.ColumnInfo("Rut", "VARCHAR", 20, false));

        assertThat(spy.resolveValidColumns("dbo", "Empleados", null)).isEmpty();
        assertThat(spy.resolveValidColumns("dbo", "Empleados", List.of())).isEmpty();
    }

    @Test
    void getColumns_fallsBackToResultSetMetadata_whenDatabaseMetaDataReturnsNothing() throws Exception {
        mockTables("dbo", "Vista");
        ResultSet emptyCols = Mockito.mock(ResultSet.class);
        when(metaData.getColumns(any(), any(), any(), any())).thenReturn(emptyCols);
        when(emptyCols.next()).thenReturn(false);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class))).thenAnswer(inv -> {
            org.springframework.jdbc.core.ResultSetExtractor<?> ex = inv.getArgument(1);
            ResultSet rs = Mockito.mock(ResultSet.class);
            java.sql.ResultSetMetaData rsmd = Mockito.mock(java.sql.ResultSetMetaData.class);
            when(rs.getMetaData()).thenReturn(rsmd);
            when(rsmd.getColumnCount()).thenReturn(1);
            when(rsmd.getColumnLabel(1)).thenReturn("total");
            when(rsmd.getColumnTypeName(1)).thenReturn("int");
            when(rsmd.getColumnDisplaySize(1)).thenReturn(11);
            when(rsmd.isNullable(1)).thenReturn(java.sql.ResultSetMetaData.columnNullable);
            return ex.extractData(rs);
        });

        assertThat(schemaMetadataService.getColumns("dbo", "Vista"))
                .containsExactly(new SchemaMetadataService.ColumnInfo("total", "int", 11, true));
        verify(jdbcTemplate).query(Mockito.eq("SELECT * FROM [dbo].[Vista] WHERE 1 = 0"), any(org.springframework.jdbc.core.ResultSetExtractor.class));
    }
}
