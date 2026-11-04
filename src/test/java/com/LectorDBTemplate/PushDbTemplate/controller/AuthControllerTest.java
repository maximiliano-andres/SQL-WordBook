package com.LectorDBTemplate.PushDbTemplate.controller;

import com.LectorDBTemplate.PushDbTemplate.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujo de autenticación tal como lo usa AuthContext.jsx: Basic UNA vez en POST /login,
 * luego cookie de sesión; /status público; /logout invalida la sesión. Sin tests antes de la
 * auditoría 2026-11. Cada test usa una IP distinta para que el rate limiter (bean compartido
 * del contexto) no acople tests entre sí; DirtiesContext lo reinicia al terminar la clase.
 */
@WebMvcTest(controllers = AuthController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.security.username=test-user",
        "app.security.password=test-pass"
})
@DirtiesContext
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor ip(String addr) {
        return req -> {
            req.setRemoteAddr(addr);
            return req;
        };
    }

    @Test
    void status_isPublic_andReportsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/auth/status").with(ip("10.1.0.1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.username").doesNotExist());
    }

    @Test
    void login_withValidCredentials_returnsUserAndCreatesSession() throws Exception {
        var result = mockMvc.perform(post("/api/auth/login").with(httpBasic("test-user", "test-pass")).with(ip("10.1.0.2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.username").value("test-user"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(get("/api/auth/status").session(session).with(ip("10.1.0.2")))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("test-user"));
    }

    @Test
    void login_withWrongPassword_returns401Json_withoutBrowserPopupHeader() throws Exception {
        mockMvc.perform(post("/api/auth/login").with(httpBasic("test-user", "mala")).with(ip("10.1.0.3")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void login_withoutCredentials_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login").with(ip("10.1.0.4")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_usernameIsCaseSensitiveForPasswordButRejectsUnknownUser() throws Exception {
        mockMvc.perform(post("/api/auth/login").with(httpBasic("otro", "test-pass")).with(ip("10.1.0.5")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_invalidatesSession_soItNoLongerAuthenticates() throws Exception {
        var login = mockMvc.perform(post("/api/auth/login").with(httpBasic("test-user", "test-pass")).with(ip("10.1.0.6")))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(post("/api/auth/logout").session(session).with(ip("10.1.0.6")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void logout_withoutSession_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(ip("10.1.0.7")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bruteForceOnLogin_isBlockedWith429AfterMaxAttempts() throws Exception {
        for (int i = 0; i < 8; i++) {
            mockMvc.perform(post("/api/auth/login").with(httpBasic("test-user", "intento" + i)).with(ip("10.1.0.8")))
                    .andExpect(status().isUnauthorized());
        }
        // Incluso con la contraseña correcta: la IP quedó bloqueada durante la ventana.
        mockMvc.perform(post("/api/auth/login").with(httpBasic("test-user", "test-pass")).with(ip("10.1.0.8")))
                .andExpect(status().isTooManyRequests());
    }

    // Antes del fix, este bucle nunca llegaba a 429: el límite solo miraba POST /api/auth/login.
    @Test
    void bruteForceThroughAnotherEndpoint_isAlsoBlocked() throws Exception {
        for (int i = 0; i < 8; i++) {
            mockMvc.perform(get("/api/auth/status").with(httpBasic("test-user", "intento" + i)).with(ip("10.1.0.9")))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/auth/status").with(httpBasic("test-user", "otro")).with(ip("10.1.0.9")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void sessionCookieRequests_areNeverRateLimited() throws Exception {
        var login = mockMvc.perform(post("/api/auth/login").with(httpBasic("test-user", "test-pass")).with(ip("10.1.0.10")))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        for (int i = 0; i < 30; i++) {
            mockMvc.perform(get("/api/auth/status").session(session).with(ip("10.1.0.10")))
                    .andExpect(status().isOk());
        }
    }
}
