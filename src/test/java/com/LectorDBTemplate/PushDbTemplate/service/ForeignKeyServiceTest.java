package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.FkCellResolution;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.FkStatus;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.ForeignKeyInfo;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.ForeignKeyResolution;
import com.LectorDBTemplate.PushDbTemplate.service.SchemaMetadataService.ColumnInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.CacheManager;
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
 *
 * A diferencia de la versión anterior (DatabaseServiceForeignKeyTest, cuando todo esto vivía
 * en una sola DatabaseService), isTableValid()/getColumns() ahora son llamadas reales a un
 * SchemaMetadataService inyectado — se mockean directamente en vez de simular
 * DatabaseMetaData.getTables() para el whitelisting (ver auditoría, hallazgo F6).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ForeignKeyServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private SchemaMetadataService schemaMetadataService;
    @Mock private CacheManager cacheManager;
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private DatabaseMetaData metaData;

    private ForeignKeyService foreignKeyService;

    @BeforeEach
    void setUp() {
        foreignKeyService = new ForeignKeyService(jdbcTemplate, schemaMetadataService, cacheManager);
        ReflectionTestUtils.setField(foreignKeyService, "self", foreignKeyService);
    }

    @Test
    void getForeignKeys_detectsSingleColumnFk_fromRealMetadata() throws Exception {
        when(schemaMetadataService.isTableValid("dbo", "empleado")).thenReturn(true);
        mockConnectionMetadata();

        ResultSet importedKeysRs = mock(ResultSet.class);
        when(metaData.getImportedKeys(null, "dbo", "empleado")).thenReturn(importedKeysRs);
        when(importedKeysRs.next()).thenReturn(true, false);
        when(importedKeysRs.getString("FK_NAME")).thenReturn("FK_empleado_departamento");
        when(importedKeysRs.getString("FKCOLUMN_NAME")).thenReturn("departamento_id");
        when(importedKeysRs.getString("PKTABLE_SCHEM")).thenReturn("dbo");
        when(importedKeysRs.getString("PKTABLE_NAME")).thenReturn("departamento");
        when(importedKeysRs.getString("PKCOLUMN_NAME")).thenReturn("id");

        List<ForeignKeyInfo> fks = foreignKeyService.getForeignKeys("dbo", "empleado");

        assertThat(fks).containsExactly(new ForeignKeyInfo("departamento_id", "dbo", "departamento", "id"));
    }

    @Test
    void getForeignKeys_skipsCompositeForeignKeys() throws Exception {
        when(schemaMetadataService.isTableValid("dbo", "detalle_pedido")).thenReturn(true);
        mockConnectionMetadata();

        ResultSet importedKeysRs = mock(ResultSet.class);
        when(metaData.getImportedKeys(null, "dbo", "detalle_pedido")).thenReturn(importedKeysRs);
        // Dos columnas bajo el mismo FK_NAME => restricción compuesta
        when(importedKeysRs.next()).thenReturn(true, true, false);
        when(importedKeysRs.getString("FK_NAME")).thenReturn("FK_compuesta", "FK_compuesta");
        when(importedKeysRs.getString("FKCOLUMN_NAME")).thenReturn("pedido_id", "linea_id");
        when(importedKeysRs.getString("PKTABLE_SCHEM")).thenReturn("dbo", "dbo");
        when(importedKeysRs.getString("PKTABLE_NAME")).thenReturn("pedido_linea", "pedido_linea");
        when(importedKeysRs.getString("PKCOLUMN_NAME")).thenReturn("pedido_id", "linea_id");

        List<ForeignKeyInfo> fks = foreignKeyService.getForeignKeys("dbo", "detalle_pedido");

        assertThat(fks).isEmpty();
    }

    @Test
    void resolveDisplayColumn_prefersPriorityNamedColumn_overEarlierTextColumn() {
        when(schemaMetadataService.getColumns("dbo", "departamento")).thenReturn(List.of(
                new ColumnInfo("id", "INT", 4, false),
                new ColumnInfo("codigo", "VARCHAR", 10, true),
                new ColumnInfo("nombre", "VARCHAR", 100, true)
        ));

        String display = foreignKeyService.resolveDisplayColumn("dbo", "departamento", "id");

        assertThat(display).isEqualTo("nombre");
    }

    @Test
    void resolveDisplayColumn_fallsBackToFirstTextColumn_whenNoPriorityNameMatches() {
        when(schemaMetadataService.getColumns("dbo", "departamento")).thenReturn(List.of(
                new ColumnInfo("id", "INT", 4, false),
                new ColumnInfo("codigo", "VARCHAR", 10, true),
                new ColumnInfo("activo", "BIT", 1, false)
        ));

        String display = foreignKeyService.resolveDisplayColumn("dbo", "departamento", "id");

        assertThat(display).isEqualTo("codigo");
    }

    @Test
    void resolveDisplayColumn_fallsBackToPrimaryKey_whenNoTextColumnsExist() {
        when(schemaMetadataService.getColumns("dbo", "medicion")).thenReturn(List.of(
                new ColumnInfo("id", "INT", 4, false),
                new ColumnInfo("valor", "DECIMAL", 10, false)
        ));

        String display = foreignKeyService.resolveDisplayColumn("dbo", "medicion", "id");

        assertThat(display).isEqualTo("id");
    }

    @Test
    void resolveForeignKeys_classifiesResolvedNullAndOrphanRows() throws Exception {
        ForeignKeyService selfMock = mock(ForeignKeyService.class);
        ReflectionTestUtils.setField(foreignKeyService, "self", selfMock);

        when(schemaMetadataService.isTableValid("dbo", "empleado")).thenReturn(true);
        when(schemaMetadataService.isTableValid("dbo", "departamento")).thenReturn(true);
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

        ForeignKeyResolution result = foreignKeyService.resolveForeignKeys("dbo", "empleado", rows);

        assertThat(result.columns()).hasSize(1);
        assertThat(result.columns().get(0).displayColumn()).isEqualTo("nombre");
        assertThat(result.resolutions().get("departamento_id")).containsExactly(
                new FkCellResolution(FkStatus.RESOLVED, "Finanzas"),
                new FkCellResolution(FkStatus.RESOLVED, "Tecnología"),
                new FkCellResolution(FkStatus.NULL, null),
                new FkCellResolution(FkStatus.ORPHAN, null)
        );
    }

    private void mockConnectionMetadata() throws Exception {
        when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
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
