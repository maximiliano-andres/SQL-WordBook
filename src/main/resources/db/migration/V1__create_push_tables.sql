-- Esquema propio de la aplicación (no del negocio): relaciones de Foreign Key virtuales
-- configuradas manualmente desde el Explorador de Tablas, y plantillas guardadas del
-- Constructor de Reportes Personalizados.
--
-- V1 usa guardas "IF NOT EXISTS" a propósito, a diferencia de las migraciones futuras:
-- esta app creaba estas mismas tablas con DDL condicional embebido en código Java
-- (DatabaseService.ensureCustomFksTableExists/ensureCustomReportsTableExists) antes de
-- adoptar Flyway. En cualquier base de datos donde la app ya corrió, las tablas ya
-- existen pero Flyway nunca las registró; esta guarda hace que V1 sea un no-op seguro
-- ahí, y una creación real en una base de datos nueva. Desde V2 en adelante ya no hace
-- falta este patrón: Flyway garantiza que cada migración se ejecuta una sola vez.

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'push_custom_fks' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.push_custom_fks (
        schema_name NVARCHAR(128) NOT NULL,
        table_name NVARCHAR(128) NOT NULL,
        fk_column NVARCHAR(128) NOT NULL,
        referenced_schema NVARCHAR(128) NOT NULL,
        referenced_table NVARCHAR(128) NOT NULL,
        referenced_column NVARCHAR(128) NOT NULL,
        display_column NVARCHAR(256) NULL,
        filter_column NVARCHAR(128) NULL,
        filter_value NVARCHAR(256) NULL,
        enabled BIT NOT NULL CONSTRAINT DF_push_custom_fks_enabled DEFAULT 1,
        CONSTRAINT PK_push_custom_fks PRIMARY KEY (schema_name, table_name, fk_column)
    )
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'push_custom_reports' AND schema_id = SCHEMA_ID('dbo'))
BEGIN
    CREATE TABLE dbo.push_custom_reports (
        id NVARCHAR(64) NOT NULL CONSTRAINT PK_push_custom_reports PRIMARY KEY,
        name NVARCHAR(255) NOT NULL,
        description NVARCHAR(1000) NULL,
        config_json NVARCHAR(MAX) NOT NULL,
        created_at DATETIME2 NOT NULL CONSTRAINT DF_push_custom_reports_created DEFAULT GETDATE(),
        updated_at DATETIME2 NOT NULL CONSTRAINT DF_push_custom_reports_updated DEFAULT GETDATE()
    )
END
GO

-- Índice de apoyo para la búsqueda de FKs virtuales "entrantes" (qué tablas apuntan a una
-- tabla dada), usado por DatabaseService.loadCustomFksReferencing(). Antes de esta migración
-- esa búsqueda no existía como consulta directa (se resolvía recorriendo todo el catálogo).
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_push_custom_fks_referenced' AND object_id = OBJECT_ID('dbo.push_custom_fks'))
BEGIN
    CREATE INDEX IX_push_custom_fks_referenced ON dbo.push_custom_fks (referenced_schema, referenced_table)
END
GO
