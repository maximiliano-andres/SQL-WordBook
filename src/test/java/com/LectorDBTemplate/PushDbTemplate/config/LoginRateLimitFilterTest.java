package com.LectorDBTemplate.PushDbTemplate.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class LoginRateLimitFilterTest {

    private MockHttpServletRequest loginRequest(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    void allowsRequestsUnderTheThreshold() throws Exception {
        LoginRateLimitFilter filter = new LoginRateLimitFilter();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < LoginRateLimitFilter.MAX_ATTEMPTS; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(loginRequest("10.0.0.1"), response, chain);
            assertThat(response.getStatus()).isEqualTo(200); // MockHttpServletResponse default
        }

        verify(chain, times(LoginRateLimitFilter.MAX_ATTEMPTS)).doFilter(any(ServletRequest.class), any(ServletResponse.class));
    }

    @Test
    void blocksWithHttp429AfterExceedingThreshold() throws Exception {
        LoginRateLimitFilter filter = new LoginRateLimitFilter();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < LoginRateLimitFilter.MAX_ATTEMPTS; i++) {
            filter.doFilter(loginRequest("10.0.0.2"), new MockHttpServletResponse(), chain);
        }

        MockHttpServletResponse blockedResponse = new MockHttpServletResponse();
        filter.doFilter(loginRequest("10.0.0.2"), blockedResponse, chain);

        assertThat(blockedResponse.getStatus()).isEqualTo(429);
        verify(chain, times(LoginRateLimitFilter.MAX_ATTEMPTS)).doFilter(any(ServletRequest.class), any(ServletResponse.class));
    }

    @Test
    void tracksAttemptsIndependentlyPerIp() throws Exception {
        LoginRateLimitFilter filter = new LoginRateLimitFilter();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < LoginRateLimitFilter.MAX_ATTEMPTS; i++) {
            filter.doFilter(loginRequest("10.0.0.3"), new MockHttpServletResponse(), chain);
        }

        MockHttpServletResponse otherIpResponse = new MockHttpServletResponse();
        filter.doFilter(loginRequest("10.0.0.4"), otherIpResponse, chain);

        assertThat(otherIpResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void doesNotThrottleNonLoginRequests() throws Exception {
        LoginRateLimitFilter filter = new LoginRateLimitFilter();
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/db/tables");
        request.setRemoteAddr("10.0.0.5");

        for (int i = 0; i < LoginRateLimitFilter.MAX_ATTEMPTS + 5; i++) {
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }

        verify(chain, times(LoginRateLimitFilter.MAX_ATTEMPTS + 5)).doFilter(any(ServletRequest.class), any(ServletResponse.class));
    }

    // --- Auditoría 2026-11: fuerza bruta por credenciales Basic en rutas distintas de /login ---

    private MockHttpServletRequest basicRequest(String uri, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRemoteAddr(ip);
        request.addHeader("Authorization", "Basic dGVzdDp3cm9uZw==");
        return request;
    }

    /** Cadena que simula la respuesta de Spring Security: 401 o 200. */
    private FilterChain chainResponding(int status) {
        return (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(status);
    }

    @Test
    void failedBasicAttemptsOnAnyEndpoint_areThrottled() throws Exception {
        LoginRateLimitFilter filter = new LoginRateLimitFilter();
        FilterChain unauthorized = chainResponding(401);

        for (int i = 0; i < LoginRateLimitFilter.MAX_ATTEMPTS; i++) {
            MockHttpServletResponse r = new MockHttpServletResponse();
            filter.doFilter(basicRequest("/api/db/tables", "10.0.1.1"), r, unauthorized);
            assertThat(r.getStatus()).isEqualTo(401);
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(basicRequest("/api/db/info", "10.0.1.1"), blocked, unauthorized);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getContentAsString()).contains("Demasiados intentos");
    }

    @Test
    void successfulBasicRequests_areNeverThrottled() throws Exception {
        LoginRateLimitFilter filter = new LoginRateLimitFilter();
        FilterChain ok = chainResponding(200);

        for (int i = 0; i < LoginRateLimitFilter.MAX_ATTEMPTS * 5; i++) {
            MockHttpServletResponse r = new MockHttpServletResponse();
            filter.doFilter(basicRequest("/api/db/tables", "10.0.1.2"), r, ok);
            assertThat(r.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void loginAttemptsAndFailedBasicAttempts_shareTheSameCounter() throws Exception {
        LoginRateLimitFilter filter = new LoginRateLimitFilter();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < LoginRateLimitFilter.MAX_ATTEMPTS / 2; i++) {
            filter.doFilter(loginRequest("10.0.1.3"), new MockHttpServletResponse(), chain);
        }
        for (int i = 0; i < LoginRateLimitFilter.MAX_ATTEMPTS / 2; i++) {
            filter.doFilter(basicRequest("/api/db/tables", "10.0.1.3"), new MockHttpServletResponse(), chainResponding(401));
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(loginRequest("10.0.1.3"), blocked, chain);
        assertThat(blocked.getStatus()).isEqualTo(429);
    }

    @Test
    void basicSchemeIsDetectedCaseInsensitively_andBearerIsIgnored() throws Exception {
        LoginRateLimitFilter filter = new LoginRateLimitFilter();
        for (int i = 0; i < LoginRateLimitFilter.MAX_ATTEMPTS; i++) {
            MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/db/tables");
            r.setRemoteAddr("10.0.1.4");
            r.addHeader("Authorization", "bAsIc eDp5");
            filter.doFilter(r, new MockHttpServletResponse(), chainResponding(401));
        }
        MockHttpServletRequest bearer = new MockHttpServletRequest("GET", "/api/db/tables");
        bearer.setRemoteAddr("10.0.1.4");
        bearer.addHeader("Authorization", "Bearer abc");
        MockHttpServletResponse r = new MockHttpServletResponse();
        filter.doFilter(bearer, r, chainResponding(401));

        assertThat(r.getStatus()).isEqualTo(401); // no es Basic: no se bloquea ni se cuenta
    }

    @Test
    void windowExpiry_resetsTheCounter() throws Exception {
        LoginRateLimitFilter filter = new LoginRateLimitFilter();
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < LoginRateLimitFilter.MAX_ATTEMPTS; i++) {
            filter.doFilter(loginRequest("10.0.1.5"), new MockHttpServletResponse(), chain);
        }

        // Simula que la ventana empezó hace más de WINDOW_MILLIS.
        @SuppressWarnings("unchecked")
        var map = (java.util.concurrent.ConcurrentHashMap<String, Object>)
                org.springframework.test.util.ReflectionTestUtils.getField(filter, "attemptsByIp");
        Object window = map.get("10.0.1.5");
        java.util.concurrent.atomic.AtomicLong start = (java.util.concurrent.atomic.AtomicLong)
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(window, "windowStart");
        start.set(System.currentTimeMillis() - LoginRateLimitFilter.WINDOW_MILLIS - 1);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(loginRequest("10.0.1.5"), response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void staleEntries_arePrunedOnceMapExceedsThreshold() throws Exception {
        LoginRateLimitFilter filter = new LoginRateLimitFilter();
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i <= LoginRateLimitFilter.PRUNE_THRESHOLD; i++) {
            filter.doFilter(loginRequest("10." + (i >> 16 & 255) + "." + (i >> 8 & 255) + "." + (i & 255)), new MockHttpServletResponse(), chain);
        }
        @SuppressWarnings("unchecked")
        var map = (java.util.concurrent.ConcurrentHashMap<String, Object>)
                org.springframework.test.util.ReflectionTestUtils.getField(filter, "attemptsByIp");
        for (Object w : map.values()) {
            ((java.util.concurrent.atomic.AtomicLong) org.springframework.test.util.ReflectionTestUtils.invokeMethod(w, "windowStart"))
                    .set(System.currentTimeMillis() - LoginRateLimitFilter.WINDOW_MILLIS - 1);
        }

        filter.doFilter(loginRequest("192.168.0.1"), new MockHttpServletResponse(), chain);

        assertThat(filter.trackedIpCount()).isEqualTo(1);
    }
}
