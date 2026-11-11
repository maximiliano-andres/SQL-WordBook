package com.LectorDBTemplate.PushDbTemplate.config;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Protege la API con HTTP Basic + sesión de servidor (cookie JSESSIONID httpOnly).
 * Se personaliza el AuthenticationEntryPoint para NO emitir la cabecera 'WWW-Authenticate: Basic',
 * evitando que el navegador secuestre la UI con un cuadro de diálogo nativo ('Acceder') y
 * permitiendo que el frontend muestre un modal de inicio de sesión perfectamente integrado.
 *
 * La política de sesión es IF_REQUIRED (no STATELESS): tras un login válido por Basic, Spring
 * Security crea una sesión de servidor, así el frontend solo envía usuario/contraseña UNA VEZ
 * (en el POST a /api/auth/login) y las peticiones siguientes viajan autenticadas por la cookie
 * de sesión, sin reenviar la contraseña en cada request (ver auditoría frontend, hallazgos
 * críticos/altos: la contraseña ya no necesita guardarse en el cliente en absoluto, ni en
 * memoria ni en localStorage, porque el navegador gestiona la cookie automáticamente).
 * CSRF permanece deshabilitado porque la cookie de sesión se emite con SameSite=Lax
 * (ver application.yaml): un sitio de terceros no puede provocar que el navegador la adjunte en
 * una petición fetch/XHR/POST de formulario entre sitios, que es el vector que CSRF explota.
 * Como defensa en profundidad, OriginCheckFilter rechaza además las peticiones que modifican
 * estado cuyo Origin/Referer no es la propia app (sin cambios en el frontend).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Largo mínimo recomendado para APP_PASSWORD (ISO/IEC 27002:2022 5.17). */
    static final int MIN_RECOMMENDED_PASSWORD_LENGTH = 12;

    // Cabeceras de seguridad (ISO/IEC 27002:2022 8.26). frame-ancestors 'none' impide embeber la
    // app en un iframe ajeno (clickjacking); base-uri/form-action/object-src cierran vectores de
    // inyección que default-src no cubre.
    static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; script-src 'self'; "
            + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'";
    static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public LoginRateLimitFilter loginRateLimitFilter() {
        return new LoginRateLimitFilter();
    }

    @Bean
    public UserDetailsService userDetailsService(
            PasswordEncoder passwordEncoder,
            @Value("${app.security.username}") String username,
            @Value("${app.security.password}") String password) {
        if (password == null || password.length() < MIN_RECOMMENDED_PASSWORD_LENGTH) {
            // Solo advierte (no impide el arranque) para no romper despliegues existentes.
            log.warn("APP_PASSWORD tiene menos de {} caracteres. Se recomienda una contraseña más larga "
                    + "(ISO/IEC 27002 5.17 «Información de autenticación»).", MIN_RECOMMENDED_PASSWORD_LENGTH);
        }
        UserDetails user = User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public AuthenticationEntryPoint customAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            // No agregamos WWW-Authenticate: Basic para suprimir el popup nativo del navegador
            response.getWriter().write("{\"error\":\"No autorizado\",\"message\":\"Se requiere autenticación válida\",\"authenticated\":false}");
        };
    }

    /**
     * Auditoría de autenticaciones (ISO/IEC 27002:2022 8.15). Spring Security publica estos eventos
     * cada vez que se validan credenciales (login por Basic); las peticiones posteriores viajan con la
     * cookie de sesión y no generan eventos, así que no se inunda el log.
     */
    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        AuditLog.event("LOGIN", AuditLog.SUCCESS, event.getAuthentication().getName(), AuditLog.currentIp(), null);
    }

    @EventListener
    public void onAuthenticationFailure(AbstractAuthenticationFailureEvent event) {
        AuditLog.event("LOGIN", AuditLog.FAILURE, String.valueOf(event.getAuthentication().getName()), AuditLog.currentIp(),
                AuditLog.details("reason", event.getException().getClass().getSimpleName()));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, LoginRateLimitFilter loginRateLimitFilter,
            @Value("${app.security.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String allowedOrigins,
            @Value("${app.security.session-max-lifetime:8h}") Duration sessionMaxLifetime) throws Exception {
        // Se instancian aquí (no como @Bean) para que Spring Boot no los registre además como
        // filtros globales del contenedor: solo deben correr dentro de la cadena de seguridad.
        OriginCheckFilter originCheckFilter = new OriginCheckFilter(allowedOrigins);
        SessionLifetimeFilter sessionLifetimeFilter = new SessionLifetimeFilter(sessionMaxLifetime);
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(customAuthenticationEntryPoint()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/api/auth/status").permitAll()
                .requestMatchers("/api/db/connection/presets").permitAll()
                .requestMatchers("/actuator/**").authenticated()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .httpBasic(basic -> basic.authenticationEntryPoint(customAuthenticationEntryPoint()))
            .addFilterBefore(sessionLifetimeFilter, SecurityContextHolderFilter.class)
            .addFilterBefore(originCheckFilter, BasicAuthenticationFilter.class)
            .addFilterBefore(loginRateLimitFilter, BasicAuthenticationFilter.class)
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY))
                // HSTS: Spring Security solo la emite en peticiones HTTPS (detrás de un proxy, requiere
                // server.forward-headers-strategy=native, ver perfil prod).
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
            );
        return http.build();
    }
}
