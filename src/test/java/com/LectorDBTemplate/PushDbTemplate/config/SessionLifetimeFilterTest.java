package com.LectorDBTemplate.PushDbTemplate.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Duración máxima absoluta de sesión (ISO/IEC 27002 8.5). */
class SessionLifetimeFilterTest {

    /** MockHttpSession no permite fijar creationTime, así que se sobrescribe. */
    private static MockHttpSession sessionCreatedAgo(Duration age) {
        long created = System.currentTimeMillis() - age.toMillis();
        return new MockHttpSession() {
            @Override
            public long getCreationTime() {
                return created;
            }
        };
    }

    @Test
    void sessionOlderThanMaxLifetime_isInvalidated_andRequestContinues() throws Exception {
        MockHttpSession session = sessionCreatedAgo(Duration.ofHours(9));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/db/tables");
        req.setSession(session);
        FilterChain chain = mock(FilterChain.class);

        new SessionLifetimeFilter(Duration.ofHours(8)).doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(session.isInvalid()).isTrue();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void youngSession_isKept() throws Exception {
        MockHttpSession session = sessionCreatedAgo(Duration.ofMinutes(5));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/db/tables");
        req.setSession(session);

        new SessionLifetimeFilter(Duration.ofHours(8)).doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void zeroLifetime_disablesTheCheck() throws Exception {
        MockHttpSession session = sessionCreatedAgo(Duration.ofDays(30));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/db/tables");
        req.setSession(session);

        new SessionLifetimeFilter(Duration.ZERO).doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(session.isInvalid()).isFalse();
    }
}
