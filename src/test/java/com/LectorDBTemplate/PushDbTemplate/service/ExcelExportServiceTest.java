package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.BuiltReportQuery;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.CustomReportQuery;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.ReportColumn;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.TableRef;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.ForeignKeyInfo;
import com.LectorDBTemplate.PushDbTemplate.service.dialect.SqlServerDialect;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exportación a Excel: antes de la auditoría 2026-11 este servicio no tenía ni un test, pese a
 * ser el que más carga genera sobre la BD (streaming de hasta EXPORT_MAX_ROWS filas) y el que
 * escribe datos de negocio en un formato que el usuario abre en Excel (Formula Injection).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExcelExportServiceTest {

    @Mock private DynamicDataSourceService dynamicDataSourceService;
    @Mock private SchemaMetadataService schemaMetadataService;
    @Mock private ForeignKeyService foreignKeyService;
    @Mock private CustomReportService customReportService;
    @Mock private JdbcTemplate jdbcTemplate;

    private ExcelExportService service;

    @BeforeEach
    void setUp() {
        when(dynamicDataSourceService.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(dynamicDataSourceService.getDialect()).thenReturn(new SqlServerDialect());
        service = new ExcelExportService(dynamicDataSourceService, schemaMetadataService, foreignKeyService, customReportService, 1_000, 2);
    }

    /** Tabla dbo.Empleados válida con columnas exportables y conteo dado. */
    private void validTable(long count, String... columns) {
        when(schemaMetadataService.isTableValid("dbo", "Empleados")).thenReturn(true);
        when(schemaMetadataService.resolveValidColumns(eq("dbo"), eq("Empleados"), any())).thenReturn(List.of(columns));
        when(schemaMetadataService.getTableCount(eq("dbo"), eq("Empleados"), any(), any(), any(), any())).thenReturn(count);
    }

    /** Simula el streaming: cada fila se entrega al RowCallbackHandler con getObject(i). */
    private void streamRows(Object[]... rows) throws Exception {
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (Object[] row : rows) {
                ResultSet rs = mock(ResultSet.class);
                for (int i = 0; i < row.length; i++) {
                    when(rs.getObject(i + 1)).thenReturn(row[i]);
                }
                handler.processRow(rs);
            }
            return null;
        }).when(jdbcTemplate).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));
    }

    private static Sheet readSheet(ByteArrayOutputStream out) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray())).getSheetAt(0);
    }

    // --- Exportación de tabla ---

    @Test
    void exportTable_writesHeaderAndTypedCells() throws Exception {
        validTable(2, "id", "nombre", "activo");
        streamRows(new Object[]{1, "Ana", true}, new Object[]{2, null, false});

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportTableToExcel("dbo", "Empleados", List.of("id", "nombre", "activo"), out);

        Sheet sheet = readSheet(out);
        Row header = sheet.getRow(0);
        assertThat(header.getCell(0).getStringCellValue()).isEqualTo("id");
        assertThat(header.getCell(2).getStringCellValue()).isEqualTo("activo");
        assertThat(sheet.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(1.0);
        assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Ana");
        assertThat(sheet.getRow(1).getCell(2).getBooleanCellValue()).isTrue();
        assertThat(sheet.getRow(2).getCell(1).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
        assertThat(sheet.getLastRowNum()).isEqualTo(2);
    }

    // Formula Injection (CSV/Excel injection): un valor que empieza con = + - @ TAB CR se
    // interpretaría como fórmula al abrir el archivo.
    @Test
    void exportTable_prefixesFormulaLikeValuesWithApostrophe() throws Exception {
        validTable(6, "nota");
        streamRows(new Object[]{"=HYPERLINK(\"http://evil\",\"x\")"}, new Object[]{"+1"}, new Object[]{"-2"},
                new Object[]{"@SUM(A1)"}, new Object[]{"\tcmd"}, new Object[]{"normal"});

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportTableToExcel("dbo", "Empleados", List.of("nota"), out);

        Sheet sheet = readSheet(out);
        assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("'=HYPERLINK(\"http://evil\",\"x\")");
        assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("'+1");
        assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("'-2");
        assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("'@SUM(A1)");
        assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("'\tcmd");
        assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("normal");
    }

    @Test
    void exportTable_negativeNumbersStayNumeric_onlyStringsAreSanitized() throws Exception {
        validTable(1, "saldo");
        streamRows(new Object[]{-150.5});

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportTableToExcel("dbo", "Empleados", List.of("saldo"), out);

        assertThat(readSheet(out).getRow(1).getCell(0).getNumericCellValue()).isEqualTo(-150.5);
    }

    @Test
    void exportTable_rejectsTableNotInWhitelist_andReleasesThePermit() throws Exception {
        when(schemaMetadataService.isTableValid("dbo", "Inventada")).thenReturn(false);

        for (int i = 0; i < 5; i++) { // más intentos que permisos: si no se liberara, daría 429
            assertThatThrownBy(() -> service.exportTableToExcel("dbo", "Inventada", List.of("x"), new ByteArrayOutputStream()))
                    .isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void exportTable_aboveMaxRows_isRejectedBeforeStreaming() throws Exception {
        validTable(1_001, "id");

        assertThatThrownBy(() -> service.exportTableToExcel("dbo", "Empleados", List.of("id"), new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1001")
                .hasMessageContaining("1000");
        verify(jdbcTemplate, never()).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));
    }

    @Test
    void exportTable_exactlyMaxRows_isAllowed() throws Exception {
        validTable(1_000, "id");
        streamRows();

        service.exportTableToExcel("dbo", "Empleados", List.of("id"), new ByteArrayOutputStream());

        verify(jdbcTemplate).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));
    }

    @Test
    void exportTable_appliesFilterAndUsesStreamingFetchSize() throws Exception {
        validTable(1, "id", "nombre");
        when(schemaMetadataService.buildFilterCondition(eq("dbo"), eq("Empleados"), eq("nombre"), eq("LIKE"), eq("ana"), any(), any()))
                .thenAnswer(inv -> {
                    List<Object> params = inv.getArgument(6);
                    params.add("%ana%");
                    return "[nombre] LIKE ?";
                });
        ArgumentCaptor<PreparedStatementCreator> psc = ArgumentCaptor.forClass(PreparedStatementCreator.class);

        service.exportTableToExcel("dbo", "Empleados", List.of("id", "nombre"), "nombre", "LIKE", "ana", new ByteArrayOutputStream());

        verify(jdbcTemplate).query(psc.capture(), any(RowCallbackHandler.class));
        Connection con = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(con.prepareStatement(anyString(), eq(ResultSet.TYPE_FORWARD_ONLY), eq(ResultSet.CONCUR_READ_ONLY))).thenReturn(ps);
        psc.getValue().createPreparedStatement(con);

        verify(con).prepareStatement(eq("SELECT [id], [nombre] FROM [dbo].[Empleados] WHERE [nombre] LIKE ?"), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(ps).setFetchSize(ExcelExportService.EXPORT_FETCH_SIZE);
        verify(ps).setString(1, "%ana%");
    }

    @Test
    void exportTable_translatesFkColumnsToDisplayValues() throws Exception {
        validTable(2, "id", "departamento_id");
        when(schemaMetadataService.isTableValid("dbo", "departamento")).thenReturn(true);
        when(foreignKeyService.getForeignKeys("dbo", "Empleados")).thenReturn(List.of(
                new ForeignKeyInfo("departamento_id", "dbo", "departamento", "id", "nombre")));
        // SELECT DISTINCT de valores FK de la tabla exportada
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(new ArrayList<>(List.of(10)));
        // Lookup por lotes contra la tabla referenciada
        doAnswer(inv -> {
            RowCallbackHandler h = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject(1)).thenReturn(10);
            when(rs.getObject(2)).thenReturn("Finanzas");
            h.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        streamRows(new Object[]{1, 10}, new Object[]{2, 99});

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportTableToExcel("dbo", "Empleados", List.of("id", "departamento_id"), out);

        Sheet sheet = readSheet(out);
        assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("10 · Finanzas");
        assertThat(sheet.getRow(2).getCell(1).getNumericCellValue()).isEqualTo(99.0); // huérfano: valor crudo
    }

    // Optimización de la auditoría: las FKs de columnas NO exportadas no generan consultas.
    @Test
    void exportTable_skipsFkLookupsForColumnsThatAreNotExported() throws Exception {
        validTable(1, "id", "nombre");
        when(schemaMetadataService.isTableValid(eq("dbo"), anyString())).thenReturn(true);
        when(foreignKeyService.getForeignKeys("dbo", "Empleados")).thenReturn(List.of(
                new ForeignKeyInfo("departamento_id", "dbo", "departamento", "id", "nombre"),
                new ForeignKeyInfo("cargo_id", "dbo", "cargo", "id", "glosa")));
        streamRows(new Object[]{1, "Ana"});

        service.exportTableToExcel("dbo", "Empleados", List.of("id", "nombre"), new ByteArrayOutputStream());

        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
        verify(jdbcTemplate, never()).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        verify(foreignKeyService, never()).resolveDisplayColumn(any(), any(), any());
    }

    @Test
    void exportTable_fkPreprocessingFailure_doesNotAbortExport() throws Exception {
        validTable(1, "id", "departamento_id");
        when(foreignKeyService.getForeignKeys("dbo", "Empleados")).thenThrow(new RuntimeException("metadatos caídos"));
        streamRows(new Object[]{1, 10});

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportTableToExcel("dbo", "Empleados", List.of("id", "departamento_id"), out);

        assertThat(readSheet(out).getRow(1).getCell(1).getNumericCellValue()).isEqualTo(10.0);
    }

    // --- Concurrencia ---

    @Test
    void export_beyondMaxConcurrent_isRejectedWithTooManyExports_andRecoversAfterwards() throws Exception {
        validTable(1, "id");
        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(jdbcTemplate).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    service.exportTableToExcel("dbo", "Empleados", List.of("id"), new ByteArrayOutputStream());
                    return null;
                });
            }
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.exportTableToExcel("dbo", "Empleados", List.of("id"), new ByteArrayOutputStream()))
                    .isInstanceOf(ExcelExportService.TooManyExportsException.class);
            assertThatThrownBy(() -> service.exportCustomReportToExcel(null, new ByteArrayOutputStream()))
                    .isInstanceOf(ExcelExportService.TooManyExportsException.class);

            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        streamRows();
        service.exportTableToExcel("dbo", "Empleados", List.of("id"), new ByteArrayOutputStream()); // permisos liberados
    }

    // --- Reporte personalizado ---

    private BuiltReportQuery built(List<ReportColumn> cols) {
        return new BuiltReportQuery("SELECT COUNT(*) ...", "paged", "SELECT [t0].[id] AS [Id] FROM ...",
                List.of(), List.of(), List.of("x"), cols, "preview", "from", "", Map.of("t0", new TableRef("dbo", "Empleados", "t0")));
    }

    @Test
    void exportCustomReport_usesLabelsAsHeaders_andSanitizesFormulas() throws Exception {
        CustomReportQuery q = new CustomReportQuery(new TableRef("dbo", "Empleados", "t0"), null, null, null, null, null, null, null);
        when(customReportService.buildReportSql(q)).thenReturn(built(List.of(
                new ReportColumn("t0", "id", "Id"), new ReportColumn("t0", "nota", "Nota"))));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        streamRows(new Object[]{1, "=1+1"});

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.exportCustomReportToExcel(q, out);

        Sheet sheet = readSheet(out);
        assertThat(sheet.getSheetName()).isEqualTo("Reporte Personalizado");
        assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Id");
        assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Nota");
        assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("'=1+1");
    }

    @Test
    void exportCustomReport_aboveMaxRows_isRejected() throws Exception {
        CustomReportQuery q = new CustomReportQuery(new TableRef("dbo", "Empleados", "t0"), null, null, null, null, null, null, null);
        when(customReportService.buildReportSql(q)).thenReturn(built(List.of(new ReportColumn("t0", "id", "Id"))));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(5_000L);

        assertThatThrownBy(() -> service.exportCustomReportToExcel(q, new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5000");
        verify(jdbcTemplate, never()).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));
    }

    @Test
    void exportCustomReport_invalidQuery_propagatesSecurityException_andReleasesPermit() throws Exception {
        when(customReportService.buildReportSql(any())).thenThrow(new SecurityException("Tabla base no válida"));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.exportCustomReportToExcel(null, new ByteArrayOutputStream()))
                    .isInstanceOf(SecurityException.class);
        }
    }

    // --- Trazabilidad (5.12 / 8.12) y enmascaramiento (8.11) ---

    @Test
    void exportTable_addsTraceabilityProperties_andConfidentialFooter_andReturnsRowCount() throws Exception {
        validTable(2, "id");
        streamRows(new Object[]{1}, new Object[]{2});

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long rows = service.exportTableToExcel("dbo", "Empleados", List.of("id"), out);

        assertThat(rows).isEqualTo(2);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            var core = wb.getProperties().getCoreProperties();
            assertThat(core.getCreator()).isEqualTo("anonymous");
            assertThat(core.getTitle()).isEqualTo("dbo.Empleados");
            assertThat(core.getCategory()).isEqualTo(ExcelExportService.CLASSIFICATION);
            assertThat(wb.getProperties().getCustomProperties().getProperty("Clasificacion")).isNotNull();
            assertThat(wb.getSheetAt(0).getFooter().getCenter()).isEqualTo(ExcelExportService.CLASSIFICATION);
        }
    }

    @Test
    void exportTable_masksConfiguredSensitiveColumns() throws Exception {
        ExcelExportService masked = new ExcelExportService(dynamicDataSourceService, schemaMetadataService,
                foreignKeyService, customReportService, 1_000, 2, new SensitiveColumnMasker("sueldo*"));
        validTable(1, "nombre", "sueldo_base");
        streamRows(new Object[]{"Ana", 1_250_000});

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        masked.exportTableToExcel("dbo", "Empleados", List.of("nombre", "sueldo_base"), out);

        Sheet sheet = readSheet(out);
        assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Ana");
        assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo(SensitiveColumnMasker.MASK);
    }
}
