package com.LectorDBTemplate.PushDbTemplate.controller;

import com.LectorDBTemplate.PushDbTemplate.config.SecurityConfig;
import com.LectorDBTemplate.PushDbTemplate.service.DatabaseService;
import com.LectorDBTemplate.PushDbTemplate.service.DatabaseService.TableInfo;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test del controller: verifica que /api/db/** exige autenticación
 * (hallazgo crítico de la auditoría) y que las excepciones de seguridad
 * del servicio se traducen al código HTTP correcto.
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
    private DatabaseService databaseService;

    @Test
    void tablesEndpoint_withoutCredentials_returns401() throws Exception {
        mockMvc.perform(get("/api/db/tables"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tablesEndpoint_withValidCredentials_returns200() throws Exception {
        when(databaseService.getTables()).thenReturn(List.of(new TableInfo("dbo", "Empleados")));

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
        when(databaseService.getColumns("dbo", "TablaInventada"))
                .thenThrow(new SecurityException("Acceso denegado"));

        mockMvc.perform(get("/api/db/tables/dbo/TablaInventada/columns")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("test-user", "test-pass")))
                .andExpect(status().isForbidden());
    }

    @Test
    void dataEndpoint_withValidFilterParams_returns200() throws Exception {
        when(databaseService.getTableCount("dbo", "Empleados", "nombre", "LIKE", "ana", null)).thenReturn(1L);
        when(databaseService.getTableData("dbo", "Empleados", 15, 0, null, "nombre", "LIKE", "ana", null))
                .thenReturn(List.of(Map.of("nombre", "ana")));
        when(databaseService.resolveForeignKeys(eq("dbo"), eq("Empleados"), any()))
                .thenReturn(new DatabaseService.ForeignKeyResolution(List.of(), Map.of()));

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/data")
                        .param("filterColumn", "nombre")
                        .param("filterOperator", "LIKE")
                        .param("filterValue", "ana")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("test-user", "test-pass")))
                .andExpect(status().isOk());
    }

    @Test
    void dataEndpoint_whenFilterColumnRejectedByService_returns403() throws Exception {
        when(databaseService.getTableCount(eq("dbo"), eq("Empleados"), eq("nombre]; DROP TABLE Empleados; --"), any(), any(), any()))
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
        when(databaseService.getTableCount(eq("dbo"), eq("Empleados"), eq("nombre"), eq("1=1; DROP TABLE Empleados; --"), any(), any()))
                .thenThrow(new IllegalArgumentException("Operador de filtro no permitido"));

        mockMvc.perform(get("/api/db/tables/dbo/Empleados/data")
                        .param("filterColumn", "nombre")
                        .param("filterOperator", "1=1; DROP TABLE Empleados; --")
                        .param("filterValue", "x")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("test-user", "test-pass")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void actuatorHealth_isPublic_withoutCredentials() throws Exception {
        // @WebMvcTest no carga la autoconfiguración de Actuator, así que la ruta
        // no tiene handler (404) — lo que importa es que Security no la bloquee
        // antes con un 401, que es lo que confirma esta aserción.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound());
    }
}
