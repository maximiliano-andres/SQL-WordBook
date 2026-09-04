package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.CustomReportQuery;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.CustomReportResult;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.JoinDefinition;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.JoinOn;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.ReportColumn;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.ReportFilter;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.ReportSort;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.TableRef;
import com.LectorDBTemplate.PushDbTemplate.service.SchemaMetadataService.ColumnInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas del constructor dinámico de SQL para reportes multi-tabla (buildReportSql,
 * ejercitado vía el método público executeCustomReportPreview). Antes de esta clase, este era
 * el código con más ramas condicionales y mayor superficie de construcción dinámica de SQL de
 * todo el proyecto (alias, tipos de JOIN, operadores, DISTINCT) sin ni un solo test — a
 * diferencia de la ruta de una sola tabla, cubierta en SchemaMetadataServiceTest (ver
 * auditoría, hallazgo F2).
 *
 * CustomReportService no se auto-invoca (no tiene métodos @Cacheable propios): a diferencia
 * de SchemaMetadataServiceTest/ForeignKeyServiceTest, no hace falta spy ni el truco de "self",
 * solo mockear SchemaMetadataService como colaborador real.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // no todos los tests tocan ambas tablas mockeadas
class CustomReportServiceTest {

    @Mock
    private DynamicDataSourceService dynamicDataSourceService;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private SchemaMetadataService schemaMetadataService;

    private static final ColumnInfo[] FACTURAS_COLS = {
            new ColumnInfo("id", "INT", 10, false),
            new ColumnInfo("clienteId", "INT", 10, true),
            new ColumnInfo("monto", "DECIMAL", 18, true),
            new ColumnInfo("fecha", "DATETIME", 8, true)
    };
    private static final ColumnInfo[] CLIENTES_COLS = {
            new ColumnInfo("id", "INT", 10, false),
            new ColumnInfo("nombre", "VARCHAR", 100, true)
    };

    private CustomReportService newService() {
        when(dynamicDataSourceService.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(dynamicDataSourceService.getDialect()).thenReturn(new com.LectorDBTemplate.PushDbTemplate.service.dialect.SqlServerDialect());
        when(schemaMetadataService.isTableValid("dbo", "Facturas")).thenReturn(true);
        when(schemaMetadataService.isTableValid("dbo", "Clientes")).thenReturn(true);
        when(schemaMetadataService.getColumns("dbo", "Facturas")).thenReturn(List.of(FACTURAS_COLS));
        when(schemaMetadataService.getColumns("dbo", "Clientes")).thenReturn(List.of(CLIENTES_COLS));
        return new CustomReportService(dynamicDataSourceService, schemaMetadataService);
    }

    private void stubEmptyResult() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    }

    private CustomReportQuery baseOnlyQuery(String baseAlias, List<ReportColumn> columns, List<ReportFilter> filters, List<ReportSort> sorts, Boolean distinct) {
        return new CustomReportQuery(
                new TableRef("dbo", "Facturas", baseAlias),
                List.of(),
                columns,
                filters,
                sorts,
                15, 0, distinct
        );
    }

    // --- Alias de tabla base ---

    @Test
    void baseTable_withoutAlias_defaultsToT0() {
        CustomReportService service = newService();
        stubEmptyResult();

        CustomReportResult result = service.executeCustomReportPreview(baseOnlyQuery(null, null, null, null, null));

        assertThat(result.generatedSql()).contains("[dbo].[Facturas] AS [t0]");
    }

    @Test
    void baseTable_aliasWithInvalidCharacters_isRejected() {
        CustomReportService service = newService();

        assertThatThrownBy(() -> service.executeCustomReportPreview(
                baseOnlyQuery("t0; DROP TABLE Facturas; --", null, null, null, null)))
                .isInstanceOf(SecurityException.class);
    }

    // --- Joins ---

    @Test
    void join_toTableNotInWhitelist_isRejected() {
        CustomReportService service = newService();
        when(schemaMetadataService.isTableValid("dbo", "TablaInventada")).thenReturn(false);

        JoinDefinition join = new JoinDefinition("LEFT",
                new TableRef("dbo", "TablaInventada", "t1"),
                new JoinOn("t0", "id"), new JoinOn("t1", "facturaId"));
        CustomReportQuery query = new CustomReportQuery(
                new TableRef("dbo", "Facturas", "t0"), List.of(join), null, null, null, 15, 0, null);

        assertThatThrownBy(() -> service.executeCustomReportPreview(query))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void join_withDisallowedType_isRejected() {
        CustomReportService service = newService();

        JoinDefinition join = new JoinDefinition("DELETE",
                new TableRef("dbo", "Clientes", "t1"),
                new JoinOn("t0", "clienteId"), new JoinOn("t1", "id"));
        CustomReportQuery query = new CustomReportQuery(
                new TableRef("dbo", "Facturas", "t0"), List.of(join), null, null, null, 15, 0, null);

        assertThatThrownBy(() -> service.executeCustomReportPreview(query))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void join_duplicateAlias_isRejected() {
        CustomReportService service = newService();

        JoinDefinition join = new JoinDefinition("LEFT",
                new TableRef("dbo", "Clientes", "t0"), // mismo alias que la tabla base
                new JoinOn("t0", "clienteId"), new JoinOn("t0", "id"));
        CustomReportQuery query = new CustomReportQuery(
                new TableRef("dbo", "Facturas", "t0"), List.of(join), null, null, null, 15, 0, null);

        assertThatThrownBy(() -> service.executeCustomReportPreview(query))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void innerJoin_buildsCorrectJoinClauseAndOnCondition() {
        CustomReportService service = newService();
        stubEmptyResult();

        JoinDefinition join = new JoinDefinition("INNER",
                new TableRef("dbo", "Clientes", "t1"),
                new JoinOn("t0", "clienteId"), new JoinOn("t1", "id"));
        CustomReportQuery query = new CustomReportQuery(
                new TableRef("dbo", "Facturas", "t0"), List.of(join),
                List.of(new ReportColumn("t0", "id", "Factura")),
                null, null, 15, 0, null);

        CustomReportResult result = service.executeCustomReportPreview(query);

        assertThat(result.generatedSql())
                .contains("INNER JOIN [dbo].[Clientes] AS [t1]")
                .contains("ON [t0].[clienteId] = [t1].[id]");
    }

    // --- Filtros (WHERE) ---

    @Test
    void betweenFilter_onJoinedTableAlias_bindsTwoParamsInOrder() {
        CustomReportService service = newService();
        stubEmptyResult();

        JoinDefinition join = new JoinDefinition("LEFT",
                new TableRef("dbo", "Clientes", "t1"),
                new JoinOn("t0", "clienteId"), new JoinOn("t1", "id"));
        ReportFilter filter = new ReportFilter("t0", "monto", "BETWEEN", "1000", "5000", "AND");
        CustomReportQuery query = new CustomReportQuery(
                new TableRef("dbo", "Facturas", "t0"), List.of(join),
                List.of(new ReportColumn("t0", "id", "Factura")),
                List.of(filter), null, 15, 0, null);

        service.executeCustomReportPreview(query);

        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(anyString(), params.capture());
        assertThat(params.getValue()).containsExactly("1000", "5000", 0, 15);
    }

    @Test
    void inFilter_splitsCommaSeparatedValueIntoPlaceholders() {
        CustomReportService service = newService();
        stubEmptyResult();

        ReportFilter filter = new ReportFilter("t0", "id", "IN", "1, 2, 3", null, "AND");
        CustomReportQuery query = baseOnlyQuery("t0",
                List.of(new ReportColumn("t0", "id", "Factura")),
                List.of(filter), null, null);

        CustomReportResult result = service.executeCustomReportPreview(query);

        assertThat(result.generatedSql()).contains("[t0].[id] IN (?, ?, ?)");
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(anyString(), params.capture());
        assertThat(params.getValue()).containsExactly("1", "2", "3", 0, 15);
    }

    @Test
    void isNullFilter_needsNoBoundValue() {
        CustomReportService service = newService();
        stubEmptyResult();

        ReportFilter filter = new ReportFilter("t0", "clienteId", "IS NULL", null, null, "AND");
        CustomReportQuery query = baseOnlyQuery("t0",
                List.of(new ReportColumn("t0", "id", "Factura")),
                List.of(filter), null, null);

        CustomReportResult result = service.executeCustomReportPreview(query);

        assertThat(result.generatedSql()).contains("[t0].[clienteId] IS NULL");
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(anyString(), params.capture());
        assertThat(params.getValue()).containsExactly(0, 15);
    }

    @Test
    void filter_onUnknownTableAlias_isRejected() {
        CustomReportService service = newService();

        ReportFilter filter = new ReportFilter("t9", "id", "=", "1", null, "AND");
        CustomReportQuery query = baseOnlyQuery("t0", null, List.of(filter), null, null);

        assertThatThrownBy(() -> service.executeCustomReportPreview(query))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filter_withDisallowedOperator_isRejected() {
        CustomReportService service = newService();

        ReportFilter filter = new ReportFilter("t0", "id", "1=1; DROP TABLE Facturas; --", "1", null, "AND");
        CustomReportQuery query = baseOnlyQuery("t0", null, List.of(filter), null, null);

        assertThatThrownBy(() -> service.executeCustomReportPreview(query))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- DISTINCT + ORDER BY ---

    @Test
    void distinct_orderByColumnNotInSelect_isDroppedAndFallsBackToFirstSelectedColumn() {
        CustomReportService service = newService();
        stubEmptyResult();

        ReportSort sortOnUnselectedColumn = new ReportSort("t0", "fecha", "DESC");
        CustomReportQuery query = baseOnlyQuery("t0",
                List.of(new ReportColumn("t0", "id", "Factura"), new ReportColumn("t0", "monto", "Monto")),
                null, List.of(sortOnUnselectedColumn), true);

        CustomReportResult result = service.executeCustomReportPreview(query);

        assertThat(result.generatedSql())
                .contains("SELECT DISTINCT")
                .doesNotContain("[t0].[fecha]")
                .contains("ORDER BY [t0].[id] ASC");
    }

    @Test
    void distinct_orderByColumnInSelect_isRespectedWithRequestedDirection() {
        CustomReportService service = newService();
        stubEmptyResult();

        ReportSort sortOnSelectedColumn = new ReportSort("t0", "monto", "DESC");
        CustomReportQuery query = baseOnlyQuery("t0",
                List.of(new ReportColumn("t0", "id", "Factura"), new ReportColumn("t0", "monto", "Monto")),
                null, List.of(sortOnSelectedColumn), true);

        CustomReportResult result = service.executeCustomReportPreview(query);

        assertThat(result.generatedSql()).contains("ORDER BY [t0].[monto] DESC");
    }

    // --- Resultado ---

    @Test
    void executeCustomReportPreview_mapsTotalRowsAndPaginationFromJdbc() {
        CustomReportService service = newService();
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        CustomReportQuery query = baseOnlyQuery("t0",
                List.of(new ReportColumn("t0", "id", "Factura")), null, null, null);

        CustomReportResult result = service.executeCustomReportPreview(query);

        assertThat(result.totalRows()).isEqualTo(42L);
        assertThat(result.currentPage()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(3); // ceil(42 / 15)
    }
}
