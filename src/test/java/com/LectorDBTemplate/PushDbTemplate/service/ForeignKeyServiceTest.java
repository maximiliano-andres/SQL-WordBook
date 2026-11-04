package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.FkCellResolution;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.FkStatus;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.ForeignKeyInfo;
import com.LectorDBTemplate.PushDbTemplate.service.ForeignKeyService.ForeignKeyResolution;
import com.LectorDBTemplate.PushDbTemplate.service.SchemaMetadataService.ColumnInfo;
import com.LectorDBTemplate.PushDbTemplate.service.dialect.SqlServerDialect;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ForeignKeyServiceTest {

    @Mock private DynamicDataSourceService dynamicDataSourceService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private JdbcTemplate appJdbcTemplate;
    @Mock private SchemaMetadataService schemaMetadataService;
    @Mock private CacheManager cacheManager;
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private DatabaseMetaData metaData;

    private ForeignKeyService foreignKeyService;

    @BeforeEach
    void setUp() {
        when(dynamicDataSourceService.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(dynamicDataSourceService.getDataSource()).thenReturn(dataSource);
        when(dynamicDataSourceService.getDialect()).thenReturn(new SqlServerDialect());

        foreignKeyService = new ForeignKeyService(dynamicDataSourceService, schemaMetadataService, cacheManager, appJdbcTemplate);
        ReflectionTestUtils.setField(foreignKeyService, "self", foreignKeyService);
    }

    @Test
    void getForeignKeys_detectsSingleColumnFk_fromRealMetadata() throws Exception {
        when(schemaMetadataService.isTableValid("dbo", "empleado")).thenReturn(true);
        mockConnectionMetadata();

        ResultSet importedKeysRs = mock(ResultSet.class);
        when(metaData.getImportedKeys(any(), any(), any())).thenReturn(importedKeysRs);
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
        when(metaData.getImportedKeys(any(), any(), any())).thenReturn(importedKeysRs);
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

        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(fakeRow(10, "Finanzas"));
            handler.processRow(fakeRow(20, "Tecnología"));
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        List<Map<String, Object>> rows = List.of(
                rowOf("id", 1, "departamento_id", 10),
                rowOf("id", 2, "departamento_id", 20),
                rowOf("id", 3, "departamento_id", null),
                rowOf("id", 4, "departamento_id", 99)
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

    // --- Persistencia de FKs personalizadas: siempre contra la BD fija de la app, nunca
    // contra la conexión dinámica de negocio (ver auditoría, hallazgo crítico).

    @Test
    void saveCustomFks_persistsAgainstAppJdbcTemplate_neverAgainstDynamicConnection() {
        ForeignKeyInfo fk = new ForeignKeyInfo("departamento_id", "dbo", "departamento", "id");

        foreignKeyService.saveCustomFks("dbo", "empleado", List.of(fk));

        verify(appJdbcTemplate).update(anyString(), eq("dbo"), eq("empleado"));
        // Los INSERT ahora viajan en un único batchUpdate (un round-trip) en vez de uno por FK.
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Object[]>> rows = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(appJdbcTemplate).batchUpdate(anyString(), rows.capture());
        assertThat(rows.getValue()).hasSize(1);
        assertThat(rows.getValue().get(0)).containsExactly(
                "dbo", "empleado", "departamento_id", "dbo", "departamento", "id", null, null, null, 1);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void getForeignKeys_readsCustomOverridesFromAppJdbcTemplate_neverFromDynamicConnection() throws Exception {
        when(schemaMetadataService.isTableValid("dbo", "empleado")).thenReturn(true);
        mockConnectionMetadata();
        ResultSet importedKeysRs = mock(ResultSet.class);
        when(metaData.getImportedKeys(any(), any(), any())).thenReturn(importedKeysRs);
        when(importedKeysRs.next()).thenReturn(false);

        foreignKeyService.getForeignKeys("dbo", "empleado");

        verify(appJdbcTemplate).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq("dbo"), eq("empleado"));
        verify(jdbcTemplate, never()).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));
    }

    // --- saveCustomFks: validación de entrada y atomicidad (auditoría 2026-11) ---

    @Test
    void saveCustomFks_nullElementOrMissingColumn_isRejectedBeforeTouchingTheDatabase() {
        java.util.List<ForeignKeyInfo> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> foreignKeyService.saveCustomFks("dbo", "empleado", withNull))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> foreignKeyService.saveCustomFks("dbo", "empleado",
                        List.of(new ForeignKeyInfo(null, "dbo", "departamento", "id"))))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> foreignKeyService.saveCustomFks("dbo", "empleado",
                        List.of(new ForeignKeyInfo("departamento_id", "dbo", " ", "id"))))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> foreignKeyService.saveCustomFks("dbo", "empleado",
                        List.of(new ForeignKeyInfo("departamento_id", "dbo", "departamento", null))))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> foreignKeyService.saveCustomFks("dbo", "empleado",
                        List.of(new ForeignKeyInfo("departamento_id", null, "departamento", "id"))))
                .isInstanceOf(IllegalArgumentException.class);

        // Clave del fix: la validación ocurre ANTES del DELETE, así que una petición inválida
        // ya no borra las relaciones existentes de la tabla.
        verifyNoInteractions(appJdbcTemplate);
    }

    @Test
    void saveCustomFks_blankSchemaOrTable_isRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> foreignKeyService.saveCustomFks(" ", "empleado", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> foreignKeyService.saveCustomFks("dbo", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(appJdbcTemplate);
    }

    @Test
    void saveCustomFks_emptySchemaForEngineWithoutSchemas_isStillAccepted() {
        // SQLite no tiene esquemas: el frontend puede mandar referencedSchema "" y eso se
        // aceptaba antes de la auditoría; se mantiene para no romper ese flujo.
        foreignKeyService.saveCustomFks("main", "empleado", List.of(new ForeignKeyInfo("departamento_id", "", "departamento", "id")));

        verify(appJdbcTemplate).batchUpdate(anyString(), org.mockito.ArgumentMatchers.<List<Object[]>>any());
    }

    @Test
    void saveCustomFks_sameColumnWithDifferentCase_keepsOnlyLastDefinition() {
        foreignKeyService.saveCustomFks("dbo", "empleado", List.of(
                new ForeignKeyInfo("Departamento_Id", "dbo", "departamento", "id", "codigo"),
                new ForeignKeyInfo("departamento_id", "dbo", "departamento", "id", "nombre")
        ));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Object[]>> rows = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(appJdbcTemplate).batchUpdate(anyString(), rows.capture());
        assertThat(rows.getValue()).hasSize(1);
        assertThat(rows.getValue().get(0)[6]).isEqualTo("nombre");
    }

    @Test
    void saveCustomFks_emptyList_onlyDeletesAndSkipsBatchInsert() {
        foreignKeyService.saveCustomFks("dbo", "empleado", List.of());

        verify(appJdbcTemplate).update(anyString(), eq("dbo"), eq("empleado"));
        verify(appJdbcTemplate, never()).batchUpdate(anyString(), org.mockito.ArgumentMatchers.<List<Object[]>>any());
    }

    @Test
    void saveCustomFks_disabledFk_isPersistedWithEnabledZero() {
        foreignKeyService.saveCustomFks("dbo", "empleado", List.of(
                new ForeignKeyInfo("departamento_id", "dbo", "departamento", "id", null, null, null, false, false)));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Object[]>> rows = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(appJdbcTemplate).batchUpdate(anyString(), rows.capture());
        assertThat(rows.getValue().get(0)[9]).isEqualTo(0);
    }

    @Test
    void saveCustomFks_evictsFkAndColumnCachesForTheTable() {
        org.springframework.cache.Cache fkCache = mock(org.springframework.cache.Cache.class);
        org.springframework.cache.Cache colCache = mock(org.springframework.cache.Cache.class);
        org.springframework.cache.Cache displayCache = mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("foreignKeys")).thenReturn(fkCache);
        when(cacheManager.getCache("columns")).thenReturn(colCache);
        when(cacheManager.getCache("fkDisplayColumn")).thenReturn(displayCache);

        foreignKeyService.saveCustomFks("dbo", "empleado", List.of());

        verify(fkCache).evict("dbo.empleado");
        verify(colCache).evict("dbo.empleado");
        verify(displayCache).clear();
    }

    @Test
    void saveCustomFks_isTransactional_soAFailedInsertRollsBackTheDelete() throws Exception {
        assertThat(ForeignKeyService.class
                .getMethod("saveCustomFks", String.class, String.class, List.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }

    // --- getForeignKeys: combinación de FKs nativas y personalizadas ---

    @Test
    void getForeignKeys_rejectsTableNotInWhitelist() {
        when(schemaMetadataService.isTableValid("dbo", "inventada")).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> foreignKeyService.getForeignKeys("dbo", "inventada"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void getForeignKeys_customFkOverridesNativeOnSameColumn_andDisabledCustomHidesIt() throws Exception {
        when(schemaMetadataService.isTableValid("dbo", "empleado")).thenReturn(true);
        mockConnectionMetadata();
        ResultSet importedKeysRs = mock(ResultSet.class);
        when(metaData.getImportedKeys(any(), any(), any())).thenReturn(importedKeysRs);
        when(importedKeysRs.next()).thenReturn(true, true, false);
        when(importedKeysRs.getString("FK_NAME")).thenReturn("FK_a", "FK_b");
        when(importedKeysRs.getString("FKCOLUMN_NAME")).thenReturn("departamento_id", "cargo_id");
        when(importedKeysRs.getString("PKTABLE_SCHEM")).thenReturn("dbo", "dbo");
        when(importedKeysRs.getString("PKTABLE_NAME")).thenReturn("departamento", "cargo");
        when(importedKeysRs.getString("PKCOLUMN_NAME")).thenReturn("id", "id");

        ForeignKeyInfo override = new ForeignKeyInfo("DEPARTAMENTO_ID", "dbo", "area", "codigo", "nombre", null, null, true, true);
        ForeignKeyInfo disabled = new ForeignKeyInfo("cargo_id", "dbo", "cargo", "id", null, null, null, false, true);
        when(appJdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq("dbo"), eq("empleado")))
                .thenReturn(List.of(override, disabled));

        List<ForeignKeyInfo> fks = foreignKeyService.getForeignKeys("dbo", "empleado");

        assertThat(fks).containsExactly(override);
    }

    @Test
    void getForeignKeys_metadataFailure_stillReturnsCustomFks() throws Exception {
        when(schemaMetadataService.isTableValid("dbo", "empleado")).thenReturn(true);
        when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("caída"));
        ForeignKeyInfo custom = new ForeignKeyInfo("departamento_id", "dbo", "departamento", "id", null, null, null, true, true);
        when(appJdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq("dbo"), eq("empleado")))
                .thenReturn(List.of(custom));

        assertThat(foreignKeyService.getForeignKeys("dbo", "empleado")).containsExactly(custom);
    }

    // --- resolveForeignKeys: casos borde ---

    @Test
    void resolveForeignKeys_rejectsTableNotInWhitelist() {
        when(schemaMetadataService.isTableValid("dbo", "inventada")).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> foreignKeyService.resolveForeignKeys("dbo", "inventada", List.of()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void resolveForeignKeys_emptyPage_returnsEmptyWithoutQueryingFks() {
        ForeignKeyService selfMock = mock(ForeignKeyService.class);
        ReflectionTestUtils.setField(foreignKeyService, "self", selfMock);
        when(schemaMetadataService.isTableValid("dbo", "empleado")).thenReturn(true);

        ForeignKeyResolution result = foreignKeyService.resolveForeignKeys("dbo", "empleado", List.of());

        assertThat(result.columns()).isEmpty();
        assertThat(result.resolutions()).isEmpty();
        verifyNoInteractions(selfMock);
    }

    @Test
    void resolveForeignKeys_fkColumnNotInSelectedColumns_isSkipped() {
        ForeignKeyService selfMock = mock(ForeignKeyService.class);
        ReflectionTestUtils.setField(foreignKeyService, "self", selfMock);
        when(schemaMetadataService.isTableValid("dbo", "empleado")).thenReturn(true);
        when(selfMock.getForeignKeys("dbo", "empleado")).thenReturn(List.of(
                new ForeignKeyInfo("departamento_id", "dbo", "departamento", "id")));

        ForeignKeyResolution result = foreignKeyService.resolveForeignKeys("dbo", "empleado", List.of(rowOf("id", 1)));

        assertThat(result.columns()).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void resolveForeignKeys_referencedTableNotFound_marksAllCellsAsOrphan() {
        ForeignKeyService selfMock = mock(ForeignKeyService.class);
        ReflectionTestUtils.setField(foreignKeyService, "self", selfMock);
        when(schemaMetadataService.isTableValid("dbo", "empleado")).thenReturn(true);
        when(schemaMetadataService.isTableValid("dbo", "borrada")).thenReturn(false);
        when(selfMock.getForeignKeys("dbo", "empleado")).thenReturn(List.of(
                new ForeignKeyInfo("departamento_id", "dbo", "borrada", "id")));

        ForeignKeyResolution result = foreignKeyService.resolveForeignKeys("dbo", "empleado",
                List.of(rowOf("departamento_id", 1), rowOf("departamento_id", 2)));

        assertThat(result.resolutions().get("departamento_id")).containsOnly(
                new FkCellResolution(FkStatus.ORPHAN, "[Tabla no encontrada]"));
        assertThat(result.columns().get(0).enabled()).isFalse();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void resolveForeignKeys_lookupQueryFails_marksNonNullCellsAsRelationError() {
        ForeignKeyService selfMock = mock(ForeignKeyService.class);
        ReflectionTestUtils.setField(foreignKeyService, "self", selfMock);
        when(schemaMetadataService.isTableValid(eq("dbo"), anyString())).thenReturn(true);
        when(selfMock.getForeignKeys("dbo", "empleado")).thenReturn(List.of(
                new ForeignKeyInfo("departamento_id", "dbo", "departamento", "id", "nombre")));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("timeout"))
                .when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        ForeignKeyResolution result = foreignKeyService.resolveForeignKeys("dbo", "empleado",
                List.of(rowOf("departamento_id", 10), rowOf("departamento_id", null)));

        assertThat(result.resolutions().get("departamento_id")).containsExactly(
                new FkCellResolution(FkStatus.ORPHAN, "[Error de Relación]"),
                new FkCellResolution(FkStatus.NULL, null));
    }

    @Test
    void resolveForeignKeys_disabledFk_returnsNullStatusWithoutQuerying() {
        ForeignKeyService selfMock = mock(ForeignKeyService.class);
        ReflectionTestUtils.setField(foreignKeyService, "self", selfMock);
        when(schemaMetadataService.isTableValid(eq("dbo"), anyString())).thenReturn(true);
        when(selfMock.getForeignKeys("dbo", "empleado")).thenReturn(List.of(
                new ForeignKeyInfo("departamento_id", "dbo", "departamento", "id", "nombre", null, null, false, true)));

        ForeignKeyResolution result = foreignKeyService.resolveForeignKeys("dbo", "empleado",
                List.of(rowOf("departamento_id", 10)));

        assertThat(result.resolutions().get("departamento_id")).containsExactly(new FkCellResolution(FkStatus.NULL, null));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void resolveForeignKeys_withFilterAndMultipleDisplayColumns_bindsFilterAndJoinsValues() throws Exception {
        ForeignKeyService selfMock = mock(ForeignKeyService.class);
        ReflectionTestUtils.setField(foreignKeyService, "self", selfMock);
        when(schemaMetadataService.isTableValid(eq("dbo"), anyString())).thenReturn(true);
        when(selfMock.getForeignKeys("dbo", "empleado")).thenReturn(List.of(
                new ForeignKeyInfo("cargo_id", "dbo", "parametro", "id", "codigo, glosa", "tipo", "CARGO")));

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> params = org.mockito.ArgumentCaptor.forClass(Object[].class);
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject(1)).thenReturn(5);
            when(rs.getObject(2)).thenReturn("GER");
            when(rs.getObject(3)).thenReturn("Gerente ");
            handler.processRow(rs);
            return null;
        }).when(jdbcTemplate).query(sql.capture(), any(RowCallbackHandler.class), params.capture());

        ForeignKeyResolution result = foreignKeyService.resolveForeignKeys("dbo", "empleado", List.of(rowOf("cargo_id", 5)));

        assertThat(sql.getValue()).contains("SELECT [id], [codigo], [glosa] FROM [dbo].[parametro]")
                .contains("WHERE [id] IN (?)").contains("AND [tipo] = ?");
        assertThat(params.getValue()).containsExactly(5, "CARGO");
        assertThat(result.resolutions().get("cargo_id")).containsExactly(new FkCellResolution(FkStatus.RESOLVED, "GER - Gerente"));
    }

    @Test
    void resolveForeignKeys_moreThan900DistinctValues_splitsIntoSeveralInBatches() {
        ForeignKeyService selfMock = mock(ForeignKeyService.class);
        ReflectionTestUtils.setField(foreignKeyService, "self", selfMock);
        when(schemaMetadataService.isTableValid(eq("dbo"), anyString())).thenReturn(true);
        when(selfMock.getForeignKeys("dbo", "empleado")).thenReturn(List.of(
                new ForeignKeyInfo("departamento_id", "dbo", "departamento", "id", "nombre")));

        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 1500; i++) rows.add(rowOf("departamento_id", i));

        foreignKeyService.resolveForeignKeys("dbo", "empleado", rows);

        // 1500 valores distintos → 900 + 600: respeta el límite de parámetros de Oracle/SQLite/SQL Server.
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    // --- suggestJoinsForTable ---

    @Test
    void suggestJoins_rejectsTableNotInWhitelist() {
        when(schemaMetadataService.isTableValid("dbo", "inventada")).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> foreignKeyService.suggestJoinsForTable("dbo", "inventada"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void suggestJoins_includesOutgoingAndIncomingRelations_withoutDuplicates() throws Exception {
        ForeignKeyService selfMock = mock(ForeignKeyService.class);
        ReflectionTestUtils.setField(foreignKeyService, "self", selfMock);
        when(schemaMetadataService.isTableValid(eq("dbo"), anyString())).thenReturn(true);
        when(selfMock.getForeignKeys("dbo", "empleado")).thenReturn(List.of(
                new ForeignKeyInfo("departamento_id", "dbo", "departamento", "id"),
                new ForeignKeyInfo("departamento_id", "dbo", "departamento", "id")));
        mockConnectionMetadata();
        ResultSet exported = mock(ResultSet.class);
        when(metaData.getExportedKeys(any(), any(), any())).thenReturn(exported);
        when(exported.next()).thenReturn(true, false);
        when(exported.getString("FK_NAME")).thenReturn("FK_asistencia_empleado");
        when(exported.getString("FKTABLE_SCHEM")).thenReturn("dbo");
        when(exported.getString("FKTABLE_NAME")).thenReturn("asistencia");
        when(exported.getString("FKCOLUMN_NAME")).thenReturn("empleado_id");
        when(exported.getString("PKCOLUMN_NAME")).thenReturn("id");

        var joins = foreignKeyService.suggestJoinsForTable("dbo", "empleado");

        assertThat(joins).extracting(ForeignKeyService.SuggestedJoin::targetTable).containsExactly("departamento", "asistencia");
        assertThat(joins).allMatch(j -> j.type().equals("LEFT"));
    }

    private void mockConnectionMetadata() throws Exception {
        when(dynamicDataSourceService.getDataSource()).thenReturn(dataSource);
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
