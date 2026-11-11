package com.LectorDBTemplate.PushDbTemplate.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Enmascaramiento opcional de columnas (ISO/IEC 27002 8.11). */
class SensitiveColumnMaskerTest {

    @Test
    void emptyConfig_masksNothing() {
        SensitiveColumnMasker masker = new SensitiveColumnMasker("");
        assertThat(masker.isEnabled()).isFalse();
        assertThat(masker.isSensitive("sueldo_base")).isFalse();
    }

    @Test
    void patterns_areCaseInsensitive_andSupportWildcards() {
        SensitiveColumnMasker masker = new SensitiveColumnMasker(" sueldo* , RUT, *password* ");
        assertThat(masker.isSensitive("sueldo_base")).isTrue();
        assertThat(masker.isSensitive("SUELDO")).isTrue();
        assertThat(masker.isSensitive("rut")).isTrue();
        assertThat(masker.isSensitive("rut_empresa")).isFalse();
        assertThat(masker.isSensitive("db_password_hash")).isTrue();
        assertThat(masker.isSensitive("nombre")).isFalse();
    }

    @Test
    void regexCharacters_inConfig_areTreatedLiterally() {
        SensitiveColumnMasker masker = new SensitiveColumnMasker("monto.(clp)");
        assertThat(masker.isSensitive("monto.(clp)")).isTrue();
        assertThat(masker.isSensitive("montoX(clp)")).isFalse();
    }

    @Test
    void maskRows_replacesOnlySensitiveNonNullValues_mappingLabelsToColumns() {
        SensitiveColumnMasker masker = new SensitiveColumnMasker("sueldo*");
        Map<String, Object> row1 = new HashMap<>(Map.of("Nombre", "Ana", "Sueldo mensual", 1_250_000));
        Map<String, Object> row2 = new HashMap<>();
        row2.put("Nombre", "Luis");
        row2.put("Sueldo mensual", null);
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row1, row2));

        masker.maskRows(rows, Map.of("Nombre", "nombre", "Sueldo mensual", "sueldo_base"));

        assertThat(row1).containsEntry("Sueldo mensual", SensitiveColumnMasker.MASK).containsEntry("Nombre", "Ana");
        assertThat(row2.get("Sueldo mensual")).isNull();
    }
}
