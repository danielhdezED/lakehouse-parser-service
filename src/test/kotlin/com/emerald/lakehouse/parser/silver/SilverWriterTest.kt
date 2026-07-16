package com.emerald.lakehouse.parser.silver

import com.emerald.coolector.parser.TimeRecord
import java.io.File
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Valida SilverWriter escribiendo a un directorio local en vez de `s3://silver` real (DuckDB
 * `COPY ... TO` acepta cualquier path con la misma sintaxis) — confirma el layout de
 * particiones y que la tabla `silver.telemetry_decoded` queda registrada en el catálogo,
 * sin necesitar MinIO/Postgres reales.
 */
class SilverWriterTest {

    @Test
    fun `writes decoded records partitioned by technology, device and date, and registers the catalog table`() {
        val silverRoot = createTempDirectory("silver-writer-test").toFile()

        Class.forName("org.duckdb.DuckDBDriver")
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { it.execute("CREATE SCHEMA silver") }

            val records = listOf(
                TimeRecord("compressor_state", 1.0, 1780000000000L),
                TimeRecord("door_state", 0.0, 1780000000000L),
            )

            val destination = SilverWriter.write(
                conn = conn,
                records = records,
                technology = "ImberaLink",
                deviceId = "B4A2EB428B04",
                extractionId = "ext-1",
                silverRoot = silverRoot.path,
            )

            assertTrue(destination != null)

            val partitionDir = File(silverRoot, "telemetry/technology=ImberaLink/device_id=B4A2EB428B04/year=2026/month=5/day=28")
            assertTrue(partitionDir.isDirectory, "expected partition directory, got: ${silverRoot.walkTopDown().toList()}")
            assertTrue(partitionDir.listFiles { f -> f.extension == "parquet" }.orEmpty().isNotEmpty())

            val rowCount = conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT count(*) FROM silver.telemetry_decoded").use { rs -> rs.next(); rs.getInt(1) }
            }
            assertEquals(2, rowCount)
        }
    }

    @Test
    fun `empty records write nothing and return null`() {
        Class.forName("org.duckdb.DuckDBDriver")
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { it.execute("CREATE SCHEMA silver") }

            val destination = SilverWriter.write(conn, emptyList(), "ImberaLink", "X", "ext-1")

            assertTrue(destination == null)
        }
    }

    /**
     * Regresión del bug real documentado en docs/ai/lakehouse-audit-2026-07-16.md
     * (Coolector-SDK, hallazgo C1): una redelivery del mismo webhook de MinIO para el
     * mismo objeto Bronze (mismo `extraction_id`) volvía a llamar `SilverWriter.write`
     * con el mismo `extraction_id` — antes del fix, el `FILENAME_PATTERN` con `{uuid}`
     * generaba un Parquet nuevo cada vez (nunca colisionaba con el anterior) y
     * `upsertCatalogTable` insertaba sin comprobar si esas filas ya existían, duplicando
     * el historial del dispositivo en `silver.telemetry_decoded` — de donde
     * `AnalyticsPipeline` lee para recalcular Gold, propagando la duplicación en cascada.
     * Fix: nombre de archivo determinístico por `extraction_id` (sin `{uuid}`) +
     * `DELETE` por `(technology, device_id, extraction_id)` antes del `INSERT`.
     */
    @Test
    fun `redelivering the same extraction replaces its rows and file instead of duplicating them`() {
        val silverRoot = createTempDirectory("silver-writer-redelivery-test").toFile()

        Class.forName("org.duckdb.DuckDBDriver")
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { it.execute("CREATE SCHEMA silver") }

            val firstDelivery = listOf(
                TimeRecord("compressor_state", 1.0, 1780000000000L),
                TimeRecord("door_state", 0.0, 1780000000000L),
            )
            SilverWriter.write(
                conn = conn,
                records = firstDelivery,
                technology = "ImberaLink",
                deviceId = "B4A2EB428B04",
                extractionId = "ext-redelivered",
                silverRoot = silverRoot.path,
            )

            // Simula una redelivery del mismo webhook para el mismo objeto Bronze/extracción
            // (mismo extraction_id, contenido potencialmente distinto si se reprocesó).
            val redelivery = listOf(
                TimeRecord("compressor_state", 1.0, 1780000000000L),
                TimeRecord("door_state", 0.0, 1780000000000L),
                TimeRecord("internal_temperature", 4.5, 1780000000000L),
            )
            SilverWriter.write(
                conn = conn,
                records = redelivery,
                technology = "ImberaLink",
                deviceId = "B4A2EB428B04",
                extractionId = "ext-redelivered",
                silverRoot = silverRoot.path,
            )

            val partitionDir = File(silverRoot, "telemetry/technology=ImberaLink/device_id=B4A2EB428B04/year=2026/month=5/day=28")
            val parquetFiles = partitionDir.listFiles { f -> f.extension == "parquet" }.orEmpty()
            assertEquals(1, parquetFiles.size, "expected the redelivery to replace the file, got: ${parquetFiles.map { it.name }}")

            val rowCount = conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT count(*) FROM silver.telemetry_decoded WHERE extraction_id = 'ext-redelivered'",
                ).use { rs -> rs.next(); rs.getInt(1) }
            }
            assertEquals(3, rowCount, "expected only the redelivery's 3 rows, not accumulated with the first delivery's 2")
        }
    }

    /**
     * Confirma que el fix de idempotencia no rompe el caso normal: dos extracciones
     * distintas del mismo dispositivo (mismo día) deben acumularse, no reemplazarse —
     * el nombre de archivo determinístico usa `extraction_id`, no solo `device_id`.
     */
    @Test
    fun `two different extractions for the same device and day both accumulate in the catalog`() {
        val silverRoot = createTempDirectory("silver-writer-two-extractions-test").toFile()

        Class.forName("org.duckdb.DuckDBDriver")
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { it.execute("CREATE SCHEMA silver") }

            SilverWriter.write(
                conn = conn,
                records = listOf(TimeRecord("compressor_state", 1.0, 1780000000000L)),
                technology = "ImberaLink",
                deviceId = "B4A2EB428B04",
                extractionId = "ext-a",
                silverRoot = silverRoot.path,
            )
            SilverWriter.write(
                conn = conn,
                records = listOf(TimeRecord("door_state", 0.0, 1780000000000L)),
                technology = "ImberaLink",
                deviceId = "B4A2EB428B04",
                extractionId = "ext-b",
                silverRoot = silverRoot.path,
            )

            val partitionDir = File(silverRoot, "telemetry/technology=ImberaLink/device_id=B4A2EB428B04/year=2026/month=5/day=28")
            val parquetFiles = partitionDir.listFiles { f -> f.extension == "parquet" }.orEmpty()
            assertEquals(2, parquetFiles.size, "expected one file per extraction, got: ${parquetFiles.map { it.name }}")

            val rowCount = conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT count(*) FROM silver.telemetry_decoded").use { rs -> rs.next(); rs.getInt(1) }
            }
            assertEquals(2, rowCount, "expected rows from both extractions to accumulate")
        }
    }
}
