package com.emerald.lakehouse.parser.silver

import com.emerald.coolector.parser.TimeRecord
import java.io.File
import java.sql.Connection
import java.sql.Timestamp

/**
 * Escribe la salida decodificada de [com.emerald.lakehouse.parser.decode.TelemetryDecoder]
 * a Parquet real en el bucket `silver` (hoy solo tiene un placeholder `.keep`) y la registra
 * incrementalmente en la tabla `silver.telemetry_decoded` del catálogo DuckLake — **distinta**
 * de las vistas `silver.telemetry_readings_summary`/`silver.telemetry_documents` que ya crea
 * `DuckLakeSync.kt` (esas siguen reconstruyendo hex crudo, no decodificado; no se tocan).
 *
 * Particionado igual que Bronze: `silver/telemetry/technology=<tech>/device_id=<device>/
 * year=<YYYY>/month=<MM>/day=<DD>/`. El registro en la tabla del catálogo se hace leyendo
 * la misma tabla de staging que ya se usó para el `COPY` (sin releer desde S3 — son
 * exactamente las mismas filas).
 */
object SilverWriter {

    private const val STAGING_TABLE = "decoded_records_staging"
    private const val CATALOG_TABLE = "silver.telemetry_decoded"

    /** @return el prefijo S3 donde quedó escrito el Parquet, o null si no había nada que escribir. */
    fun write(
        conn: Connection,
        records: List<TimeRecord>,
        technology: String,
        deviceId: String,
        extractionId: String,
        silverRoot: String = "s3://silver",
    ): String? {
        if (records.isEmpty()) return null

        conn.createStatement().use { stmt ->
            stmt.execute(
                "CREATE OR REPLACE TABLE $STAGING_TABLE " +
                    "(name VARCHAR, value DOUBLE, timestamp TIMESTAMP, technology VARCHAR, device_id VARCHAR, extraction_id VARCHAR)",
            )
        }
        conn.prepareStatement("INSERT INTO $STAGING_TABLE VALUES (?, ?, ?, ?, ?, ?)").use { ps ->
            for (record in records) {
                ps.setString(1, record.name)
                ps.setDouble(2, record.value)
                ps.setTimestamp(3, Timestamp(record.timestampMillis))
                ps.setString(4, technology)
                ps.setString(5, deviceId)
                ps.setString(6, extractionId)
                ps.addBatch()
            }
            ps.executeBatch()
        }

        val safeTech = sanitize(technology)
        val safeDevice = sanitize(deviceId)
        val safeExtraction = sanitize(extractionId)
        val destinationPath = "${silverRoot.trimEnd('/')}/telemetry/technology=$safeTech/device_id=$safeDevice/"

        // S3/MinIO no tiene "directorios" reales y crea el prefijo al escribir el primer
        // objeto — pero un filesystem local sí los necesita de antemano (solo aplica en
        // tests; en producción destinationPath siempre es `s3://...`).
        if (!destinationPath.contains("://")) {
            File(destinationPath).mkdirs()
        }

        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                COPY (
                    SELECT *, date_part('year', timestamp) AS year, date_part('month', timestamp) AS month, date_part('day', timestamp) AS day
                    FROM $STAGING_TABLE
                ) TO '$destinationPath' (
                    FORMAT PARQUET,
                    PARTITION_BY (year, month, day),
                    OVERWRITE_OR_IGNORE,
                    FILENAME_PATTERN 'silver_${safeExtraction}_{uuid}'
                )
                """.trimIndent(),
            )
        }

        upsertCatalogTable(conn)
        return destinationPath
    }

    private fun upsertCatalogTable(conn: Connection) {
        val tableExists = conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT count(*) FROM information_schema.tables " +
                    "WHERE table_schema = 'silver' AND table_name = 'telemetry_decoded'",
            ).use { rs -> rs.next() && rs.getInt(1) > 0 }
        }

        conn.createStatement().use { stmt ->
            if (!tableExists) {
                stmt.execute("CREATE TABLE $CATALOG_TABLE AS SELECT * FROM $STAGING_TABLE")
            } else {
                stmt.execute("INSERT INTO $CATALOG_TABLE BY NAME SELECT * FROM $STAGING_TABLE")
            }
        }
    }

    /** Mismos reemplazos que `GoldWriter`/`ObjectKeyBuilder` en los otros servicios del ecosistema. */
    private fun sanitize(value: String): String {
        val cleaned = value.trim()
        if (cleaned.isEmpty()) return "unknown"
        return cleaned.replace("/", "_").replace("\\", "_").replace(" ", "_").replace(":", "")
    }
}
