package com.LectorDBTemplate.PushDbTemplate.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Enmascaramiento opcional de columnas sensibles (ISO/IEC 27002:2022 8.11 «Enmascaramiento de
 * datos» y 5.34 «Privacidad y protección de datos personales»).
 *
 * Se configura con APP_MASKED_COLUMNS: lista separada por comas de nombres de columna, sin
 * distinguir mayúsculas, con comodín «*» (p. ej. {@code sueldo*,rut,*_password}). Las columnas
 * que coinciden se muestran como {@value #MASK} en la grilla, en la vista previa de reportes y en
 * los Excel exportados. Si la variable está vacía (valor por defecto) no se enmascara nada y la
 * app se comporta exactamente como antes.
 *
 * Limitación conocida: la columna sigue pudiendo usarse en filtros y ordenamientos, por lo que un
 * usuario podría inferir rangos de valores. El objetivo es evitar la exposición casual (pantallas
 * compartidas, archivos reenviados), no reemplazar los permisos de la base de datos.
 */
@Component
public class SensitiveColumnMasker {

    public static final String MASK = "******";

    private final List<Pattern> patterns;

    public SensitiveColumnMasker(@Value("${app.privacy.masked-columns:}") String maskedColumnsCsv) {
        this.patterns = Arrays.stream(maskedColumnsCsv == null ? new String[0] : maskedColumnsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(SensitiveColumnMasker::toPattern)
                .toList();
    }

    /** Instancia que no enmascara nada (compatibilidad con código que no la configura). */
    public static SensitiveColumnMasker disabled() {
        return new SensitiveColumnMasker("");
    }

    public boolean isEnabled() {
        return !patterns.isEmpty();
    }

    public boolean isSensitive(String columnName) {
        if (columnName == null || patterns.isEmpty()) {
            return false;
        }
        String normalized = columnName.trim().toLowerCase(Locale.ROOT);
        for (Pattern p : patterns) {
            if (p.matcher(normalized).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Enmascara en sitio las filas: {@code columnKeyToSourceColumn} asocia cada clave del Map de la
     * fila con el nombre real de la columna en la base (en la grilla son iguales; en los reportes la
     * clave es la etiqueta elegida por el usuario).
     */
    public void maskRows(List<Map<String, Object>> rows, Map<String, String> columnKeyToSourceColumn) {
        if (!isEnabled() || rows == null) {
            return;
        }
        List<String> keysToMask = columnKeyToSourceColumn.entrySet().stream()
                .filter(e -> isSensitive(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        if (keysToMask.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : rows) {
            for (String key : keysToMask) {
                if (row.containsKey(key) && row.get(key) != null) {
                    row.put(key, MASK);
                }
            }
        }
    }

    private static Pattern toPattern(String glob) {
        // "sueldo*" -> \Qsueldo\E.*  ;  "*_pass*" -> .*\Q_pass\E.*  (cada «*» se vuelve «.*»)
        String[] parts = glob.toLowerCase(Locale.ROOT).split("\\*", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            if (!parts[i].isEmpty()) {
                regex.append(Pattern.quote(parts[i]));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
