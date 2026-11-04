package com.LectorDBTemplate.PushDbTemplate.service;

import com.LectorDBTemplate.PushDbTemplate.config.CacheConfig;
import com.LectorDBTemplate.PushDbTemplate.service.dialect.SqlServerDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Prueba la caché REAL (proxy de Spring + Caffeine de CacheConfig) sobre SchemaMetadataService.
 * Los tests unitarios con Mockito no pasan por el proxy, así que no pueden detectar errores en
 * las anotaciones @Cacheable (claves que colisionan, resultados vacíos cacheados, etc.).
 */
@SpringJUnitConfig({CacheConfig.class, SchemaMetadataService.class})
class SchemaMetadataServiceCacheTest {

    @MockitoBean
    private DynamicDataSourceService dynamicDataSourceService;

    @Autowired
    private SchemaMetadataService service;
    @Autowired
    private CacheManager cacheManager;

    private final DataSource dataSource = mock(DataSource.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
        when(dynamicDataSourceService.getDataSource()).thenReturn(dataSource);
        when(dynamicDataSourceService.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(dynamicDataSourceService.getDialect()).thenReturn(new SqlServerDialect());
    }

    private void tablesAvailable(String schema, String name) throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        ResultSet rs = mock(ResultSet.class);
        org.mockito.Mockito.doReturn(conn).when(dataSource).getConnection(); // doReturn: puede venir de un stub que lanza
        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getTables(any(), any(), any(), any())).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("TABLE_SCHEM")).thenReturn(schema);
        when(rs.getString("TABLE_NAME")).thenReturn(name);
    }

    @Test
    void getTables_successfulResult_isCached() throws Exception {
        tablesAvailable("dbo", "Empleados");

        service.getTables();
        service.getTables();

        verify(dataSource, times(1)).getConnection();
    }

    // Hallazgo: una caída momentánea de la BD dejaba cacheada una lista vacía 10 minutos y
    // todos los endpoints respondían 403 "tabla no válida" aunque la BD ya estuviera arriba.
    @Test
    void getTables_failure_isNotCached_soTheNextCallRecovers() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("Connection refused"));
        assertThat(service.getTables()).isEmpty();

        tablesAvailable("dbo", "Empleados");

        assertThat(service.getTables()).extracting(SchemaMetadataService.TableInfo::name).containsExactly("Empleados");
        assertThat(service.isTableValid("dbo", "Empleados")).isTrue();
    }

    // Hallazgo: con la clave concatenada "a.b.null", un filtro "= 'null'" y la ausencia de
    // filtro compartían entrada de caché y devolvían el mismo total.
    @Test
    void getTableCount_nullFilterValueAndLiteralNullString_useDifferentCacheEntries() throws Exception {
        tablesAvailable("dbo", "Empleados");
        var spyCols = java.util.List.of(new SchemaMetadataService.ColumnInfo("estado", "VARCHAR", 10, true));
        cacheManager.getCache("columns").put("dbo.Empleados", spyCols);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM [dbo].[Empleados]"), eq(Long.class), any(Object[].class))).thenReturn(100L);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM [dbo].[Empleados] WHERE [estado] = ?"), eq(Long.class), any(Object[].class))).thenReturn(3L);

        long all = service.getTableCount("dbo", "Empleados", "estado", "=", null, null);
        long literalNull = service.getTableCount("dbo", "Empleados", "estado", "=", "null", null);

        assertThat(all).isEqualTo(100L);
        assertThat(literalNull).isEqualTo(3L);
    }

    @Test
    void getTableCount_sameArguments_hitsCacheOnSecondCall() throws Exception {
        tablesAvailable("dbo", "Empleados");
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(7L);

        service.getTableCount("dbo", "Empleados");
        service.getTableCount("dbo", "Empleados");

        verify(jdbcTemplate, times(1)).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }
}
