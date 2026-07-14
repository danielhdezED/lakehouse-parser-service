package com.emerald.lakehouse.parser.config

/**
 * Defaults coinciden con los que ya usan `lakehouse-ingestion-api`/`lakehouse-silver-service`
 * (minioadmin/minioadmin, postgres/ducklake, buckets bronze/silver) — mismo laboratorio,
 * comportamiento sin cambios si `docker-compose.yml` no pasa overrides.
 *
 * `parserCipherKeyPath`/`parserImberaBlobPath` son las variables nuevas de este servicio:
 * apuntan a DOS archivos montados de solo lectura desde fuera del repo (semilla `parsingKey`
 * + blob ofuscado `IMBERA_KEY_BLOB`, ver `CipherKeyLoader.kt` para el porqué de dos archivos)
 * — mismo patrón que la Intermediate CA de mTLS en `lakehouse-ingestion-api`. Este es el
 * **único** servicio del ecosistema con acceso a esa llave (ver
 * docs/ai/security-guidelines.md en Coolector-SDK) — si cualquiera de los dos archivos no
 * existe, el servicio sigue funcionando decodificando solo firmwares sin cifrar (v126).
 */
data class AppConfig(
    val minioEndpoint: String,
    val minioAccessKey: String,
    val minioSecretKey: String,
    val bronzeBucketName: String,
    val silverBucketName: String,
    val port: Int,
    val duckLakeCatalogHost: String,
    val duckLakeCatalogPort: Int,
    val duckLakeCatalogDb: String,
    val duckLakeCatalogUser: String,
    val duckLakeCatalogPassword: String,
    val duckLakeDataPath: String,
    val parserCipherKeyPath: String,
    val parserImberaBlobPath: String,
) {
    companion object {
        fun fromEnvironment(): AppConfig = AppConfig(
            minioEndpoint = System.getenv("MINIO_ENDPOINT") ?: "minio:9000",
            minioAccessKey = System.getenv("MINIO_ACCESS_KEY") ?: "minioadmin",
            minioSecretKey = System.getenv("MINIO_SECRET_KEY") ?: "minioadmin",
            bronzeBucketName = System.getenv("BRONZE_BUCKET_NAME") ?: "bronze",
            silverBucketName = System.getenv("SILVER_BUCKET_NAME") ?: "silver",
            port = System.getenv("PORT")?.toIntOrNull() ?: 8082,
            duckLakeCatalogHost = System.getenv("DUCKLAKE_CATALOG_HOST") ?: "postgres",
            duckLakeCatalogPort = System.getenv("DUCKLAKE_CATALOG_PORT")?.toIntOrNull() ?: 5432,
            duckLakeCatalogDb = System.getenv("DUCKLAKE_CATALOG_DB") ?: "ducklake_catalog",
            duckLakeCatalogUser = System.getenv("DUCKLAKE_CATALOG_USER") ?: "ducklake",
            duckLakeCatalogPassword = System.getenv("DUCKLAKE_CATALOG_PASSWORD") ?: "ducklake",
            duckLakeDataPath = System.getenv("DUCKLAKE_DATA_PATH") ?: "s3://ducklake-catalog/",
            parserCipherKeyPath = System.getenv("PARSER_CIPHER_KEY_PATH")
                ?: "/etc/lakehouse-mtls/parser-cipher-key",
            parserImberaBlobPath = System.getenv("PARSER_IMBERA_BLOB_PATH")
                ?: "/etc/lakehouse-mtls/parser-imbera-blob",
        )
    }
}
