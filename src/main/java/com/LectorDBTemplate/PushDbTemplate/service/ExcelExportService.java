package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.BuiltReportQuery;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.CustomReportQuery;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.ReportColumn;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.TableRef;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.ForeignKeyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * Exportación de datos a Excel (.xlsx) por streaming (Apache POI SXSSF): tanto de una sola
 * tabla como de reportes personalizados multi-tabla. Extraído de la antigua DatabaseService
 * (ver auditoría, hallazgo F6).
 */
@Service
public class ExcelExportService {

    private static final Logger log = LoggerFactory.getLogger(ExcelExportService.class);

    private final JdbcTemplate jdbcTemplate;
    private final SchemaMetadataService schemaMetadataService;
    private final ForeignKeyService foreignKeyService;
    private final CustomReportService customReportService;
    private final long maxExportRows;

    // Limita cuántas exportaciones completas pueden ejecutarse en paralelo: cada una
    // retiene una conexión de HikariCP (pool de tamaño fijo) durante todo el streaming
    // hacia el cliente, así que sin este límite unas pocas exportaciones concurrentes
    // podrían agotar el pool y bloquear el resto de la aplicación.
    private final Semaphore exportSemaphore;
    private final int maxConcurrentExports;

    public ExcelExportService(JdbcTemplate jdbcTemplate,
                               SchemaMetadataService schemaMetadataService,
                               ForeignKeyService foreignKeyService,
                               CustomReportService customReportService,
                               @Value("${app.export.max-rows:50000}") long maxExportRows,
                               @Value("${app.export.max-concurrent:2}") int maxConcurrentExports) {
        this.jdbcTemplate = jdbcTemplate;
        this.schemaMetadataService = schemaMetadataService;
        this.foreignKeyService = foreignKeyService;
        this.customReportService = customReportService;
        this.maxExportRows = maxExportRows;
        this.maxConcurrentExports = maxConcurrentExports;
        this.exportSemaphore = new Semaphore(maxConcurrentExports);
    }

    /**
     * Excepción específica para cuando ya hay demasiadas exportaciones en curso
     * (ver exportSemaphore): cada exportación retiene una conexión de HikariCP durante
     * todo el streaming, así que se limita la concurrencia en vez de dejar que agoten el pool.
     */
    public static class TooManyExportsException extends RuntimeException {
        public TooManyExportsException(String message) {
            super(message);
        }
    }

    // Caracteres que Excel/Sheets puede interpretar como inicio de fórmula al abrir un
    // CSV/XLS exportado ("CSV/Formula Injection"). Se neutralizan con un apóstrofe inicial.
    private static final char[] RISKY_SPREADSHEET_PREFIXES = {'=', '+', '-', '@', '\t', '\r'};

    private String sanitizeForSpreadsheet(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        for (char risky : RISKY_SPREADSHEET_PREFIXES) {
            if (first == risky) {
                return "'" + value;
            }
        }
        return value;
    }

    /**
     * Construye el mapa "valor crudo -> valor descriptivo" para una FK, resolviendo su
     * displayColumn (heurística o explícito) y consultando en lotes seguros (ver SqlSafe).
     * Se registra la clave tanto con el valor crudo como recortado (trim) porque columnas
     * CHAR de ancho fijo en SQL Server suelen traer espacios de relleno.
     * Compartido por exportTableToExcel (una tabla) y exportCustomReportToExcel (multi-tabla)
     * para no duplicar esta lógica dos veces.
     */
    private Map<String, String> buildFkLookupMap(ForeignKeyInfo fk, List<Object> distinctValues) {
        Map<String, String> lookupMap = new HashMap<>();
        if (distinctValues.isEmpty()) {
            return lookupMap;
        }

        String displayColumn = fk.referencedColumn();
        if (fk.displayColumn() != null && !fk.displayColumn().trim().isEmpty()) {
            displayColumn = fk.displayColumn();
        } else {
            try {
                displayColumn = foreignKeyService.resolveDisplayColumn(fk.referencedSchema(), fk.referencedTable(), fk.referencedColumn());
            } catch (Exception e) {
                log.warn("Exportación: No se pudo obtener displayColumn para {}.{}: {}",
                        fk.referencedSchema(), fk.referencedTable(), e.getMessage());
            }
        }

        final List<String> dispCols = new ArrayList<>();
        if (displayColumn != null && !displayColumn.trim().isEmpty()) {
            dispCols.addAll(Arrays.stream(displayColumn.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList());
        }
        if (dispCols.isEmpty()) {
            dispCols.add(fk.referencedColumn());
        }

        List<String> selectCols = new ArrayList<>();
        selectCols.add(fk.referencedColumn());
        for (String c : dispCols) {
            if (!c.equalsIgnoreCase(fk.referencedColumn())) {
                selectCols.add(c);
            }
        }

        String extraCondition = (fk.filterColumn() != null && !fk.filterColumn().trim().isEmpty() && fk.filterValue() != null)
                ? SqlSafe.buildSafeColumnList(List.of(fk.filterColumn())) + " = ?"
                : null;

        try {
            RowCallbackHandler handler = rs -> {
                Object key = rs.getObject(1);
                if (key == null) return;

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < dispCols.size(); i++) {
                    String colName = dispCols.get(i);
                    int selectIdx = selectCols.indexOf(colName);
                    Object targetVal = null;
                    if (selectIdx != -1) {
                        targetVal = rs.getObject(selectIdx + 1);
                    }
                    if (targetVal != null) {
                        if (sb.length() > 0) {
                            sb.append(" - ");
                        }
                        sb.append(targetVal.toString().trim());
                    }
                }
                String kStr = String.valueOf(key);
                String translation = sb.length() > 0 ? sb.toString() : null;
                lookupMap.put(kStr, translation);
                lookupMap.put(kStr.trim(), translation);
            };
            // Troceado en lotes seguros (ver SqlSafe.queryInBatches): distinctValues puede
            // superar los 2.100 parámetros permitidos por SQL Server cuando la exportación
            // cubre la tabla completa en vez de solo una página.
            SqlSafe.queryInBatches(
                    jdbcTemplate,
                    SqlSafe.buildSafeColumnList(selectCols),
                    SqlSafe.buildSafeTableName(fk.referencedSchema(), fk.referencedTable()),
                    SqlSafe.buildSafeColumnList(List.of(fk.referencedColumn())),
                    distinctValues, extraCondition, fk.filterValue(),
                    handler
            );
        } catch (Exception e) {
            log.error("Exportación: Error al resolver FK de la columna {} apuntando a {}.{}: {}",
                    fk.fkColumn(), fk.referencedSchema(), fk.referencedTable(), e.getMessage());
        }
        return lookupMap;
    }

    private String applyFkLookup(Object val, Map<String, String> lookup) {
        String valStr = val.toString();
        if (lookup == null) {
            return null;
        }
        String resolved = lookup.get(valStr);
        if (resolved == null) {
            resolved = lookup.get(valStr.trim());
        }
        return resolved;
    }

    public void exportTableToExcel(String schema, String tableName, List<String> selectedColumns, OutputStream outputStream) throws Exception {
        exportTableToExcel(schema, tableName, selectedColumns, null, null, null, null, outputStream);
    }

    public void exportTableToExcel(
            String schema,
            String tableName,
            List<String> selectedColumns,
            String filterColumn,
            String filterOperator,
            String filterValue,
            OutputStream outputStream) throws Exception {
        exportTableToExcel(schema, tableName, selectedColumns, filterColumn, filterOperator, filterValue, null, outputStream);
    }

    public void exportTableToExcel(
            String schema,
            String tableName,
            List<String> selectedColumns,
            String filterColumn,
            String filterOperator,
            String filterValue,
            String filterValue2,
            OutputStream outputStream) throws Exception {

        if (!exportSemaphore.tryAcquire()) {
            throw new TooManyExportsException(
                "Hay demasiadas exportaciones en curso (máximo " + maxConcurrentExports + " en paralelo permitido). " +
                "Intenta nuevamente en unos segundos.");
        }
        try {
            if (!schemaMetadataService.isTableValid(schema, tableName)) {
                throw new SecurityException("Acceso denegado: Esquema o tabla no válidos (" + schema + "." + tableName + ")");
            }

            List<String> validatedColumns = schemaMetadataService.resolveValidColumns(schema, tableName, selectedColumns);

            long totalRows = schemaMetadataService.getTableCount(schema, tableName, filterColumn, filterOperator, filterValue, filterValue2);
            if (totalRows > maxExportRows) {
                throw new IllegalArgumentException(
                    "La tabla contiene " + totalRows + " filas, por encima del máximo exportable de "
                        + maxExportRows + ". Aplique filtros o contacte al administrador.");
            }

            List<Object> queryParams = new ArrayList<>();
            String condition = schemaMetadataService.buildFilterCondition(schema, tableName, filterColumn, filterOperator, filterValue, filterValue2, queryParams);

            String columnsBuilder = SqlSafe.buildSafeColumnList(validatedColumns);
            String safeTableName = SqlSafe.buildSafeTableName(schema, tableName);
            String sql = "SELECT " + columnsBuilder + " FROM " + safeTableName;
            if (condition != null) {
                sql += " WHERE " + condition;
            }

            log.info("Iniciando exportación .xlsx de reporte completo por streaming para la tabla {}.{} con filtro={} y valor2={}", schema, tableName, condition, filterValue2);

            // Cargar mapas de traducción de claves foráneas activas y válidas
            Map<String, Map<String, String>> fkLookups = new HashMap<>();
            try {
                for (ForeignKeyInfo fk : foreignKeyService.getForeignKeys(schema, tableName)) {
                    if (fk.enabled() && schemaMetadataService.isTableValid(fk.referencedSchema(), fk.referencedTable())) {

                        List<Object> distinctParams = new ArrayList<>();
                        String distinctCondition = schemaMetadataService.buildFilterCondition(schema, tableName, filterColumn, filterOperator, filterValue, filterValue2, distinctParams);

                        String distinctSql = "SELECT DISTINCT [" + fk.fkColumn().replace("]", "]]") + "] "
                                + "FROM " + safeTableName;

                        if (distinctCondition != null) {
                            distinctSql += " WHERE " + distinctCondition + " AND [" + fk.fkColumn().replace("]", "]]") + "] IS NOT NULL";
                        } else {
                            distinctSql += " WHERE [" + fk.fkColumn().replace("]", "]]") + "] IS NOT NULL";
                        }

                        List<Object> distinctValues = jdbcTemplate.query(distinctSql, (rs, rowNum) -> rs.getObject(1), distinctParams.toArray())
                                .stream()
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList();

                        fkLookups.put(fk.fkColumn(), buildFkLookupMap(fk, distinctValues));
                    }
                }
            } catch (Exception e) {
                log.error("Exportación: Error al pre-procesar lookups de claves foráneas", e);
            }

            try (org.apache.poi.xssf.streaming.SXSSFWorkbook workbook = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100)) {
                workbook.setCompressTempFiles(true);
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Reporte");

                org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
                for (int i = 0; i < validatedColumns.size(); i++) {
                    headerRow.createCell(i).setCellValue(validatedColumns.get(i));
                }

                int[] nextRowIndex = {1};
                // Tipado explícito a RowCallbackHandler: un lambda de bloque sin "return" es
                // ambiguo entre RowCallbackHandler.processRow(ResultSet) (void) y
                // ResultSetExtractor<T>.extractData(ResultSet) (retorna T) para algunos
                // compiladores (javac lo resuelve solo; ECJ no, y genera un stub que lanza
                // Error en tiempo de ejecución en vez de fallar el build).
                RowCallbackHandler rowWriter = rs -> {
                    org.apache.poi.ss.usermodel.Row row = sheet.createRow(nextRowIndex[0]++);
                    for (int i = 1; i <= validatedColumns.size(); i++) {
                        String colName = validatedColumns.get(i - 1);
                        Object val = rs.getObject(i);
                        org.apache.poi.ss.usermodel.Cell cell = row.createCell(i - 1);

                        if (val == null) {
                            cell.setBlank();
                        } else {
                            String resolved = applyFkLookup(val, fkLookups.get(colName));
                            if (resolved != null) {
                                cell.setCellValue(sanitizeForSpreadsheet(resolved));
                            } else if (val instanceof Number number) {
                                cell.setCellValue(number.doubleValue());
                            } else {
                                cell.setCellValue(sanitizeForSpreadsheet(val.toString()));
                            }
                        }
                    }
                };
                jdbcTemplate.query(sql, rowWriter, queryParams.toArray());

                workbook.write(outputStream);
                workbook.dispose();
            }
            outputStream.flush();
        } finally {
            exportSemaphore.release();
        }
    }

    /**
     * Exporta el reporte personalizado multi-tabla a un archivo Excel (.xlsx) por streaming.
     * Al igual que exportTableToExcel(), resuelve las columnas con Foreign Key (nativa o
     * virtual) a su valor descriptivo en vez de dejar el ID crudo: antes de esto, la
     * exportación de un solo reporte y la de un reporte multi-tabla se comportaban distinto
     * frente a columnas FK, lo que podía confundir a usuarios de negocio (ver auditoría F13).
     */
    public void exportCustomReportToExcel(CustomReportQuery query, OutputStream outputStream) throws Exception {
        if (!exportSemaphore.tryAcquire()) {
            throw new TooManyExportsException(
                    "Hay demasiadas exportaciones en curso (máximo " + maxConcurrentExports + " en paralelo permitido). " +
                    "Intenta nuevamente en unos segundos.");
        }
        try {
            BuiltReportQuery built = customReportService.buildReportSql(query);

            Long total = jdbcTemplate.queryForObject(built.countSql(), Long.class, built.countParams().toArray());
            long totalRows = total != null ? total : 0L;
            if (totalRows > maxExportRows) {
                throw new IllegalArgumentException(
                        "El reporte contiene " + totalRows + " filas, por encima del máximo exportable de "
                        + maxExportRows + ". Aplique filtros más específicos o contacte al administrador.");
            }

            Map<String, Map<String, String>> fkLookupsByLabel = buildFkLookupsForReportColumns(built);

            try (org.apache.poi.xssf.streaming.SXSSFWorkbook workbook = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100)) {
                workbook.setCompressTempFiles(true);
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Reporte");

                // Encabezados
                org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
                List<ReportColumn> cols = built.validatedColumns();
                for (int i = 0; i < cols.size(); i++) {
                    headerRow.createCell(i).setCellValue(cols.get(i).label());
                }

                int[] nextRowIndex = {1};
                // Ver comentario equivalente en exportTableToExcel: tipado explícito a
                // RowCallbackHandler para no depender de que el compilador resuelva la
                // ambigüedad con ResultSetExtractor<T> por su cuenta.
                RowCallbackHandler rowWriter = rs -> {
                    org.apache.poi.ss.usermodel.Row row = sheet.createRow(nextRowIndex[0]++);
                    for (int i = 1; i <= cols.size(); i++) {
                        Object val = rs.getObject(i);
                        org.apache.poi.ss.usermodel.Cell cell = row.createCell(i - 1);
                        if (val == null) {
                            cell.setBlank();
                        } else {
                            String resolved = applyFkLookup(val, fkLookupsByLabel.get(cols.get(i - 1).label()));
                            if (resolved != null) {
                                cell.setCellValue(sanitizeForSpreadsheet(resolved));
                            } else if (val instanceof Number number) {
                                cell.setCellValue(number.doubleValue());
                            } else {
                                cell.setCellValue(sanitizeForSpreadsheet(val.toString()));
                            }
                        }
                    }
                };
                jdbcTemplate.query(built.selectAllSql(), rowWriter, built.selectAllParams().toArray());

                workbook.write(outputStream);
                workbook.dispose();
            }
            outputStream.flush();
        } finally {
            exportSemaphore.release();
        }
    }

    /**
     * Para cada columna proyectada del reporte que tenga una FK (nativa o virtual) habilitada
     * en su tabla de origen, calcula los valores distintos que toma en el resultado (misma
     * condición WHERE que el reporte) y construye su mapa de traducción. Se indexa por label
     * (el encabezado final en el Excel) porque dos columnas de distinta tabla podrían llamarse
     * igual antes de alias-arse.
     */
    private Map<String, Map<String, String>> buildFkLookupsForReportColumns(BuiltReportQuery built) {
        Map<String, Map<String, String>> fkLookupsByLabel = new HashMap<>();
        for (ReportColumn rc : built.validatedColumns()) {
            TableRef tRef = built.tableAliasMap().get(rc.tableAlias());
            if (tRef == null) {
                continue;
            }
            ForeignKeyInfo fk;
            try {
                fk = foreignKeyService.getForeignKeys(tRef.schema(), tRef.name()).stream()
                        .filter(f -> f.fkColumn().equalsIgnoreCase(rc.column()))
                        .filter(f -> f.enabled() && schemaMetadataService.isTableValid(f.referencedSchema(), f.referencedTable()))
                        .findFirst()
                        .orElse(null);
            } catch (Exception e) {
                log.warn("Exportación de reporte: error al buscar FK de {}.{}.{}: {}", tRef.schema(), tRef.name(), rc.column(), e.getMessage());
                continue;
            }
            if (fk == null) {
                continue;
            }

            String safeCol = "[" + rc.tableAlias() + "].[" + rc.column().replace("]", "]]") + "]";
            String distinctSql = "SELECT DISTINCT " + safeCol + " FROM " + built.fromClauseSql()
                    + (built.whereSql().isEmpty()
                        ? "\nWHERE " + safeCol + " IS NOT NULL"
                        : built.whereSql() + " AND " + safeCol + " IS NOT NULL");

            try {
                List<Object> distinctValues = jdbcTemplate.query(distinctSql, (rs, rowNum) -> rs.getObject(1), built.selectAllParams().toArray())
                        .stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
                fkLookupsByLabel.put(rc.label(), buildFkLookupMap(fk, distinctValues));
            } catch (Exception e) {
                log.error("Exportación de reporte: error al resolver valores distintos de {}.{}: {}", rc.tableAlias(), rc.column(), e.getMessage());
            }
        }
        return fkLookupsByLabel;
    }
}
