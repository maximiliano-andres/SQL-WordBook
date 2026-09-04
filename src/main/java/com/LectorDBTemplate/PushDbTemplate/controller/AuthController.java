package com.LectorDBTemplate.PushDbTemplate.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controlador de autenticación para validar credenciales y verificar el estado de la sesión
 * desde el frontend, permitiendo una experiencia de inicio de sesión visual integrada.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * Retorna el estado actual de autenticación del usuario.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getAuthStatus() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.ok(Map.of(
                    "authenticated", true,
                    "username", auth.getName()
            ));
        }
        return ResponseEntity.ok(Map.of(
                "authenticated", false
        ));
    }

    /**
     * Endpoint de inicio de sesión. Si la petición llega aquí con credenciales Basic válidas,
     * Spring Security ya las habrá validado; de lo contrario, el AuthenticationEntryPoint
     * responderá 401 sin disparar el popup nativo del navegador.
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "authenticated", true,
                    "username", auth.getName()
            ));
        }
        return ResponseEntity.status(401).body(Map.of(
                "success", false,
                "authenticated", false,
                "error", "Credenciales no válidas"
        ));
    }
}
