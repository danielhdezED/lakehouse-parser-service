package com.emerald.lakehouse.parser.duckdb

import com.emerald.lakehouse.parser.config.AppConfig
import java.sql.Connection
import java.sql.Statement

/**
 * Conecta una sesión DuckDB embebida al mismo catálogo DuckLake que ya usa
 * `DuckLakeSync.kt` (lakehouse-ingestion-api) y `DuckLakeCatalog.kt` (lakehouse-silver-service)
 * — mismo patrón de `ATTACH`, garantiza que existan los schemas `bronze`/`silver`/`gold`.
 */
object DuckLakeCatalog {

    fun attach(conn: Connection, config: AppConfig) {
        conn.createStatement().use { stmt ->
            stmt.execute("INSTALL httpfs; LOAD httpfs;")
            stmt.execute("INSTALL ducklake; LOAD ducklake;")
            stmt.execute("INSTALL postgres; LOAD postgres;")
            stmt.execute("SET s3_endpoint='${config.minioEndpoint}';")
            stmt.execute("SET s3_access_key_id='${config.minioAccessKey}';")
            stmt.execute("SET s3_secret_access_key='${config.minioSecretKey}';")
            stmt.execute("SET s3_use_ssl=false;")
            stmt.execute("SET s3_url_style='path';")
            stmt.execute(
                "ATTACH 'ducklake:postgres:dbname=${config.duckLakeCatalogDb} " +
                    "user=${config.duckLakeCatalogUser} password=${config.duckLakeCatalogPassword} " +
                    "host=${config.duckLakeCatalogHost} port=${config.duckLakeCatalogPort}' " +
                    "AS coolector_lake (DATA_PATH '${config.duckLakeDataPath}');",
            )
            stmt.execute("USE coolector_lake;")
            createSchemas(stmt)
        }
    }

    private fun createSchemas(stmt: Statement) {
        stmt.execute("CREATE SCHEMA IF NOT EXISTS bronze;")
        stmt.execute("CREATE SCHEMA IF NOT EXISTS silver;")
        stmt.execute("CREATE SCHEMA IF NOT EXISTS gold;")
    }
}
