package com.LectorDBTemplate.PushDbTemplate.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** El registro de auditoría no debe permitir inyectar líneas falsas (CWE-117). */
class AuditLogTest {

    @Test
    void clean_neutralizesLineBreaks_andQuotesValuesWithSpaces() {
        assertThat(AuditLog.clean("admin\nevent=LOGIN outcome=SUCCESS")).doesNotContain("\n").startsWith("\"");
        assertThat(AuditLog.clean("dbo.Empleados")).isEqualTo("dbo.Empleados");
        assertThat(AuditLog.clean(null)).isEqualTo("-");
        assertThat(AuditLog.clean("")).isEqualTo("-");
    }

    @Test
    void clean_truncatesVeryLongValues() {
        assertThat(AuditLog.clean("x".repeat(500))).hasSizeLessThanOrEqualTo(201);
    }

    @Test
    void details_keepsInsertionOrder() {
        assertThat(AuditLog.details("a", 1, "b", 2, "c", 3).keySet()).containsExactly("a", "b", "c");
    }

    @Test
    void currentUser_withoutAuthentication_isAnonymous() {
        assertThat(AuditLog.currentUser()).isEqualTo("anonymous");
        assertThat(AuditLog.currentIp()).isEqualTo("-");
    }
}
