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
}
