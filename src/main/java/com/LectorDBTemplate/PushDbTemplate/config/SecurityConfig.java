package com.LectorDBTemplate.PushDbTemplate.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
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
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, LoginRateLimitFilter loginRateLimitFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(customAuthenticationEntryPoint()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/auth/status").permitAll()
                .requestMatchers("/api/db/connection/presets").permitAll()
                .requestMatchers("/actuator/**").authenticated()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .httpBasic(basic -> basic.authenticationEntryPoint(customAuthenticationEntryPoint()))
            .addFilterBefore(loginRateLimitFilter, BasicAuthenticationFilter.class)
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; script-src 'self'"))
            );
        return http.build();
    }
}
