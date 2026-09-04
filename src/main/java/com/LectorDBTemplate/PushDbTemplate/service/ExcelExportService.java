package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.BuiltReportQuery;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.CustomReportQuery;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.ReportColumn;
import com.LectorDBTemplate.PushDbTemplate.service.CustomReportService.TableRef;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.ForeignKeyInfo;
import com.LectorDBTemplate.PushDbTemplate.service.dialect.DbDialect;
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
 * Exportación de datos a Excel (.xlsx) por streaming (Apache POI SXSSF) con soporte
 * universal para cualquier motor de base de datos conectado.
 */
@Service
public class ExcelExportService {

    private static final Logger log = LoggerFactory.getLogger(ExcelExportService.class);

    private final DynamicDataSourceService dynamicDataSourceService;
    private final SchemaMetadataService schemaMetadataService;
    private final ForeignKeyService foreignKeyService;
    private final CustomReportService customReportService;
    private final long maxExportRows;

    private final Semaphore exportSemaphore;
    private final int maxConcurrentExports;

    public ExcelExportService(DynamicDataSourceService dynamicDataSourceService,
                               SchemaMetadataService schemaMetadataService,
                               ForeignKeyService foreignKeyService,
                               CustomReportService customReportService,
                               @Value("${app.export.max-rows:50000}") long maxExportRows,
                               @Value("${app.export.max-concurrent:2}") int maxConcurrentExports) {
        this.dynamicDataSourceService = dynamicDataSourceService;
        this.schemaMetadataService = schemaMetadataService;
        this.foreignKeyService = foreignKeyService;
        this.customReportService = customReportService;
        this.maxExportRows = maxExportRows;
        this.maxConcurrentExports = maxConcurrentExports;
        this.exportSemaphore = new Semaphore(maxConcurrentExports);
    }

    private JdbcTemplate getJdbcTemplate() {
        return dynamicDataSourceService.getJdbcTemplate();
    }

    private DbDialect getDialect() {
        return dynamicDataSourceService.getDialect();
    }

    public static class TooManyExportsException extends RuntimeException {
        public TooManyExportsException(String message) {
            super(message);
        }
    }

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

    private Map<String, String> buildFkLookupMap(ForeignKeyInfo fk, List<Object> distinctValues) {
        Map<String, String> lookupMap = new HashMap<>();
        if (distinctValues.isEmpty()) {
            return lookupMap;
        }

        DbDialect dialect = getDialect();
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
                ? SqlSafe.buildSafeColumnList(dialect, List.of(fk.filterColumn())) + " = ?"
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

            SqlSafe.queryInBatches(
                    getJdbcTemplate(),
                    SqlSafe.buildSafeColumnList(dialect, selectCols),
                    SqlSafe.buildSafeTableName(dialect, fk.referencedSchema(), fk.referencedTable()),
                    SqlSafe.buildSafeColumnList(dialect, List.of(fk.referencedColumn())),
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

            DbDialect dialect = getDialect();
            List<String> validatedColumns = schemaMetadataService.resolveValidColumns(schema, tableName, selectedColumns);

            long totalRows = schemaMetadataService.getTableCount(schema, tableName, filterColumn, filterOperator, filterValue, filterValue2);
            if (totalRows > maxExportRows) {
                throw new IllegalArgumentException(
                    "La tabla contiene " + totalRows + " filas, por encima del máximo exportable de "
                        + maxExportRows + ". Aplique filtros o contacte al administrador.");
            }

            List<Object> queryParams = new ArrayList<>();
            String condition = schemaMetadataService.buildFilterCondition(schema, tableName, filterColumn, filterOperator, filterValue, filterValue2, queryParams);

            String columnsBuilder = SqlSafe.buildSafeColumnList(dialect, validatedColumns);
            String safeTableName = SqlSafe.buildSafeTableName(dialect, schema, tableName);
            String sql = "SELECT " + columnsBuilder + " FROM " + safeTableName;
            if (condition != null) {
                sql += " WHERE " + condition;
            }

            log.info("Iniciando exportación .xlsx de reporte completo para {}.{} ({})", schema, tableName, dialect.getDisplayName());

            Map<String, Map<String, String>> fkLookups = new HashMap<>();
            try {
                for (ForeignKeyInfo fk : foreignKeyService.getForeignKeys(schema, tableName)) {
                    if (fk.enabled() && schemaMetadataService.isTableValid(fk.referencedSchema(), fk.referencedTable())) {

                        List<Object> distinctParams = new ArrayList<>();
                        String distinctCondition = schemaMetadataService.buildFilterCondition(schema, tableName, filterColumn, filterOperator, filterValue, filterValue2, distinctParams);

                        String safeFkCol = dialect.escapeIdentifier(fk.fkColumn());
                        String distinctSql = "SELECT DISTINCT " + safeFkCol + " FROM " + safeTableName;

                        if (distinctCondition != null) {
                            distinctSql += " WHERE " + distinctCondition + " AND " + safeFkCol + " IS NOT NULL";
                        } else {
                            distinctSql += " WHERE " + safeFkCol + " IS NOT NULL";
                        }

                        List<Object> distinctValues = getJdbcTemplate().query(distinctSql, (rs, rowNum) -> rs.getObject(1), distinctParams.toArray())
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
                RowCallbackHandler rowWriter = rs -> {
                    org.apache.poi.ss.usermodel.Row row = sheet.createRow(nextRowIndex[0]++);
                    for (int i = 1; i <= validatedColumns.size(); i++) {
                        String colName = validatedColumns.get(i - 1);
                        Object val = rs.getObject(i);
                        org.apache.poi.ss.usermodel.Cell cell = row.createCell(i - 1);

                        if (val == null) {
                            cell.setBlank();
                        } else {
                            Map<String, String> lookup = fkLookups.get(colName);
                            String resolvedName = (lookup != null) ? applyFkLookup(val, lookup) : null;

                            if (resolvedName != null) {
                                String cellText = val + " · " + resolvedName;
                                cell.setCellValue(sanitizeForSpreadsheet(cellText));
                            } else if (val instanceof Number num) {
                                cell.setCellValue(num.doubleValue());
                            } else if (val instanceof Boolean b) {
                                cell.setCellValue(b);
                            } else {
                                cell.setCellValue(sanitizeForSpreadsheet(val.toString()));
                            }
                        }
                    }
                };

                getJdbcTemplate().query(sql, rowWriter, queryParams.toArray());
                workbook.write(outputStream);
            }
        } finally {
            exportSemaphore.release();
        }
    }

    public void exportCustomReportToExcel(CustomReportQuery query, OutputStream outputStream) throws Exception {
        if (!exportSemaphore.tryAcquire()) {
            throw new TooManyExportsException(
                "Hay demasiadas exportaciones en curso (máximo " + maxConcurrentExports + " en paralelo permitido). " +
                "Intenta nuevamente en unos segundos.");
        }
        try {
            BuiltReportQuery built = customReportService.buildReportSql(query);

            Long totalRows = getJdbcTemplate().queryForObject(built.countSql(), Long.class, built.countParams().toArray());
            long total = (totalRows != null) ? totalRows : 0L;
            if (total > maxExportRows) {
                throw new IllegalArgumentException(
                    "El reporte personalizado contiene " + total + " filas, por encima del máximo exportable de "
                        + maxExportRows + ". Aplique filtros o acote la consulta.");
            }

            log.info("Iniciando exportación .xlsx de reporte personalizado multi-tabla ({} filas)", total);

            List<ReportColumn> columns = built.validatedColumns();

            try (org.apache.poi.xssf.streaming.SXSSFWorkbook workbook = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100)) {
                workbook.setCompressTempFiles(true);
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Reporte Personalizado");

                org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
                for (int i = 0; i < columns.size(); i++) {
                    headerRow.createCell(i).setCellValue(columns.get(i).label());
                }

                int[] nextRowIndex = {1};
                RowCallbackHandler rowWriter = rs -> {
                    org.apache.poi.ss.usermodel.Row row = sheet.createRow(nextRowIndex[0]++);
                    for (int i = 1; i <= columns.size(); i++) {
                        Object val = rs.getObject(i);
                        org.apache.poi.ss.usermodel.Cell cell = row.createCell(i - 1);
                        if (val == null) {
                            cell.setBlank();
                        } else if (val instanceof Number num) {
                            cell.setCellValue(num.doubleValue());
                        } else if (val instanceof Boolean b) {
                            cell.setCellValue(b);
                        } else {
                            cell.setCellValue(sanitizeForSpreadsheet(val.toString()));
                        }
                    }
                };

                getJdbcTemplate().query(built.selectAllSql(), rowWriter, built.selectAllParams().toArray());
                workbook.write(outputStream);
            }
        } finally {
            exportSemaphore.release();
        }
    }
}
