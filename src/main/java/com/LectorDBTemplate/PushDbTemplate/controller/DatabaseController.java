package com.LectorDBTemplate.PushDbTemplate.controller;

import com.LectorDBTemplate.PushDbTemplate.config.AuditLog;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.CustomReportQuery;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.CustomReportResult;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.ReportTemplate;
import com.LectorDBTemplate.PushDbTemplate.service.DatabaseDiagnosticsService;
import com.LectorDBTemplate.PushDbTemplate.service.DynamicDataSourceService;
import com.LectorDBTemplate.PushDbTemplate.service.DynamicDataSourceService.ConnectResult;
import com.LectorDBTemplate.PushDbTemplate.service.DynamicDataSourceService.ConnectionConfig;
import com.LectorDBTemplate.PushDbTemplate.service.DynamicDataSourceService.TestResult;
import com.LectorDBTemplate.PushDbTemplate.service.ExcelExportService;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.ForeignKeyInfo;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.ForeignKeyResolution;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.FkCellResolution;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.ForeignKeyColumnInfo;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.SuggestedJoin;
import com.LectorDBTemplate.PushDbTemplate.service.SchemaMetadataService;
import com.LectorDBTemplate.PushDbTemplate.service.SchemaMetadataService.ColumnInfo;
import com.LectorDBTemplate.PushDbTemplate.service.SchemaMetadataService.TableInfo;
import com.LectorDBTemplate.PushDbTemplate.service.SensitiveColumnMasker;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// El frontend se sirve desde el mismo origen (static/) en producción y el proxy
// de Vite en desarrollo; no se necesita CORS. La API está protegida por
// autenticación (ver SecurityConfig), por lo que abrir CORS ampliaría
// innecesariamente la superficie de ataque.
//
// Delega en 5 servicios especializados (antes: una sola DatabaseService de ~1.700 líneas
// mezclando todo; ver auditoría, hallazgo F6) — cada uno con una responsabilidad concreta:
// metadatos de esquema, resolución de FKs, motor de reportes multi-tabla, exportación a
// Excel, y diagnóstico del servidor.
@RestController
@RequestMapping("/api/db")
public class DatabaseController {

    private static final Logger log = LoggerFactory.getLogger(DatabaseController.class);

    private final SchemaMetadataService schemaMetadataService;
    private final ForeignKeyService foreignKeyService;
    private final CustomReportService customReportService;
    private final ExcelExportService excelExportService;
    private final DatabaseDiagnosticsService databaseDiagnosticsService;
    private final DynamicDataSourceService dynamicDataSourceService;
    private final SensitiveColumnMasker masker;

    public DatabaseController(SchemaMetadataService schemaMetadataService,
                               ForeignKeyService foreignKeyService,
                               CustomReportService customReportService,
                               ExcelExportService excelExportService,
                               DatabaseDiagnosticsService databaseDiagnosticsService,
                               DynamicDataSourceService dynamicDataSourceService,
                               SensitiveColumnMasker masker) {
        this.masker = masker;
        this.schemaMetadataService = schemaMetadataService;
        this.foreignKeyService = foreignKeyService;
        this.customReportService = customReportService;
        this.excelExportService = excelExportService;
        this.databaseDiagnosticsService = databaseDiagnosticsService;
        this.dynamicDataSourceService = dynamicDataSourceService;
    }

    /**
     * Retorna información descriptiva del servidor de base de datos.
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getDatabaseInfo() {
        return ResponseEntity.ok(databaseDiagnosticsService.getDatabaseInfo());
    }

    /**
     * Retorna la configuración de la conexión actual a base de datos (sanitizada).
     */
    @GetMapping("/connection/current")
    public ResponseEntity<Map<String, Object>> getCurrentConnection() {
        return ResponseEntity.ok(dynamicDataSourceService.getCurrentConnectionSummary());
    }

    /**
     * Retorna los motores de base de datos soportados con sus puertos y configuraciones por defecto.
     */
    @GetMapping("/connection/presets")
    public ResponseEntity<List<Map<String, Object>>> getConnectionPresets() {
        return ResponseEntity.ok(dynamicDataSourceService.getPresets());
    }

    /**
     * Prueba conectividad contra una base de datos sin alterar la conexión activa.
     */
    @PostMapping("/connection/test")
    public ResponseEntity<TestResult> testConnection(
            @RequestBody ConnectionConfig config) {
        TestResult result = dynamicDataSourceService.testConnection(config);
        AuditLog.event("CONNECTION_TEST", result != null && result.success() ? AuditLog.SUCCESS : AuditLog.FAILURE,
                connectionDetails(config));
        return ResponseEntity.ok(result);
    }

    /**
     * Conecta y activa en caliente una nueva base de datos.
     */
    @PostMapping("/connection/connect")
    public ResponseEntity<ConnectResult> connect(
            @RequestBody ConnectionConfig config) {
        ConnectResult result = dynamicDataSourceService.connectTo(config);
        AuditLog.event("CONNECTION_SWITCH", result.success() ? AuditLog.SUCCESS : AuditLog.FAILURE, connectionDetails(config));
        if (!result.success()) {
            return ResponseEntity.status(400).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /** Datos del destino para la auditoría: nunca la contraseña ni la URL completa (puede traer credenciales). */
    private static Map<String, Object> connectionDetails(ConnectionConfig config) {
        if (config == null) {
            return Map.of();
        }
        boolean customUrl = config.customJdbcUrl() != null && !config.customJdbcUrl().isBlank();
        return AuditLog.details(
                "engine", config.engineType(),
                "host", customUrl ? DynamicDataSourceService.maskJdbcUrl(config.customJdbcUrl()) : config.host(),
                "port", config.port(),
                "database", config.databaseName(),
                "dbUser", config.username());
    }

    // TTL corto para las cabeceras de caché del navegador en metadata poco cambiante
    // (tablas/columnas): coincide con el TTL de la caché de servidor (ver application.yaml)
    // para no prometer al cliente una frescura mayor a la que el propio backend garantiza.
    private static final CacheControl METADATA_CACHE_CONTROL = CacheControl.maxAge(Duration.ofSeconds(30)).cachePrivate();

    /**
     * Retorna la lista de todas las tablas legibles.
     */
    @GetMapping("/tables")
    public ResponseEntity<List<TableInfo>> getTables() {
        return ResponseEntity.ok().cacheControl(METADATA_CACHE_CONTROL).body(schemaMetadataService.getTables());
    }

    /**
     * Retorna el listado de columnas (esquema) de una tabla.
     */
    @GetMapping("/tables/{schema}/{name}/columns")
    public ResponseEntity<List<ColumnInfo>> getColumns(
            @PathVariable String schema,
            @PathVariable String name) {
        return ResponseEntity.ok().cacheControl(METADATA_CACHE_CONTROL).body(schemaMetadataService.getColumns(schema, name));
    }

    // Estructura moderna de respuesta de paginación.
    // fkColumns/fkResolutions viajan aparte de "data" (que queda intacta) para no romper
    // consumidores existentes del payload de datos crudos.
    public record PageResponse(
            List<Map<String, Object>> data,
            long totalRows,
            int limit,
            int offset,
            int currentPage,
            int totalPages,
            List<ForeignKeyColumnInfo> fkColumns,
            Map<String, List<FkCellResolution>> fkResolutions
    ) {}

    /**
     * Retorna los registros paginados de una tabla con sus metadatos de paginación,
     * más la resolución automática (por lote) de sus columnas Foreign Key.
     */
    @GetMapping("/tables/{schema}/{name}/data")
    public ResponseEntity<PageResponse> getTableData(
            @PathVariable String schema,
            @PathVariable String name,
            @RequestParam(defaultValue = "15") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) List<String> columns,
            @RequestParam(required = false) String filterColumn,
            @RequestParam(required = false) String filterOperator,
            @RequestParam(required = false) String filterValue,
            @RequestParam(required = false) String filterValue2) {

        // Limita a valores seguros (máximo 100 registros por llamada)
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);

        long totalRows = schemaMetadataService.getTableCount(schema, name, filterColumn, filterOperator, filterValue, filterValue2);
        List<Map<String, Object>> data = schemaMetadataService.getTableData(schema, name, safeLimit, safeOffset, columns, filterColumn, filterOperator, filterValue, filterValue2);
        ForeignKeyResolution fkResolution = foreignKeyService.resolveForeignKeys(schema, name, data);
        Map<String, List<FkCellResolution>> fkResolutions = fkResolution.resolutions();
        if (masker.isEnabled() && data != null && !data.isEmpty()) {
            Map<String, String> keys = data.get(0).keySet().stream()
                    .collect(Collectors.toMap(Function.identity(), Function.identity(), (a, b) -> a, LinkedHashMap::new));
            masker.maskRows(data, keys);
            // La traducción de una FK sensible también revelaría el valor: se omite.
            if (fkResolutions != null) {
                fkResolutions = new HashMap<>(fkResolutions);
                fkResolutions.keySet().removeIf(masker::isSensitive);
            }
        }

        int currentPage = (safeLimit == 0) ? 1 : (safeOffset / safeLimit) + 1;
        int totalPages = (safeLimit == 0) ? 1 : (int) Math.ceil((double) totalRows / safeLimit);

        return ResponseEntity.ok(new PageResponse(
                data,
                totalRows,
                safeLimit,
                safeOffset,
                currentPage,
                totalPages,
                fkResolution.columns(),
                fkResolutions
        ));
    }

    /**
     * Endpoint para exportar el reporte completo de registros con columnas seleccionadas en formato Excel (.xlsx).
     */
    @GetMapping("/tables/{schema}/{name}/export")
    public void exportTable(
            @PathVariable String schema,
            @PathVariable String name,
            @RequestParam List<String> columns,
            @RequestParam(required = false) String filterColumn,
            @RequestParam(required = false) String filterOperator,
            @RequestParam(required = false) String filterValue,
            @RequestParam(required = false) String filterValue2,
            HttpServletResponse response) throws Exception {

        // Configurar respuesta para descarga de archivo Excel (.xlsx)
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String filename = schema + "_" + name + "_report.xlsx";

        ContentDisposition contentDisposition = ContentDisposition.builder("attachment")
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString());

        Map<String, Object> details = AuditLog.details(
                "table", schema + "." + name,
                "columns", columns == null ? 0 : columns.size(),
                "filtered", filterColumn != null && !filterColumn.isBlank());
        try {
            long rows = excelExportService.exportTableToExcel(schema, name, columns, filterColumn, filterOperator, filterValue, filterValue2, response.getOutputStream());
            details.put("rows", rows);
            AuditLog.event("EXPORT_TABLE", AuditLog.SUCCESS, details);
        } catch (Exception e) {
            details.put("error", e.getClass().getSimpleName());
            AuditLog.event("EXPORT_TABLE", AuditLog.FAILURE, details);
            throw e;
        }
    }

    /**
     * Endpoint para guardar y activar/desactivar Foreign Keys personalizadas de una tabla.
     */
    @PostMapping("/tables/{schema}/{name}/custom-fks")
    public ResponseEntity<Void> saveCustomFks(
            @PathVariable String schema,
            @PathVariable String name,
            @RequestBody List<ForeignKeyInfo> customFks) {

        foreignKeyService.saveCustomFks(schema, name, customFks);
        AuditLog.event("RELATIONS_SAVE", AuditLog.SUCCESS, AuditLog.details(
                "table", schema + "." + name, "relations", customFks == null ? 0 : customFks.size()));
        return ResponseEntity.ok().build();
    }

    // =========================================================================
    // --- ENDPOINTS PARA REPORTES PERSONALIZADOS MULTI-TABLA ---
    // =========================================================================

    /**
     * Retorna sugerencias inteligentes de cruces (Joins) basados en FKs para una tabla.
     */
    @GetMapping("/custom-reports/suggest-joins")
    public ResponseEntity<List<SuggestedJoin>> suggestJoins(
            @RequestParam String schema,
            @RequestParam String table) {
        return ResponseEntity.ok(foreignKeyService.suggestJoinsForTable(schema, table));
    }

    /**
     * Ejecuta la vista previa paginada de un reporte personalizado multi-tabla.
     */
    @PostMapping("/custom-reports/preview")
    public ResponseEntity<CustomReportResult> previewCustomReport(
            @RequestBody CustomReportQuery query) {
        CustomReportResult result = customReportService.executeCustomReportPreview(query);
        if (masker.isEnabled() && result != null && result.columns() != null) {
            Map<String, String> labelToColumn = new LinkedHashMap<>();
            result.columns().forEach(c -> labelToColumn.put(c.label(), c.column()));
            masker.maskRows(result.data(), labelToColumn);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Exporta el reporte personalizado multi-tabla a un archivo Excel (.xlsx).
     */
    @PostMapping("/custom-reports/export")
    public void exportCustomReport(
            @RequestBody CustomReportQuery query,
            HttpServletResponse response) throws Exception {

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String filename = "reporte_personalizado_" + System.currentTimeMillis() + ".xlsx";

        ContentDisposition contentDisposition = ContentDisposition.builder("attachment")
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString());

        Map<String, Object> details = AuditLog.details(
                "baseTable", query != null && query.baseTable() != null
                        ? query.baseTable().schema() + "." + query.baseTable().name() : null,
                "joins", query != null && query.joins() != null ? query.joins().size() : 0,
                "columns", query != null && query.columns() != null ? query.columns().size() : 0,
                "filters", query != null && query.filters() != null ? query.filters().size() : 0);
        try {
            long rows = excelExportService.exportCustomReportToExcel(query, response.getOutputStream());
            details.put("rows", rows);
            AuditLog.event("EXPORT_CUSTOM_REPORT", AuditLog.SUCCESS, details);
        } catch (Exception e) {
            details.put("error", e.getClass().getSimpleName());
            AuditLog.event("EXPORT_CUSTOM_REPORT", AuditLog.FAILURE, details);
            throw e;
        }
    }

    /**
     * Retorna todas las plantillas de reportes guardadas.
     */
    @GetMapping("/custom-reports/templates")
    public ResponseEntity<List<ReportTemplate>> getReportTemplates() {
        return ResponseEntity.ok(customReportService.getReportTemplates());
    }

    /**
     * Guarda o actualiza una plantilla de reporte personalizado.
     */
    @PostMapping("/custom-reports/templates")
    public ResponseEntity<ReportTemplate> saveReportTemplate(
            @RequestBody ReportTemplate template) {
        ReportTemplate saved = customReportService.saveReportTemplate(template);
        AuditLog.event("TEMPLATE_SAVE", AuditLog.SUCCESS, AuditLog.details(
                "templateId", saved != null ? saved.id() : null,
                "name", saved != null ? saved.name() : null));
        return ResponseEntity.ok(saved);
    }

    /**
     * Elimina una plantilla de reporte por ID.
     */
    @DeleteMapping("/custom-reports/templates/{id}")
    public ResponseEntity<Void> deleteReportTemplate(@PathVariable String id) {
        customReportService.deleteReportTemplate(id);
        AuditLog.event("TEMPLATE_DELETE", AuditLog.SUCCESS, AuditLog.details("templateId", id));
        return ResponseEntity.noContent().build();
    }

    // --- Manejo Centralizado de Excepciones para una API Profesional ---

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> handleSecurityException(SecurityException ex) {
        AuditLog.event("ACCESS_DENIED", AuditLog.DENIED, AuditLog.details("reason", ex.getMessage()));
        return ResponseEntity.status(403).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException ex) {
        return ResponseEntity.status(400).body(Map.of("error", ex.getMessage()));
    }

    // Errores de forma de la petición (JSON mal formado, parámetro obligatorio ausente, tipo
    // inválido como limit=abc). Antes los capturaba handleGeneralException y respondían 500
    // "error interno", lo que ocultaba un error del cliente y ensuciaba el log con stack traces.
    // El mensaje es genérico a propósito: el de Jackson puede incluir nombres de clases internas.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableBody(Exception ex) {
        return ResponseEntity.status(400).body(Map.of("error", "El cuerpo de la petición no es un JSON válido."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(400).body(Map.of("error", "Falta el parámetro obligatorio '" + ex.getParameterName() + "'."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(400).body(Map.of("error", "Valor inválido para el parámetro '" + ex.getName() + "'."));
    }

    @ExceptionHandler(ExcelExportService.TooManyExportsException.class)
    public ResponseEntity<Map<String, String>> handleTooManyExportsException(ExcelExportService.TooManyExportsException ex) {
        return ResponseEntity.status(429).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccessException(DataAccessException ex) {
        // No se expone el mensaje de la causa raíz (rootCause) al cliente: puede contener
        // nombres de columnas/restricciones o fragmentos de la sentencia SQL. El detalle
        // completo queda en el log del servidor correlacionado con un id que sí es seguro
        // devolver (mismo patrón que handleGeneralException).
        String correlationId = UUID.randomUUID().toString();
        log.error("Error de base de datos al ejecutar consulta [{}]", correlationId, ex);
        return ResponseEntity.status(400).body(Map.of(
                "error", "No se pudo ejecutar la consulta contra la base de datos.",
                "correlationId", correlationId
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneralException(Exception ex) {
        // No se expone ex.getMessage() al cliente: puede filtrar detalles de SQL,
        // nombres internos o del driver JDBC. El detalle queda en el log del
        // servidor correlacionado con un id que sí es seguro devolver.
        String correlationId = UUID.randomUUID().toString();
        log.error("Error interno no controlado [{}]", correlationId, ex);
        return ResponseEntity.status(500).body(Map.of(
                "error", "Ocurrió un error interno en el servidor de base de datos.",
                "correlationId", correlationId
        ));
    }
}
