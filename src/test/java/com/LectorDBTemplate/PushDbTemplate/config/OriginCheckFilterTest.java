package com.LectorDBTemplate.PushDbTemplate.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Defensa CSRF por verificación de Origin/Referer (ISO/IEC 27002 8.28). */
class OriginCheckFilterTest {

    private final OriginCheckFilter filter = new OriginCheckFilter("http://localhost:5173");

    private static MockHttpServletRequest post(String host) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/db/custom-reports/templates");
        req.addHeader("Host", host);
        return req;
    }

    private boolean passes(MockHttpServletRequest req) throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        try {
            verify(chain).doFilter(any(), any());
            return true;
        } catch (AssertionError notCalled) {
            verify(chain, never()).doFilter(any(), any());
            assertThat(res.getStatus()).isEqualTo(403);
            return false;
        }
    }

    @Test
    void sameOrigin_isAllowed() throws Exception {
        MockHttpServletRequest req = post("app.empresa.cl");
        req.addHeader("Origin", "https://app.empresa.cl");
        assertThat(passes(req)).isTrue();
    }

    @Test
    void sameOrigin_withExplicitPort_isAllowed() throws Exception {
        MockHttpServletRequest req = post("localhost:8080");
        req.addHeader("Origin", "http://localhost:8080");
        assertThat(passes(req)).isTrue();
    }

    @Test
    void crossOrigin_isRejected() throws Exception {
        MockHttpServletRequest req = post("app.empresa.cl");
        req.addHeader("Origin", "https://sitio-malicioso.com");
        assertThat(passes(req)).isFalse();
    }

    @Test
    void nullOrigin_isRejected() throws Exception {
        MockHttpServletRequest req = post("app.empresa.cl");
        req.addHeader("Origin", "null");
        assertThat(passes(req)).isFalse();
    }

    @Test
    void crossOriginReferer_withoutOrigin_isRejected() throws Exception {
        MockHttpServletRequest req = post("app.empresa.cl");
        req.addHeader("Referer", "https://sitio-malicioso.com/pagina");
        assertThat(passes(req)).isFalse();
    }

    @Test
    void configuredDevOrigin_isAllowed_evenIfProxyRewroteHost() throws Exception {
        MockHttpServletRequest req = post("localhost:8080");
        req.addHeader("Origin", "http://localhost:5173");
        assertThat(passes(req)).isTrue();
    }

    @Test
    void forwardedHost_fromReverseProxy_isAllowed() throws Exception {
        MockHttpServletRequest req = post("backend:8080");
        req.addHeader("X-Forwarded-Host", "app.empresa.cl");
        req.addHeader("Origin", "https://app.empresa.cl");
        assertThat(passes(req)).isTrue();
    }

    @Test
    void noOriginNorReferer_nonBrowserClient_isAllowed() throws Exception {
        assertThat(passes(post("app.empresa.cl"))).isTrue();
    }

    @Test
    void safeMethods_andNonApiPaths_areNotChecked() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/db/tables");
        get.addHeader("Origin", "https://sitio-malicioso.com");
        assertThat(passes(get)).isTrue();

        MockHttpServletRequest nonApi = new MockHttpServletRequest("POST", "/otra-ruta");
        nonApi.addHeader("Origin", "https://sitio-malicioso.com");
        assertThat(passes(nonApi)).isTrue();
    }
}
