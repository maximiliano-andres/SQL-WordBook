package com.LectorDBTemplate.PushDbTemplate.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Filtro de Servlet que redirige las solicitudes HTTP directas a "/index.html"
 * hacia la raíz "/". Evita bucles de redirección porque solo intercepta
 * las peticiones del navegador (REQUEST dispatch) y no los reenvíos internos
 * (FORWARD dispatch) que usa Spring Boot para servir index.html desde la raíz.
 */
@Component
public class IndexRedirectFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String uri = httpRequest.getRequestURI();
        // Solo redirigir si el cliente solicita directamente /index.html
        if ("/index.html".equals(uri)) {
            httpResponse.sendRedirect("/");
            return;
        }

        chain.doFilter(request, response);
    }
}
