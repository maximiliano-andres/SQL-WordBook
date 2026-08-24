package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.DatabaseService.ColumnInfo;
import com.LectorDBTemplate.PushDbTemplate.service.DatabaseService.FkCellResolution;
import com.LectorDBTemplate.PushDbTemplate.service.DatabaseService.FkStatus;
import com.LectorDBTemplate.PushDbTemplate.service.DatabaseService.ForeignKeyInfo;
import com.LectorDBTemplate.PushDbTemplate.service.DatabaseService.ForeignKeyResolution;
import com.LectorDBTemplate.PushDbTemplate.service.DatabaseService.TableInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pruebas de la resolución automática de Foreign Keys: detección por metadata real
 * (DatabaseMetaData.getImportedKeys), heurística de columna descriptiva, y clasificación
 * de estados (RESOLVED/NULL/ORPHAN) en la resolución por lote.
 */
@ExtendWith(MockitoExtension.class)
class DatabaseServiceForeignKeyTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private DatabaseMetaData metaData;

    private DatabaseService databaseService;

    @BeforeEach
    void setUp() {
        databaseService = new DatabaseService(jdbcTemplate, 50_000, 2);
    }

    @Test
    void getForeignKeys_detectsSingleColumnFk_fromRealMetadata() throws Exception {
        ReflectionTestUtils.setField(databaseService, "self", databaseService);
        mockTablesWhitelist(new TableInfo("dbo", "empleado"));

        ResultSet importedKeysRs = mock(ResultSet.class);
        when(metaData.getImportedKeys(null, "dbo", "empleado")).thenReturn(importedKeysRs);
        when(importedKeysRs.next()).thenReturn(true, false);
        when(importedKeysRs.getString("FK_NAME")).thenReturn("FK_empleado_departamento");
        when(importedKeysRs.getString("FKCOLUMN_NAME")).thenReturn("departamento_id");
        when(importedKeysRs.getString("PKTABLE_SCHEM")).thenReturn("dbo");
        when(importedKeysRs.getString("PKTABLE_NAME")).thenReturn("departamento");
        when(importedKeysRs.getString("PKCOLUMN_NAME")).thenReturn("id");

        List<ForeignKeyInfo> fks = databaseService.getForeignKeys("dbo", "empleado");

        assertThat(fks).containsExactly(new ForeignKeyInfo("departamento_id", "dbo", "departamento", "id"));
    }

    @Test
    void getForeignKeys_skipsCompositeForeignKeys() throws Exception {
        ReflectionTestUtils.setField(databaseService, "self", databaseService);
        mockTablesWhitelist(new TableInfo("dbo", "detalle_pedido"));

        ResultSet importedKeysRs = mock(ResultSet.class);
        when(metaData.getImportedKeys(null, "dbo", "detalle_pedido")).thenReturn(importedKeysRs);
        // Dos columnas bajo el mismo FK_NAME => restricción compuesta
        when(importedKeysRs.next()).thenReturn(true, true, false);
        when(importedKeysRs.getString("FK_NAME")).thenReturn("FK_compuesta", "FK_compuesta");
        when(importedKeysRs.getString("FKCOLUMN_NAME")).thenReturn("pedido_id", "linea_id");
        when(importedKeysRs.getString("PKTABLE_SCHEM")).thenReturn("dbo", "dbo");
        when(importedKeysRs.getString("PKTABLE_NAME")).thenReturn("pedido_linea", "pedido_linea");
        when(importedKeysRs.getString("PKCOLUMN_NAME")).thenReturn("pedido_id", "linea_id");

        List<ForeignKeyInfo> fks = databaseService.getForeignKeys("dbo", "detalle_pedido");

        assertThat(fks).isEmpty();
    }

    @Test
    void resolveDisplayColumn_prefersPriorityNamedColumn_overEarlierTextColumn() {
        DatabaseService selfMock = mock(DatabaseService.class);
        ReflectionTestUtils.setField(databaseService, "self", selfMock);
        when(selfMock.getColumns("dbo", "departamento")).thenReturn(List.of(
                new ColumnInfo("id", "INT", 4, false),
                new ColumnInfo("codigo", "VARCHAR", 10, true),
                new ColumnInfo("nombre", "VARCHAR", 100, true)
        ));

        String display = databaseService.resolveDisplayColumn("dbo", "departamento", "id");

        assertThat(display).isEqualTo("nombre");
    }

    @Test
    void resolveDisplayColumn_fallsBackToFirstTextColumn_whenNoPriorityNameMatches() {
        DatabaseService selfMock = mock(DatabaseService.class);
        ReflectionTestUtils.setField(databaseService, "self", selfMock);
        when(selfMock.getColumns("dbo", "departamento")).thenReturn(List.of(
                new ColumnInfo("id", "INT", 4, false),
                new ColumnInfo("codigo", "VARCHAR", 10, true),
                new ColumnInfo("activo", "BIT", 1, false)
        ));

        String display = databaseService.resolveDisplayColumn("dbo", "departamento", "id");

        assertThat(display).isEqualTo("codigo");
    }

    @Test
    void resolveDisplayColumn_fallsBackToPrimaryKey_whenNoTextColumnsExist() {
        DatabaseService selfMock = mock(DatabaseService.class);
        ReflectionTestUtils.setField(databaseService, "self", selfMock);
        when(selfMock.getColumns("dbo", "medicion")).thenReturn(List.of(
                new ColumnInfo("id", "INT", 4, false),
                new ColumnInfo("valor", "DECIMAL", 10, false)
        ));

        String display = databaseService.resolveDisplayColumn("dbo", "medicion", "id");

        assertThat(display).isEqualTo("id");
    }

    @Test
    void resolveForeignKeys_classifiesResolvedNullAndOrphanRows() throws Exception {
        DatabaseService selfMock = mock(DatabaseService.class);
        ReflectionTestUtils.setField(databaseService, "self", selfMock);

        when(selfMock.getTables()).thenReturn(List.of(
                new TableInfo("dbo", "empleado"),
                new TableInfo("dbo", "departamento")
        ));
        when(selfMock.getForeignKeys("dbo", "empleado")).thenReturn(List.of(
                new ForeignKeyInfo("departamento_id", "dbo", "departamento", "id")
        ));
        when(selfMock.resolveDisplayColumn("dbo", "departamento", "id")).thenReturn("nombre");

        // Simula la consulta por lote: solo existen los departamentos 10 y 20 (99 queda huérfano)
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(fakeRow(10, "Finanzas"));
            handler.processRow(fakeRow(20, "Tecnología"));
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        List<Map<String, Object>> rows = List.of(
                rowOf("id", 1, "departamento_id", 10),   // resuelto
                rowOf("id", 2, "departamento_id", 20),   // resuelto
                rowOf("id", 3, "departamento_id", null), // sin valor
                rowOf("id", 4, "departamento_id", 99)    // huérfano: no existe en departamento
        );

        ForeignKeyResolution result = databaseService.resolveForeignKeys("dbo", "empleado", rows);

        assertThat(result.columns()).hasSize(1);
        assertThat(result.columns().get(0).displayColumn()).isEqualTo("nombre");
        assertThat(result.resolutions().get("departamento_id")).containsExactly(
                new FkCellResolution(FkStatus.RESOLVED, "Finanzas"),
                new FkCellResolution(FkStatus.RESOLVED, "Tecnología"),
                new FkCellResolution(FkStatus.NULL, null),
                new FkCellResolution(FkStatus.ORPHAN, null)
        );
    }

    private void mockTablesWhitelist(TableInfo... tables) throws Exception {
        // getForeignKeys() valida la tabla de origen con isTableValid(), que en producción
        // pasa por self.getTables() (cacheado); aquí self == databaseService, así que se
        // ejecuta la consulta real de metadatos una sola vez, sobre los mismos mocks de
        // campo (dataSource/connection/metaData) que usa el resto del test para getImportedKeys.
        ResultSet rs = mock(ResultSet.class);

        when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getTables(null, null, "%", new String[]{"TABLE"})).thenReturn(rs);

        Boolean[] rest = new Boolean[tables.length];
        for (int i = 0; i < tables.length - 1; i++) rest[i] = true;
        rest[tables.length - 1] = false;
        when(rs.next()).thenReturn(true, rest);

        String[] schemas = new String[tables.length];
        String[] names = new String[tables.length];
        for (int i = 0; i < tables.length; i++) {
            schemas[i] = tables[i].schema();
            names[i] = tables[i].name();
        }
        when(rs.getString("TABLE_SCHEM")).thenReturn(schemas[0], schemas);
        when(rs.getString("TABLE_NAME")).thenReturn(names[0], names);
    }

    private ResultSet fakeRow(Object key, Object value) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(1)).thenReturn(key);
        when(rs.getObject(2)).thenReturn(value);
        return rs;
    }

    private Map<String, Object> rowOf(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }
}
