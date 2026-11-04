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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock
    private JdbcTemplate appJdbcTemplate;

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
        return new CustomReportService(dynamicDataSourceService, schemaMetadataService, appJdbcTemplate);
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

    // --- Persistencia de plantillas: siempre contra la BD fija de la app, nunca contra la
    // conexión dinámica de negocio (ver auditoría, hallazgo crítico: antes de este fix, las
    // plantillas guardadas mientras el usuario exploraba, por ejemplo, Postgres o Oracle se
    // perdían silenciosamente porque push_custom_reports solo existe en la BD propia de la app).

    @Test
    void saveReportTemplate_persistsAgainstAppJdbcTemplate_neverAgainstDynamicConnection() {
        CustomReportService service = newService();
        when(appJdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);

        var template = new CustomReportService.ReportTemplate(null, "Mi Reporte", "desc", "{}", null, null);
        var saved = service.saveReportTemplate(template);

        assertThat(saved.id()).isNotBlank();
        assertThat(saved.name()).isEqualTo("Mi Reporte");
        verify(appJdbcTemplate).update(anyString(), anyString(), eq("Mi Reporte"), eq("desc"), eq("{}"));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void getReportTemplates_readsFromAppJdbcTemplate_neverFromDynamicConnection() {
        CustomReportService service = newService();
        when(appJdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(new CustomReportService.ReportTemplate("1", "Reporte", null, "{}", "2024-01-01T00:00:00", "2024-01-01T00:00:00")));

        List<CustomReportService.ReportTemplate> templates = service.getReportTemplates();

        assertThat(templates).hasSize(1);
        assertThat(templates.get(0).name()).isEqualTo("Reporte");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void deleteReportTemplate_deletesAgainstAppJdbcTemplate_neverAgainstDynamicConnection() {
        CustomReportService service = newService();

        service.deleteReportTemplate("abc-123");

        verify(appJdbcTemplate).update(anyString(), eq("abc-123"));
        verifyNoInteractions(jdbcTemplate);
    }

    // --- Auditoría 2026-11: casos reales adicionales del constructor de SQL ---

    private ArgumentCaptor<Object[]> pagedParams() {
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(anyString(), params.capture());
        return params;
    }

    @Test
    void nullQueryOrMissingBaseTable_isRejectedAsBadRequest() {
        CustomReportService service = newService();

        assertThatThrownBy(() -> service.executeCustomReportPreview(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.executeCustomReportPreview(
                new CustomReportQuery(null, null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void baseTable_notInWhitelist_isRejected() {
        CustomReportService service = newService();
        when(schemaMetadataService.isTableValid("sys", "objects")).thenReturn(false);

        assertThatThrownBy(() -> service.executeCustomReportPreview(new CustomReportQuery(
                new TableRef("sys", "objects", "t0"), null, null, null, null, null, null, null)))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void noColumnsSelected_projectsEveryBaseTableColumn() {
        CustomReportService service = newService();
        stubEmptyResult();

        CustomReportResult result = service.executeCustomReportPreview(baseOnlyQuery("t0", null, null, null, null));

        assertThat(result.columns()).extracting(ReportColumn::column).containsExactly("id", "clienteId", "monto", "fecha");
        assertThat(result.generatedSql()).contains("[t0].[monto] AS [monto]");
    }

    @Test
    void column_withoutLabel_getsAliasPrefixedLabel() {
        CustomReportService service = newService();
        stubEmptyResult();

        CustomReportResult result = service.executeCustomReportPreview(
                baseOnlyQuery("t0", List.of(new ReportColumn(null, "monto", "  ")), null, null, null));

        assertThat(result.columns()).containsExactly(new ReportColumn("t0", "monto", "t0_monto"));
    }

    @Test
    void column_notInTable_isRejected() {
        CustomReportService service = newService();

        assertThatThrownBy(() -> service.executeCustomReportPreview(
                baseOnlyQuery("t0", List.of(new ReportColumn("t0", "password", "x")), null, null, null)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void columnLabel_withClosingBracket_isEscapedNotInjected() {
        CustomReportService service = newService();
        stubEmptyResult();

        CustomReportResult result = service.executeCustomReportPreview(
                baseOnlyQuery("t0", List.of(new ReportColumn("t0", "id", "x] FROM sys.objects --")), null, null, null));

        assertThat(result.generatedSql()).contains("AS [x]] FROM sys.objects --]");
    }

    @Test
    void notLikeAndDifferentOperators_bindValuesInOrder_withAndOrLogic() {
        CustomReportService service = newService();
        stubEmptyResult();

        service.executeCustomReportPreview(baseOnlyQuery("t0", List.of(new ReportColumn("t0", "id", "Id")), List.of(
                new ReportFilter("t0", "monto", ">=", "100", null, null),
                new ReportFilter("t0", "fecha", "!=", "2024-01-01", null, "or"),
                new ReportFilter("t0", "clienteId", "not like", "7", null, "XOR") // lógica inválida → AND
        ), null, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("WHERE [t0].[monto] >= ? OR [t0].[fecha] != ? AND [t0].[clienteId] NOT LIKE ?");
        assertThat(params.getValue()).containsExactly("100", "2024-01-01", "%7%", 0, 15);
    }

    @Test
    void filtersWithMissingValues_areSkipped_andDoNotLeaveDanglingLogic() {
        CustomReportService service = newService();
        stubEmptyResult();

        CustomReportResult result = service.executeCustomReportPreview(baseOnlyQuery("t0", List.of(new ReportColumn("t0", "id", "Id")), List.of(
                new ReportFilter("t0", "monto", "BETWEEN", "1", null, "AND"),
                new ReportFilter("t0", "monto", "=", null, null, "AND"),
                new ReportFilter("t0", "monto", "IN", " , ,", null, "AND"),
                new ReportFilter("t0", "  ", "=", "1", null, "AND")
        ), null, null));

        assertThat(result.generatedSql()).doesNotContain("WHERE");
    }

    @Test
    void sqlInjectionInFilterValue_isAlwaysBound() {
        CustomReportService service = newService();
        stubEmptyResult();
        String payload = "1; DROP TABLE Facturas; --";

        CustomReportResult result = service.executeCustomReportPreview(baseOnlyQuery("t0", List.of(new ReportColumn("t0", "id", "Id")),
                List.of(new ReportFilter("t0", "id", "=", payload, null, null)), null, null));

        assertThat(result.generatedSql()).doesNotContain("DROP");
        assertThat(pagedParams().getValue()[0]).isEqualTo(payload);
    }

    @Test
    void sortWithoutDistinct_usesRequestedColumnsAndDirections_andIgnoresUnknownAlias() {
        CustomReportService service = newService();
        stubEmptyResult();

        CustomReportResult result = service.executeCustomReportPreview(baseOnlyQuery("t0", List.of(new ReportColumn("t0", "id", "Id")), null, List.of(
                new ReportSort("t0", "fecha", "desc"),
                new ReportSort(null, "monto", "cualquiera"),
                new ReportSort("zz", "monto", "DESC")
        ), false));

        assertThat(result.generatedSql()).endsWith("ORDER BY [t0].[fecha] DESC, [t0].[monto] ASC");
    }

    @Test
    void noSort_onSqlServer_usesOrderBySelectNullForStablePaginationSyntax() {
        CustomReportService service = newService();
        stubEmptyResult();

        CustomReportResult result = service.executeCustomReportPreview(baseOnlyQuery("t0", List.of(new ReportColumn("t0", "id", "Id")), null, null, null));

        assertThat(result.generatedSql()).endsWith("ORDER BY (SELECT NULL)");
    }

    @Test
    void distinct_countWrapsDistinctSubquery() {
        CustomReportService service = newService();
        stubEmptyResult();

        service.executeCustomReportPreview(baseOnlyQuery("t0", List.of(new ReportColumn("t0", "clienteId", "Cliente")), null, null, true));

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(countSql.capture(), eq(Long.class), any(Object[].class));
        assertThat(countSql.getValue()).contains("SELECT DISTINCT [t0].[clienteId] AS [c0]").contains("AS [__distinct_count_wrapper]");
    }

    @Test
    void limitAndOffset_areClampedLikeTheSingleTableEndpoint() {
        CustomReportService service = newService();
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1_000L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        CustomReportResult big = service.executeCustomReportPreview(new CustomReportQuery(
                new TableRef("dbo", "Facturas", "t0"), null, List.of(new ReportColumn("t0", "id", "Id")), null, null, 5_000, 200, null));
        assertThat(big.limit()).isEqualTo(100);
        assertThat(big.offset()).isEqualTo(200);
        assertThat(big.currentPage()).isEqualTo(3);
        assertThat(big.totalPages()).isEqualTo(10);

        CustomReportResult invalid = service.executeCustomReportPreview(new CustomReportQuery(
                new TableRef("dbo", "Facturas", "t0"), null, List.of(new ReportColumn("t0", "id", "Id")), null, null, -1, -50, null));
        assertThat(invalid.limit()).isEqualTo(15);
        assertThat(invalid.offset()).isZero();
    }

    @Test
    void joinTypeWithJoinSuffix_andMissingOnAlias_areHandled() {
        CustomReportService service = newService();
        stubEmptyResult();

        JoinDefinition ok = new JoinDefinition("full join", new TableRef("dbo", "Clientes", "c"),
                new JoinOn("t0", "clienteId"), new JoinOn("c", "id"));
        CustomReportResult result = service.executeCustomReportPreview(new CustomReportQuery(
                new TableRef("dbo", "Facturas", "t0"), List.of(ok), List.of(new ReportColumn("c", "nombre", "Cliente")), null, null, 15, 0, null));
        assertThat(result.generatedSql()).contains("FULL JOIN [dbo].[Clientes] AS [c]");

        JoinDefinition badAlias = new JoinDefinition("LEFT", new TableRef("dbo", "Clientes", "c"),
                new JoinOn("zz", "clienteId"), new JoinOn("c", "id"));
        assertThatThrownBy(() -> service.executeCustomReportPreview(new CustomReportQuery(
                new TableRef("dbo", "Facturas", "t0"), List.of(badAlias), null, null, null, 15, 0, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void joinWithUnknownOnColumn_isRejected() {
        CustomReportService service = newService();

        JoinDefinition join = new JoinDefinition("LEFT", new TableRef("dbo", "Clientes", "c"),
                new JoinOn("t0", "clienteId"), new JoinOn("c", "id]; DROP TABLE x;--"));

        assertThatThrownBy(() -> service.executeCustomReportPreview(new CustomReportQuery(
                new TableRef("dbo", "Facturas", "t0"), List.of(join), null, null, null, 15, 0, null)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void incompleteJoinEntries_areSkippedSilently() {
        CustomReportService service = newService();
        stubEmptyResult();

        java.util.List<JoinDefinition> joins = new java.util.ArrayList<>();
        joins.add(null);
        joins.add(new JoinDefinition("LEFT", null, null, null));
        CustomReportResult result = service.executeCustomReportPreview(new CustomReportQuery(
                new TableRef("dbo", "Facturas", "t0"), joins, List.of(new ReportColumn("t0", "id", "Id")), null, null, 15, 0, null));

        assertThat(result.generatedSql()).doesNotContain("JOIN");
    }

    @Test
    void postgresDialect_usesDoubleQuotesAndLimitOffset() {
        CustomReportService service = newService();
        when(dynamicDataSourceService.getDialect()).thenReturn(new com.LectorDBTemplate.PushDbTemplate.service.dialect.PostgreSqlDialect());
        stubEmptyResult();

        service.executeCustomReportPreview(baseOnlyQuery("t0", List.of(new ReportColumn("t0", "id", "Id")), null, null, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("\"dbo\".\"Facturas\" AS \"t0\"").endsWith("LIMIT ? OFFSET ?");
        assertThat(params.getValue()).containsExactly(15, 0);
    }

    // --- Plantillas: validación de entrada (auditoría 2026-11) ---

    @Test
    void saveReportTemplate_validatesRequiredFieldsAndColumnLimits() {
        CustomReportService service = newService();

        assertThatThrownBy(() -> service.saveReportTemplate(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveReportTemplate(new CustomReportService.ReportTemplate(null, "  ", null, "{}", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveReportTemplate(new CustomReportService.ReportTemplate(null, "x".repeat(256), null, "{}", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("255");
        assertThatThrownBy(() -> service.saveReportTemplate(new CustomReportService.ReportTemplate(null, "R", "d".repeat(1001), "{}", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1000");
        assertThatThrownBy(() -> service.saveReportTemplate(new CustomReportService.ReportTemplate(null, "R", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("configJson");
        assertThatThrownBy(() -> service.saveReportTemplate(new CustomReportService.ReportTemplate("i".repeat(65), "R", null, "{}", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64");

        verifyNoInteractions(appJdbcTemplate);
    }

    @Test
    void saveReportTemplate_boundaryLengths_areAccepted() {
        CustomReportService service = newService();
        when(appJdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);

        var saved = service.saveReportTemplate(new CustomReportService.ReportTemplate(
                "i".repeat(64), "n".repeat(255), "d".repeat(1000), "{}", null, null));

        assertThat(saved.id()).hasSize(64);
    }

    @Test
    void saveReportTemplate_existingId_updatesInsteadOfInserting_andTrimsName() {
        CustomReportService service = newService();
        when(appJdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("abc"))).thenReturn(1);

        var saved = service.saveReportTemplate(new CustomReportService.ReportTemplate(" abc ", "  Ventas  ", null, "{\"a\":1}", null, null));

        assertThat(saved.id()).isEqualTo("abc");
        assertThat(saved.name()).isEqualTo("Ventas");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(appJdbcTemplate).update(sql.capture(), eq("Ventas"), eq(null), eq("{\"a\":1}"), eq("abc"));
        assertThat(sql.getValue()).startsWith("UPDATE");
    }

    @Test
    void deleteReportTemplate_blankOrNullId_isNoOp() {
        CustomReportService service = newService();

        service.deleteReportTemplate(null);
        service.deleteReportTemplate("   ");

        verifyNoInteractions(appJdbcTemplate);
    }
}
