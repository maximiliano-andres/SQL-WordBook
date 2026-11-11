package com.LectorDBTemplate.PushDbTemplate.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Duración máxima ABSOLUTA de una sesión (ISO/IEC 27002:2022 8.5 «Autenticación segura»).
 *
 * server.servlet.session.timeout solo controla la inactividad: una pestaña que hace peticiones
 * periódicamente podría mantener la sesión viva indefinidamente. Este filtro invalida la sesión
 * cuando supera app.security.session-max-lifetime desde su creación, sin importar la actividad;
 * la petición sigue sin autenticación y el frontend muestra el login con el aviso de sesión
 * expirada (mismo flujo que una expiración por inactividad).
 *
 * Se registra antes de SecurityContextHolderFilter, para que el contexto de seguridad no llegue a
 * cargarse desde una sesión ya vencida.
 */
public class SessionLifetimeFilter extends OncePerRequestFilter {

    private final long maxLifetimeMillis;

    public SessionLifetimeFilter(Duration maxLifetime) {
        this.maxLifetimeMillis = maxLifetime.toMillis();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && maxLifetimeMillis > 0) {
            try {
                if (System.currentTimeMillis() - session.getCreationTime() > maxLifetimeMillis) {
                    String user = "anonymous";
                    if (session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                            instanceof SecurityContext ctx && ctx.getAuthentication() != null) {
                        user = ctx.getAuthentication().getName();
                    }
                    session.invalidate();
                    AuditLog.event("SESSION_MAX_LIFETIME_EXPIRED", AuditLog.SUCCESS, user, request.getRemoteAddr(), null);
                }
            } catch (IllegalStateException alreadyInvalidated) {
                // La sesión expiró por inactividad entre getSession y getCreationTime: nada que hacer.
            }
        }
        chain.doFilter(request, response);
    }
}
