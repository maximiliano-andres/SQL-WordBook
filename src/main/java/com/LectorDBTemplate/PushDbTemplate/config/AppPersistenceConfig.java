package com.LectorDBTemplate.PushDbTemplate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Expone un JdbcTemplate fijo sobre el DataSource propio de la aplicación (el mismo que
 * Flyway migra al arrancar, definido por spring.datasource.* en application.yaml).
 *
 * Se usa exclusivamente para leer/escribir el esquema propio de la app (dbo.push_custom_fks,
 * dbo.push_custom_reports), NUNCA para consultar datos de negocio. A diferencia de
 * DynamicDataSourceService.getJdbcTemplate() -que apunta a la conexión que el usuario elige
 * en caliente desde el frontend y puede cambiar de motor en cualquier momento- este bean
 * siempre apunta a la misma base de datos, así que las FKs personalizadas y las plantillas de
 * reportes persisten de forma confiable sin importar qué base de datos esté explorando el
 * usuario (ver auditoría, hallazgo crítico: antes de este cambio esas escrituras se perdían
 * silenciosamente al cambiar de conexión y caían a un mapa en memoria que no sobrevivía un
 * reinicio ni se compartía entre usuarios).
 */
@Configuration
public class AppPersistenceConfig {

    @Bean
    public JdbcTemplate appJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
