package com.emerald.lakehouse.parser

import java.io.File
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end: un objeto Bronze real (local, no S3) -> decode v126 real (sin cifrado) ->
 * Silver Parquet real (local) + tabla `silver.telemetry_decoded` registrada — confirma que
 * `BronzeObjectReader`/`TelemetryDecoder`/`SilverWriter` encajan igual que en la suite de
 * cada pieza por separado, corridos juntos como los correría `WebhookRoute` en producción.
 */
class ParserPipelineTest {

    @Test
    fun `decodes a single Bronze object and writes it to Silver`() {
        Class.forName("org.duckdb.DuckDBDriver")
        val bronzePath = createTempFile("parser-pipeline-bronze", ".parquet").toString()
        val silverRoot = createTempDirectory("parser-pipeline-silver").toFile()

        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { it.execute("CREATE SCHEMA silver") }
            conn.createStatement().use { stmt ->
                // Mismo payload v126 (compressor OFF->ON->OFF, epoch ~2026-05-28) usado en
                // los tests de la Fase 2 — un solo objeto Bronze con 3 documentos EVENTS.
                stmt.execute(
                    """
                    COPY (
                        SELECT * FROM (VALUES
                            ('ext-1', 'B4A2EB428B04', 'ImberaLink', NULL, '126', 1, 'EVENTS', 0, '6a18a5000000'),
                            ('ext-1', 'B4A2EB428B04', 'ImberaLink', NULL, '126', 2, 'EVENTS', 0, '6a18b3100080'),
                            ('ext-1', 'B4A2EB428B04', 'ImberaLink', NULL, '126', 3, 'EVENTS', 0, '6a18c1200000')
                        ) AS t(extraction_id, id_controller, technology, id_type, ble_version, document_sequence, type, chunk_index, data_chunk_hex)
                    ) TO '$bronzePath' (FORMAT PARQUET)
                    """.trimIndent(),
                )
            }

            val destination = ParserPipeline.run(conn, bronzePath, silverRoot.path, cipher = null)

            assertNotNull(destination)

            val partitionDir = File(silverRoot, "telemetry/technology=ImberaLink/device_id=B4A2EB428B04/year=2026/month=5/day=28")
            assertTrue(partitionDir.isDirectory, "expected silver partition, got: ${silverRoot.walkTopDown().toList()}")
            assertTrue(partitionDir.listFiles { f -> f.extension == "parquet" }.orEmpty().isNotEmpty())

            val rowCount = conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT count(*) FROM silver.telemetry_decoded").use { rs -> rs.next(); rs.getInt(1) }
            }
            assertTrue(rowCount > 0)
        }
    }
}
