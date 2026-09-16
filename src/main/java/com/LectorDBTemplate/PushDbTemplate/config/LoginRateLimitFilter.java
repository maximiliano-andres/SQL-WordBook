package com.LectorDBTemplate.PushDbTemplate.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Limita los intentos de login por IP para mitigar fuerza bruta contra /api/auth/login
 * (ver auditoría, hallazgo alto: antes de este filtro no existía ningún throttling y un
 * atacante con acceso de red podía probar contraseñas indefinidamente).
 *
 * Ventana fija simple (no sliding window ni distribuida): suficiente para esta app de un
 * solo nodo con un único usuario configurado; una solución distribuida (Redis, bucket4j con
 * almacenamiento compartido) solo se justificaría si la app corriera en múltiples instancias.
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {

    static final int MAX_ATTEMPTS = 8;
    static final long WINDOW_MILLIS = 60_000;

    private record Window(AtomicInteger count, AtomicLong windowStart) {}

    private final ConcurrentHashMap<String, Window> attemptsByIp = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // getRemoteAddr() en vez de X-Forwarded-For: sin un proxy de confianza configurado
        // explícitamente, ese header lo controla el propio cliente y permitiría a un atacante
        // falsificarlo tanto para evadir el límite como para bloquear la IP de otro usuario.
        if (isLoginRequest(request) && isRateLimited(request.getRemoteAddr())) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Demasiados intentos\",\"message\":\"Intenta nuevamente en unos minutos.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isLoginRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) && "/api/auth/login".equals(request.getRequestURI());
    }

    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        Window window = attemptsByIp.computeIfAbsent(ip, k -> new Window(new AtomicInteger(0), new AtomicLong(now)));

        if (now - window.windowStart().get() > WINDOW_MILLIS) {
            window.windowStart().set(now);
            window.count().set(0);
        }

        return window.count().incrementAndGet() > MAX_ATTEMPTS;
    }
}
