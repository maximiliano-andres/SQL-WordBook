package com.LectorDBTemplate.PushDbTemplate.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registro de auditoría de eventos de seguridad y de acceso a datos (ISO/IEC 27002:2022,
 * controles 8.15 «Registro» y 8.16 «Actividades de monitoreo»).
 *
 * Escribe en el logger dedicado {@code AUDIT}, que logback-spring.xml envía a un archivo propio
 * (logs/audit.log, rotación diaria) además de la consola. Cada línea es una sola entrada con
 * formato clave=valor para que sea fácil de filtrar o ingerir en un SIEM:
 *
 * <pre>event=EXPORT_TABLE outcome=SUCCESS user=admin ip=10.0.0.5 table=dbo.Empleados rows=1520</pre>
 *
 * Reglas: nunca registrar contraseñas, valores de filtros ni datos de negocio; solo QUIÉN hizo
 * QUÉ, CUÁNDO (lo agrega el patrón de log), DESDE DÓNDE y sobre QUÉ objeto. Es una clase de
 * utilidad estática a propósito: así se puede invocar desde controllers, servicios y filtros sin
 * cambiar sus constructores.
 */
public final class AuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";
    public static final String DENIED = "DENIED";

    private AuditLog() {}

    /** Registra un evento con el usuario e IP de la petición en curso. */
    public static void event(String event, String outcome, Map<String, ?> details) {
        event(event, outcome, currentUser(), currentIp(), details);
    }

    /** Registra un evento indicando explícitamente usuario e IP (p. ej. desde un filtro o listener). */
    public static void event(String event, String outcome, String user, String ip, Map<String, ?> details) {
        if (!AUDIT.isInfoEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder(128)
                .append("event=").append(event)
                .append(" outcome=").append(outcome)
                .append(" user=").append(clean(user))
                .append(" ip=").append(clean(ip));
        if (details != null) {
            details.forEach((k, v) -> sb.append(' ').append(k).append('=').append(clean(v)));
        }
        AUDIT.info(sb.toString());
    }

    /** Atajo para armar los detalles conservando el orden de inserción. */
    public static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    public static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "anonymous";
        }
        return auth.getName();
    }

    public static String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return request.getRemoteAddr();
        }
        return "-";
    }

    /**
     * Neutraliza saltos de línea y espacios (evita inyección de líneas falsas en el log, CWE-117)
     * y acota la longitud. Los valores con espacios se entrecomillan.
     */
    static String clean(Object value) {
        if (value == null) {
            return "-";
        }
        String s = String.valueOf(value).replaceAll("[\\r\\n\\t]", "_");
        if (s.length() > 200) {
            s = s.substring(0, 200) + "…";
        }
        if (s.isEmpty()) {
            return "-";
        }
        return s.contains(" ") ? "\"" + s.replace("\"", "'") + "\"" : s;
    }
}
