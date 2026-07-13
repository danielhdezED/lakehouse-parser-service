package com.emerald.lakehouse.parser.bronze

import java.sql.DriverManager
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Confirma que `read_parquet(path)` reconstruye documentos igual que la lectura vía SQL
 * scan de tabla en la Fase 2 — la diferencia real está en que acá el `path` es un archivo
 * físico específico (local en el test, `s3://bronze/<key>` en producción), nunca la tabla
 * `bronze.telemetry_readings`.
 */
class BronzeObjectReaderTest {

    @Test
    fun `reassembles chunked hex payloads in chunk_index order from a single object`() {
        Class.forName("org.duckdb.DuckDBDriver")
        val parquetPath = createTempFile("bronze-object-reader-test", ".parquet").toString()

        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    COPY (
                        SELECT * FROM (VALUES
                            ('ext-1', 'B4A2EB428B04', 'ImberaLink', NULL, '126', 1, 'EVENTS', 1, '424008a8'),
                            ('ext-1', 'B4A2EB428B04', 'ImberaLink', NULL, '126', 1, 'EVENTS', 0, '000f')
                        ) AS t(extraction_id, id_controller, technology, id_type, ble_version, document_sequence, type, chunk_index, data_chunk_hex)
                    ) TO '$parquetPath' (FORMAT PARQUET)
                    """.trimIndent(),
                )
            }

            val documents = BronzeObjectReader.readDocuments(conn, parquetPath)

            assertEquals(1, documents.size)
            val document = documents.single()
            assertEquals("000f424008a8", document.payloadHex)
            assertEquals("126", document.bleVersion)
            assertEquals("ImberaLink", document.technology)
        }
    }

    @Test
    fun `START-END rows without payload produce a document with null type and payloadHex, not a crash`() {
        Class.forName("org.duckdb.DuckDBDriver")
        val parquetPath = createTempFile("bronze-object-reader-test-nulls", ".parquet").toString()

        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    COPY (
                        SELECT * FROM (VALUES
                            ('ext-2', 'B4A2EB428B04', 'ImberaLink', NULL, '126', 1, CAST(NULL AS VARCHAR), CAST(NULL AS INT), CAST(NULL AS VARCHAR))
                        ) AS t(extraction_id, id_controller, technology, id_type, ble_version, document_sequence, type, chunk_index, data_chunk_hex)
                    ) TO '$parquetPath' (FORMAT PARQUET)
                    """.trimIndent(),
                )
            }

            val documents = BronzeObjectReader.readDocuments(conn, parquetPath)

            assertEquals(1, documents.size)
            val document = documents.single()
            assertNull(document.type)
            assertNull(document.payloadHex)
        }
    }
}
