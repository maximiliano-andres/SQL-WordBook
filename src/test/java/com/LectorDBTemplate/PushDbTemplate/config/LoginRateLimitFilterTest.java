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
}
