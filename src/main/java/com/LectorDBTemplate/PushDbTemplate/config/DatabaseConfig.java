package com.LectorDBTemplate.PushDbTemplate.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Clase de configuración para la base de datos SQL Server.
 * Actualmente aprovecha la autoconfiguración nativa y optimizada de Spring Boot
 * con HikariCP definida en application.yaml.
 */
@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    public DatabaseConfig() {
        log.info("Inicializando configuración optimizada de base de datos SQL Server...");
    }
}
