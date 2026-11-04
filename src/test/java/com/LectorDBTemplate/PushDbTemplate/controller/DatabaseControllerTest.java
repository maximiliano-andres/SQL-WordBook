package com.LectorDBTemplate.PushDbTemplate.controller;

import com.LectorDBTemplate.PushDbTemplate.config.SecurityConfig;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService;
import com.LectorDBTemplate.PushDbTemplate.service.DatabaseDiagnosticsService;
import com.LectorDBTemplate.PushDbTemplate.service.DynamicDataSourceService;
import com.LectorDBTemplate.PushDbTemplate.service.ExcelExportService;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.ForeignKeyResolution;
import com.LectorDBTemplate.PushDbTemplate.service.SchemaMetadataService;
import com.LectorDBTemplate.PushDbTemplate.service.SchemaMetadataService.TableInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test del controller: verifica que /api/db/** exige autenticación
 * (hallazgo crítico de la auditoría) y que las excepciones de seguridad
 * de los servicios se traducen al código HTTP correcto.
 *
 * El controller ahora depende de 5 servicios especializados en vez de una sola
 * DatabaseService (ver auditoría, hallazgo F6), así que cada uno se mockea por separado.
 */
@WebMvcTest(controllers = DatabaseController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.security.username=test-user",
        "app.security.password=test-pass"
})
class DatabaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SchemaMetadataService schemaMetadataService;
    @MockitoBean
    private ForeignKeyService foreignKeyService;
    @MockitoBean
    private CustomReportService customReportService;
    @MockitoBean
    private ExcelExportService excelExportService;
    @MockitoBean
    private DatabaseDiagnosticsService databaseDiagnosticsService;
    @MockitoBean
    private DynamicDataSourceService dynamicDataSourceService;

    @Test
    void tablesEndpoint_withoutCredentials_returns401() throws Exception {
        mockMvc.perform(get("/api/db/tables"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tablesEndpoint_withValidCredentials_returns200() throws Exception {
        when(schemaMetadataService.getTables()).thenReturn(List.of(new TableInfo("dbo", "Empleados")));

        mockMvc.perform(get("/api/db/tables")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("test-user", "test-pass")))
                .andExpect(status().isOk());
    }

    @Test
    void tablesEndpoint_withWrongCredentials_returns401() throws Exception {
        mockMvc.perform(get("/api/db/tables")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("test-user", "wrong-pass")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void columnsEndpoint_whenServiceRejectsTable_returns403() throws Exception {
        when(schemaMetadataService.getColumns("dbo", "TablaInventada"))
                .thenThrow(new SecurityException("Acceso denegado"));

        mockMvc.perform(get("/api/db/tables/dbo/TablaInventada/columns")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("test-user", "test-pass")))
                .andExpect(status().isForbidden());
    }

    @Test
    void dataEndpoint_withValidFilterParams_returns200() throws Exception {
        when(schemaMetadataService.getTableCount("dbo", "Empleados", "nombre", "LIKE", "ana", null)).thenReturn(1L);
        when(schemaMetadataService.getTableData("dbo", "Empleados", 15, 0, null, "nombre", "LIKE", "ana", null))
                .thenReturn(List.of(Map.of("nombre", "ana")));
        when(foreignKeyService.resolveForeignKeys(eq("dbo"), eq("Empleados"), any()))
                .thenReturn(new ForeignKeyResolution(List.of(), Map.of()));

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/data")
                        .param("filterColumn", "nombre")
                        .param("filterOperator", "LIKE")
                        .param("filterValue", "ana")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("test-user", "test-pass")))
                .andExpect(status().isOk());
    }

    @Test
    void dataEndpoint_whenFilterColumnRejectedByService_returns403() throws Exception {
        when(schemaMetadataService.getTableCount(eq("dbo"), eq("Empleados"), eq("nombre]; DROP TABLE Empleados; --"), any(), any(), any()))
                .thenThrow(new SecurityException("Acceso denegado: Nombre de columna no válido para el filtro"));

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/data")
                        .param("filterColumn", "nombre]; DROP TABLE Empleados; --")
                        .param("filterOperator", "=")
                        .param("filterValue", "x")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("test-user", "test-pass")))
                .andExpect(status().isForbidden());
    }

    @Test
    void dataEndpoint_whenFilterOperatorRejectedByService_returns400() throws Exception {
        when(schemaMetadataService.getTableCount(eq("dbo"), eq("Empleados"), eq("nombre"), eq("1=1; DROP TABLE Empleados; --"), any(), any()))
                .thenThrow(new IllegalArgumentException("Operador de filtro no permitido"));

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/data")
                        .param("filterColumn", "nombre")
                        .param("filterOperator", "1=1; DROP TABLE Empleados; --")
                        .param("filterValue", "x")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("test-user", "test-pass")))
                .andExpect(status().isBadRequest());
    }

    // Hallazgo de auditoría: el frontend reenviaba usuario/contraseña en cada petición porque
    // la política de sesión era STATELESS. Con IF_REQUIRED, un login por Basic exitoso crea una
    // sesión de servidor y las peticiones siguientes deben poder autenticarse solo con esa
    // cookie, sin volver a enviar credenciales.
    @Test
    void sessionCookie_fromPriorBasicAuth_authenticatesSubsequentRequestWithoutCredentials() throws Exception {
        when(schemaMetadataService.getTables()).thenReturn(List.of(new TableInfo("dbo", "Empleados")));

        var firstResult = mockMvc.perform(get("/api/db/tables")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("test-user", "test-pass")))
                .andExpect(status().isOk())
                .andReturn();

        var session = firstResult.getRequest().getSession(false);
        org.junit.jupiter.api.Assertions.assertNotNull(session, "Se esperaba que el login creara una sesión de servidor");

        mockMvc.perform(get("/api/db/tables").session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorHealth_isPublic_withoutCredentials() throws Exception {
        // @WebMvcTest no carga la autoconfiguración de Actuator, así que la ruta
        // no tiene handler (404) — lo que importa es que Security no la bloquee
        // antes con un 401, que es lo que confirma esta aserción.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound());
    }

    // =====================================================================================
    // Auditoría 2026-11: cobertura por endpoint (auth, contrato de respuesta y errores)
    // =====================================================================================

    private static org.springframework.test.web.servlet.request.RequestPostProcessor auth() {
        return SecurityMockMvcRequestPostProcessors.httpBasic("test-user", "test-pass");
    }

    // Todos los endpoints de /api/db/** (salvo presets) deben exigir autenticación.
    @org.junit.jupiter.params.ParameterizedTest(name = "{0} {1}")
    @org.junit.jupiter.params.provider.CsvSource({
            "GET, /api/db/info",
            "GET, /api/db/connection/current",
            "POST, /api/db/connection/test",
            "POST, /api/db/connection/connect",
            "GET, /api/db/tables",
            "GET, /api/db/tables/dbo/Empleados/columns",
            "GET, /api/db/tables/dbo/Empleados/data",
            "GET, /api/db/tables/dbo/Empleados/export?columns=id",
            "POST, /api/db/tables/dbo/Empleados/custom-fks",
            "GET, /api/db/custom-reports/suggest-joins?schema=dbo&table=Empleados",
            "POST, /api/db/custom-reports/preview",
            "POST, /api/db/custom-reports/export",
            "GET, /api/db/custom-reports/templates",
            "POST, /api/db/custom-reports/templates",
            "DELETE, /api/db/custom-reports/templates/abc"
    })
    void everyProtectedEndpoint_withoutCredentials_returns401Json_andNeverReachesServices(String method, String url) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .request(org.springframework.http.HttpMethod.valueOf(method), url)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate")) // sin popup nativo del navegador
                .andExpect(jsonPath("$.authenticated").value(false));

        org.mockito.Mockito.verifyNoInteractions(schemaMetadataService, foreignKeyService, customReportService,
                excelExportService, databaseDiagnosticsService, dynamicDataSourceService);
    }

    @Test
    void presets_isPublic_forTheLoginScreen() throws Exception {
        when(dynamicDataSourceService.getPresets()).thenReturn(List.of(Map.of("engineType", "SQLSERVER", "defaultPort", 1433)));

        mockMvc.perform(get("/api/db/connection/presets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].engineType").value("SQLSERVER"));
    }

    @Test
    void securityHeaders_includeCspAndNoSniff() throws Exception {
        when(schemaMetadataService.getTables()).thenReturn(List.of());

        mockMvc.perform(get("/api/db/tables").with(auth()))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("X-Frame-Options"));
    }

    // --- /info y /connection/* ---

    @Test
    void info_returnsDiagnostics() throws Exception {
        when(databaseDiagnosticsService.getDatabaseInfo()).thenReturn(Map.of("engineType", "SQLSERVER", "totalTables", 12));

        mockMvc.perform(get("/api/db/info").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTables").value(12));
    }

    @Test
    void currentConnection_returnsSanitizedSummary() throws Exception {
        when(dynamicDataSourceService.getCurrentConnectionSummary()).thenReturn(Map.of("engineType", "SQLSERVER", "jdbcUrl", "jdbc:sqlserver://h;password=******"));

        mockMvc.perform(get("/api/db/connection/current").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jdbcUrl").value("jdbc:sqlserver://h;password=******"));
    }

    @Test
    void testConnection_alwaysReturns200_withSuccessFlagFromService() throws Exception {
        when(dynamicDataSourceService.testConnection(any())).thenReturn(
                new DynamicDataSourceService.TestResult(false, "Error de conexión", 0, null, null, null));

        mockMvc.perform(post("/api/db/connection/test").with(auth())
                        .contentType("application/json")
                        .content("{\"engineType\":\"POSTGRESQL\",\"host\":\"db\",\"port\":5432,\"databaseName\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void connect_success_returns200_failure_returns400WithMessage() throws Exception {
        when(dynamicDataSourceService.connectTo(any()))
                .thenReturn(new DynamicDataSourceService.ConnectResult(true, "Conectado", Map.of("engineType", "SQLITE")))
                .thenReturn(new DynamicDataSourceService.ConnectResult(false, "Parámetro JDBC no permitido", null));
        String body = "{\"engineType\":\"CUSTOM\",\"customJdbcUrl\":\"jdbc:sqlite::memory:\"}";

        mockMvc.perform(post("/api/db/connection/connect").with(auth()).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionInfo.engineType").value("SQLITE"));
        mockMvc.perform(post("/api/db/connection/connect").with(auth()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Parámetro JDBC no permitido"));
    }

    @Test
    void connect_bodyIsBoundToConnectionConfig() throws Exception {
        when(dynamicDataSourceService.connectTo(any())).thenReturn(new DynamicDataSourceService.ConnectResult(true, "ok", Map.of()));

        mockMvc.perform(post("/api/db/connection/connect").with(auth()).contentType("application/json")
                .content("{\"engineType\":\"MYSQL\",\"host\":\"h\",\"port\":3306,\"databaseName\":\"db\",\"username\":\"u\",\"password\":\"p\",\"additionalParams\":{\"useSSL\":\"false\"}}"));

        org.mockito.ArgumentCaptor<DynamicDataSourceService.ConnectionConfig> cfg = org.mockito.ArgumentCaptor.forClass(DynamicDataSourceService.ConnectionConfig.class);
        org.mockito.Mockito.verify(dynamicDataSourceService).connectTo(cfg.capture());
        org.assertj.core.api.Assertions.assertThat(cfg.getValue().port()).isEqualTo(3306);
        org.assertj.core.api.Assertions.assertThat(cfg.getValue().additionalParams()).containsEntry("useSSL", "false");
    }

    // --- /tables, /columns ---

    @Test
    void tables_returnsListWithPrivateCacheHeader() throws Exception {
        when(schemaMetadataService.getTables()).thenReturn(List.of(new TableInfo("dbo", "Empleados")));

        mockMvc.perform(get("/api/db/tables").with(auth()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("max-age=30"), org.hamcrest.Matchers.containsString("private"))))
                .andExpect(jsonPath("$[0].schema").value("dbo"))
                .andExpect(jsonPath("$[0].name").value("Empleados"));
    }

    @Test
    void columns_returnsColumnInfo() throws Exception {
        when(schemaMetadataService.getColumns("dbo", "Empleados")).thenReturn(List.of(
                new SchemaMetadataService.ColumnInfo("nombre", "VARCHAR", 100, true)));

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/columns").with(auth()))
                .andExpect(status().isOk())
                .andExpect(header().exists("Cache-Control"))
                .andExpect(jsonPath("$[0].name").value("nombre"))
                .andExpect(jsonPath("$[0].nullable").value(true));
    }

    @Test
    void columns_withUrlEncodedNames_areDecodedBeforeReachingService() throws Exception {
        when(schemaMetadataService.getColumns("ventas", "Detalle Factura")).thenReturn(List.of());

        // Variable de plantilla: MockMvc la codifica como "Detalle%20Factura", igual que
        // encodeURIComponent en el frontend (un literal "%20" se re-codificaría a "%2520").
        mockMvc.perform(get("/api/db/tables/{schema}/{name}/columns", "ventas", "Detalle Factura").with(auth()))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(schemaMetadataService).getColumns("ventas", "Detalle Factura");
    }

    // --- /data ---

    private void stubData(long total) {
        when(schemaMetadataService.getTableCount(eq("dbo"), eq("Empleados"), any(), any(), any(), any())).thenReturn(total);
        when(schemaMetadataService.getTableData(eq("dbo"), eq("Empleados"), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(Map.of("id", 1)));
        when(foreignKeyService.resolveForeignKeys(eq("dbo"), eq("Empleados"), any()))
                .thenReturn(new ForeignKeyResolution(List.of(), Map.of()));
    }

    @Test
    void data_defaultPagination_isLimit15Offset0_andComputesPages() throws Exception {
        stubData(31);

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/data").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(15))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.currentPage").value(1))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.totalRows").value(31))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.fkColumns").isArray());
    }

    @Test
    void data_limitAbove100_isClampedTo100_negativeOffsetTo0() throws Exception {
        stubData(1000);

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/data").param("limit", "100000").param("offset", "-5").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(100))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.totalPages").value(10));
        org.mockito.Mockito.verify(schemaMetadataService).getTableData("dbo", "Empleados", 100, 0, null, null, null, null, null);
    }

    @Test
    void data_limitZeroOrNegative_isClampedTo1() throws Exception {
        stubData(3);

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/data").param("limit", "0").with(auth()))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void data_secondPage_reportsCurrentPage2() throws Exception {
        stubData(40);

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/data").param("limit", "20").param("offset", "20").with(auth()))
                .andExpect(jsonPath("$.currentPage").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void data_emptyTable_returnsZeroPages() throws Exception {
        stubData(0);

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/data").with(auth()))
                .andExpect(jsonPath("$.totalRows").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void data_columnsAndBetweenFilter_arePassedThrough() throws Exception {
        stubData(1);

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/data").with(auth())
                .param("columns", "id", "nombre")
                .param("filterColumn", "edad").param("filterOperator", "BETWEEN")
                .param("filterValue", "18").param("filterValue2", "65"));

        org.mockito.Mockito.verify(schemaMetadataService).getTableData("dbo", "Empleados", 15, 0,
                List.of("id", "nombre"), "edad", "BETWEEN", "18", "65");
        org.mockito.Mockito.verify(schemaMetadataService).getTableCount("dbo", "Empleados", "edad", "BETWEEN", "18", "65");
    }

    // Antes: limit=abc → MethodArgumentTypeMismatchException → handler genérico → 500.
    @Test
    void data_nonNumericLimit_returns400NotInternalError() throws Exception {
        mockMvc.perform(get("/api/db/tables/dbo/Empleados/data").param("limit", "abc").with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("limit")));
        org.mockito.Mockito.verifyNoInteractions(schemaMetadataService);
    }

    @Test
    void data_databaseError_returns400WithCorrelationId_andHidesSqlDetails() throws Exception {
        when(schemaMetadataService.getTableCount(eq("dbo"), eq("Empleados"), any(), any(), any(), any()))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException("count",
                        "SELECT COUNT(*) FROM [dbo].[Empleados] WHERE [sueldo_secreto] = ?",
                        new java.sql.SQLException("Invalid column name 'sueldo_secreto'")));

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/data").with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sueldo_secreto"))));
    }

    @Test
    void data_unexpectedError_returns500WithCorrelationId_andHidesMessage() throws Exception {
        when(schemaMetadataService.getTableCount(eq("dbo"), eq("Empleados"), any(), any(), any(), any()))
                .thenThrow(new NullPointerException("detalle interno com.LectorDBTemplate.X"));

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/data").with(auth()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("com.LectorDBTemplate"))));
    }

    // --- /export ---

    @Test
    void export_setsXlsxContentTypeAndUtf8AttachmentFilename() throws Exception {
        mockMvc.perform(get("/api/db/tables/dbo/Planificación/export").param("columns", "id").with(auth()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("attachment"),
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''dbo_Planificaci%C3%B3n_report.xlsx"))));
        org.mockito.Mockito.verify(excelExportService).exportTableToExcel(eq("dbo"), eq("Planificación"), eq(List.of("id")),
                eq(null), eq(null), eq(null), eq(null), any());
    }

    @Test
    void export_withoutColumnsParam_returns400() throws Exception {
        mockMvc.perform(get("/api/db/tables/dbo/Empleados/export").with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("columns")));
    }

    @Test
    void export_tooManyConcurrent_returns429Json() throws Exception {
        org.mockito.Mockito.doThrow(new ExcelExportService.TooManyExportsException("Hay demasiadas exportaciones"))
                .when(excelExportService).exportTableToExcel(any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/export").param("columns", "id").with(auth()))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value("Hay demasiadas exportaciones"));
    }

    @Test
    void export_aboveMaxRows_returns400JsonWithMessage() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("La tabla contiene 2000000 filas"))
                .when(excelExportService).exportTableToExcel(any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/export").param("columns", "id").with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La tabla contiene 2000000 filas"));
    }

    @Test
    void export_tableNotInWhitelist_returns403() throws Exception {
        org.mockito.Mockito.doThrow(new SecurityException("Acceso denegado"))
                .when(excelExportService).exportTableToExcel(any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(get("/api/db/tables/sys/objects/export").param("columns", "name").with(auth()))
                .andExpect(status().isForbidden());
    }

    // --- /custom-fks ---

    @Test
    void customFks_validBody_returns200_andPassesParsedList() throws Exception {
        mockMvc.perform(post("/api/db/tables/dbo/Empleados/custom-fks").with(auth()).contentType("application/json")
                        .content("[{\"fkColumn\":\"depto_id\",\"referencedSchema\":\"dbo\",\"referencedTable\":\"Departamento\",\"referencedColumn\":\"id\",\"enabled\":false}]"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<List<ForeignKeyService.ForeignKeyInfo>> fks = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(foreignKeyService).saveCustomFks(eq("dbo"), eq("Empleados"), fks.capture());
        org.assertj.core.api.Assertions.assertThat(fks.getValue().get(0).enabled()).isFalse();
        org.assertj.core.api.Assertions.assertThat(fks.getValue().get(0).custom()).isFalse(); // default del record; el servicio lo fuerza a true
    }

    @Test
    void customFks_invalidEntry_returns400() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Cada relación personalizada requiere fkColumn"))
                .when(foreignKeyService).saveCustomFks(any(), any(), any());

        mockMvc.perform(post("/api/db/tables/dbo/Empleados/custom-fks").with(auth()).contentType("application/json").content("[{}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("fkColumn")));
    }

    // Antes: JSON mal formado → HttpMessageNotReadableException → 500 "error interno".
    @Test
    void customFks_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/db/tables/dbo/Empleados/custom-fks").with(auth()).contentType("application/json").content("[{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El cuerpo de la petición no es un JSON válido."));
        org.mockito.Mockito.verifyNoInteractions(foreignKeyService);
    }

    // --- /custom-reports/* ---

    @Test
    void suggestJoins_returnsSuggestions_andRequiresBothParams() throws Exception {
        when(foreignKeyService.suggestJoinsForTable("dbo", "Empleados")).thenReturn(List.of(
                new ForeignKeyService.SuggestedJoin("LEFT", "dbo", "Empleados", "depto_id", "dbo", "Departamento", "id", "Cruzar")));

        mockMvc.perform(get("/api/db/custom-reports/suggest-joins").param("schema", "dbo").param("table", "Empleados").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].targetTable").value("Departamento"));
        mockMvc.perform(get("/api/db/custom-reports/suggest-joins").param("schema", "dbo").with(auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("table")));
    }

    @Test
    void preview_returnsResult() throws Exception {
        when(customReportService.executeCustomReportPreview(any())).thenReturn(new CustomReportService.CustomReportResult(
                List.of(Map.of("Id", 1)), 1, 15, 0, 1, 1, 5, "SELECT ...", List.of()));

        mockMvc.perform(post("/api/db/custom-reports/preview").with(auth()).contentType("application/json")
                        .content("{\"baseTable\":{\"schema\":\"dbo\",\"name\":\"Empleados\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(1))
                .andExpect(jsonPath("$.generatedSql").value("SELECT ..."));
    }

    @Test
    void preview_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/db/custom-reports/preview").with(auth()).contentType("application/json").content("{bad"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/db/custom-reports/preview").with(auth()).contentType("application/json").content(""))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(customReportService);
    }

    @Test
    void preview_invalidAliasOrTable_mapsTo403_invalidOperator_mapsTo400() throws Exception {
        when(customReportService.executeCustomReportPreview(any()))
                .thenThrow(new SecurityException("Identificador no válido"))
                .thenThrow(new IllegalArgumentException("Operador de filtro no permitido"));
        String body = "{\"baseTable\":{\"schema\":\"dbo\",\"name\":\"Empleados\"}}";

        mockMvc.perform(post("/api/db/custom-reports/preview").with(auth()).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/db/custom-reports/preview").with(auth()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customReportExport_setsXlsxHeaders() throws Exception {
        mockMvc.perform(post("/api/db/custom-reports/export").with(auth()).contentType("application/json")
                        .content("{\"baseTable\":{\"schema\":\"dbo\",\"name\":\"Empleados\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("reporte_personalizado_")));
    }

    @Test
    void customReportExport_errorsReachFrontendAsJson() throws Exception {
        // CustomReports.jsx lee err.error del JSON para mostrar el motivo al usuario.
        org.mockito.Mockito.doThrow(new IllegalArgumentException("El reporte personalizado contiene 90000 filas"))
                .when(excelExportService).exportCustomReportToExcel(any(), any());

        mockMvc.perform(post("/api/db/custom-reports/export").with(auth()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value("El reporte personalizado contiene 90000 filas"));
    }

    @Test
    void templates_listSaveDelete() throws Exception {
        var tpl = new CustomReportService.ReportTemplate("abc", "Ventas", null, "{}", "2026-01-01", "2026-01-01");
        when(customReportService.getReportTemplates()).thenReturn(List.of(tpl));
        when(customReportService.saveReportTemplate(any())).thenReturn(tpl);

        mockMvc.perform(get("/api/db/custom-reports/templates").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("abc"));
        mockMvc.perform(post("/api/db/custom-reports/templates").with(auth()).contentType("application/json")
                        .content("{\"name\":\"Ventas\",\"configJson\":\"{}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ventas"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/db/custom-reports/templates/abc").with(auth()))
                .andExpect(status().isNoContent());
        org.mockito.Mockito.verify(customReportService).deleteReportTemplate("abc");
    }

    @Test
    void templates_saveWithoutName_returns400() throws Exception {
        when(customReportService.saveReportTemplate(any())).thenThrow(new IllegalArgumentException("El nombre del reporte es obligatorio."));

        mockMvc.perform(post("/api/db/custom-reports/templates").with(auth()).contentType("application/json").content("{\"configJson\":\"{}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El nombre del reporte es obligatorio."));
    }
}
