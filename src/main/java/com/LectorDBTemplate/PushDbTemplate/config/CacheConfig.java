package com.LectorDBTemplate.PushDbTemplate.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Separado de la clase principal a propósito: si @EnableCaching viviera en
 * PushDbTemplateApplication (la clase raíz de configuración que reutilizan
 * los slice tests como @WebMvcTest), esos tests fallarían exigiendo un
 * CacheManager aunque no necesiten caché. Como @Configuration independiente,
 * los filtros de los slice tests lo excluyen salvo que se importe explícitamente.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
