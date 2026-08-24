package com.LectorDBTemplate.PushDbTemplate.controller;

import com.LectorDBTemplate.PushDbTemplate.service.DatabaseService;
import com.LectorDBTemplate.PushDbTemplate.service.DatabaseService.TableInfo;
import com.LectorDBTemplate.PushDbTemplate.service.DatabaseService.ColumnInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// El frontend se sirve desde el mismo origen (static/) en producción y el proxy
// de Vite en desarrollo; no se necesita CORS. La API está protegida por
// autenticación (ver SecurityConfig), por lo que abrir CORS ampliaría
// innecesariamente la superficie de ataque.
@RestController
@RequestMapping("/api/db")
public class DatabaseController {

    private static final Logger log = LoggerFactory.getLogger(DatabaseController.class);

    private final DatabaseService databaseService;

    public DatabaseController(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    /**
     * Retorna información descriptiva del servidor de base de datos.
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getDatabaseInfo() {
        return ResponseEntity.ok(databaseService.getDatabaseInfo());
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
        return ResponseEntity.ok().cacheControl(METADATA_CACHE_CONTROL).body(databaseService.getTables());
    }

    /**
     * Retorna el listado de columnas (esquema) de una tabla.
     */
    @GetMapping("/tables/{schema}/{name}/columns")
    public ResponseEntity<List<ColumnInfo>> getColumns(
            @PathVariable String schema,
            @PathVariable String name) {
        return ResponseEntity.ok().cacheControl(METADATA_CACHE_CONTROL).body(databaseService.getColumns(schema, name));
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
            List<DatabaseService.ForeignKeyColumnInfo> fkColumns,
            Map<String, List<DatabaseService.FkCellResolution>> fkResolutions
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
            @RequestParam(required = false) List<String> columns) {

        // Limita a valores seguros (máximo 100 registros por llamada)
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);

        long totalRows = databaseService.getTableCount(schema, name);
        List<Map<String, Object>> data = databaseService.getTableData(schema, name, safeLimit, safeOffset, columns);
        DatabaseService.ForeignKeyResolution fkResolution = databaseService.resolveForeignKeys(schema, name, data);

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
                fkResolution.resolutions()
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
            jakarta.servlet.http.HttpServletResponse response) throws Exception {

        // Configurar respuesta para descarga de archivo Excel (.xlsx)
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String filename = schema + "_" + name + "_report.xlsx";
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        databaseService.exportTableToExcel(schema, name, columns, response.getOutputStream());
    }

    /**
     * Endpoint para guardar y activar/desactivar Foreign Keys personalizadas de una tabla.
     */
    @PostMapping("/tables/{schema}/{name}/custom-fks")
    public ResponseEntity<Void> saveCustomFks(
            @PathVariable String schema,
            @PathVariable String name,
            @RequestBody List<DatabaseService.ForeignKeyInfo> customFks) {
        
        databaseService.saveCustomFks(schema, name, customFks);
        return ResponseEntity.ok().build();
    }

    // --- Manejo Centralizado de Excepciones para una API Profesional ---

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> handleSecurityException(SecurityException ex) {
        return ResponseEntity.status(403).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException ex) {
        return ResponseEntity.status(400).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(DatabaseService.TooManyExportsException.class)
    public ResponseEntity<Map<String, String>> handleTooManyExportsException(DatabaseService.TooManyExportsException ex) {
        return ResponseEntity.status(429).body(Map.of("error", ex.getMessage()));
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
