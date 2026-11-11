package com.LectorDBTemplate.PushDbTemplate.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Defensa en profundidad contra CSRF (ISO/IEC 27002:2022 8.28 «Codificación segura»), complementaria
 * a la cookie de sesión SameSite=Lax: rechaza con 403 toda petición que modifica estado
 * (POST/PUT/PATCH/DELETE sobre /api/**) cuyo origen declarado por el navegador no sea la propia app.
 *
 * - Si viene la cabecera Origin (todos los navegadores modernos la envían en POST/fetch), su
 *   host:puerto debe coincidir con el Host de la petición, con X-Forwarded-Host (app detrás de un
 *   proxy inverso) o con un origen de la lista app.security.allowed-origins (por defecto el
 *   servidor de desarrollo de Vite, cuyo proxy reescribe el Host).
 * - Si no viene Origin se usa Referer con la misma regla.
 * - Si no viene ninguna de las dos, la petición NO es de un navegador en contexto cross-site
 *   (curl, scripts, tests), así que se deja pasar: no hay sesión de víctima que abusar.
 *
 * Confiar en X-Forwarded-Host es seguro para este propósito: un sitio atacante no puede fijar esa
 * cabecera en una petición cross-site sin un preflight CORS, y la app no habilita CORS.
 */
public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final Set<String> allowedOrigins;

    public OriginCheckFilter(String allowedOriginsCsv) {
        this.allowedOrigins = Arrays.stream(allowedOriginsCsv == null ? new String[0] : allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(OriginCheckFilter::authorityOf)
                .filter(s -> s != null)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !STATE_CHANGING.contains(request.getMethod().toUpperCase(Locale.ROOT))
                || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String source = request.getHeader("Origin");
        if (source == null || source.isBlank()) {
            source = request.getHeader("Referer");
        }
        if (source == null || source.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        String sourceAuthority = authorityOf(source);
        if (sourceAuthority != null && isTrusted(sourceAuthority, request)) {
            chain.doFilter(request, response);
            return;
        }

        AuditLog.event("CSRF_ORIGIN_REJECTED", AuditLog.DENIED, "anonymous", request.getRemoteAddr(),
                AuditLog.details("method", request.getMethod(), "path", request.getRequestURI(), "origin", source));
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"Origen de la petición no permitido.\"}");
    }

    private boolean isTrusted(String sourceAuthority, HttpServletRequest request) {
        if (allowedOrigins.contains(sourceAuthority)) {
            return true;
        }
        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        if (sourceAuthority.equalsIgnoreCase(normalizeHost(host))) {
            return true;
        }
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        if (forwardedHost != null) {
            for (String fh : forwardedHost.split(",")) {
                if (sourceAuthority.equalsIgnoreCase(normalizeHost(fh.trim()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** host[:puerto] en minúsculas, omitiendo puertos por defecto (80/443) para comparar con Host. */
    static String authorityOf(String url) {
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null) {
                return null;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (port == -1 || port == 80 || port == 443) {
                return host;
            }
            return host + ":" + port;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeHost(String hostHeader) {
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (h.endsWith(":80")) return h.substring(0, h.length() - 3);
        if (h.endsWith(":443")) return h.substring(0, h.length() - 4);
        return h;
    }
}
