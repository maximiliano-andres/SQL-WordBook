package com.LectorDBTemplate.PushDbTemplate.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Separado de la clase principal a propósito: si @EnableCaching viviera en
 * PushDbTemplateApplication (la clase raíz de configuración que reutilizan
 * los slice tests como @WebMvcTest), esos tests fallarían exigiendo un
 * CacheManager aunque no necesiten caché. Como @Configuration independiente,
 * los filtros de los slice tests lo excluyen salvo que se importe explícitamente.
 *
 * Cada caché tiene un tamaño y TTL propios en vez de un único spec compartido
 * (antes: expireAfterWrite=60s,maximumSize=200 para las 5 cachés vía
 * application.yaml). Con un spec compartido, un esquema con más de 200 tablas
 * sufre evicción constante justo en "tables"/"columns"/"foreignKeys" — donde
 * más se necesita el caché. Los metadatos de esquema cambian poco (una
 * migración de BD, no cada request), así que se les da TTL largo y tamaño
 * generoso; "tableCount" sí refleja datos que cambian, así que mantiene un
 * TTL corto.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache("tables", metadataCache().build());
        manager.registerCustomCache("columns", metadataCache().build());
        manager.registerCustomCache("foreignKeys", metadataCache().build());
        manager.registerCustomCache("fkDisplayColumn", metadataCache().build());
        manager.registerCustomCache("tableCount", Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(30))
                .maximumSize(5_000)
                .build());
        return manager;
    }

    // Metadatos de esquema (tablas/columnas/FKs/columna descriptiva): cambian solo
    // cuando alguien migra la base de datos, no en cada request. TTL de 10 minutos
    // (se refresca solo) + tamaño generoso para no desalojar en esquemas grandes.
    // saveCustomFks() igual invalida explícitamente la entrada afectada al vuelo
    // (ver DatabaseService.evictCacheForTable), así que el TTL largo no retrasa
    // la visibilidad de cambios hechos desde la propia aplicación.
    private Caffeine<Object, Object> metadataCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(10))
                .maximumSize(5_000);
    }
}
