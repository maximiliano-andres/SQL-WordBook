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
        String ip = request.getRemoteAddr();

        // POST /api/auth/login: cada intento cuenta (comportamiento original).
        if (isLoginRequest(request)) {
            if (registerAttemptAndCheckLimit(ip)) {
                reject(response);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        // Cualquier otra ruta con credenciales Basic: BasicAuthenticationFilter las valida en
        // TODAS las rutas, no solo en /login. Antes un atacante esquivaba el límite probando
        // contraseñas contra, p. ej., GET /api/db/tables con la cabecera Authorization. Aquí solo
        // cuentan los intentos FALLIDOS (401), para no limitar a clientes legítimos que usen Basic
        // en cada petición (scripts, curl); el contador es el mismo que el de /login, así que
        // alternar entre rutas no da intentos extra.
        if (carriesBasicCredentials(request)) {
            if (isBlocked(ip)) {
                reject(response);
                return;
            }
            chain.doFilter(request, response);
            if (response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
                registerAttemptAndCheckLimit(ip);
            }
            return;
        }

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"Demasiados intentos\",\"message\":\"Intenta nuevamente en unos minutos.\"}");
    }

    private boolean isLoginRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) && "/api/auth/login".equals(request.getRequestURI());
    }

    private boolean carriesBasicCredentials(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.regionMatches(true, 0, "Basic ", 0, 6);
    }

    // Umbral a partir del cual se purgan ventanas expiradas, para que el mapa no crezca sin
    // límite con IPs que intentaron una vez y no volvieron.
    static final int PRUNE_THRESHOLD = 10_000;

    private Window currentWindow(String ip, long now) {
        if (attemptsByIp.size() > PRUNE_THRESHOLD) {
            attemptsByIp.entrySet().removeIf(e -> now - e.getValue().windowStart().get() > WINDOW_MILLIS);
        }
        Window window = attemptsByIp.computeIfAbsent(ip, k -> new Window(new AtomicInteger(0), new AtomicLong(now)));
        if (now - window.windowStart().get() > WINDOW_MILLIS) {
            window.windowStart().set(now);
            window.count().set(0);
        }
        return window;
    }

    /** Registra un intento y devuelve true si con él se supera el máximo de la ventana. */
    private boolean registerAttemptAndCheckLimit(String ip) {
        return currentWindow(ip, System.currentTimeMillis()).count().incrementAndGet() > MAX_ATTEMPTS;
    }

    /** True si la IP ya agotó sus intentos en la ventana actual (sin registrar uno nuevo). */
    private boolean isBlocked(String ip) {
        return currentWindow(ip, System.currentTimeMillis()).count().get() >= MAX_ATTEMPTS;
    }

    int trackedIpCount() {
        return attemptsByIp.size();
    }
}
