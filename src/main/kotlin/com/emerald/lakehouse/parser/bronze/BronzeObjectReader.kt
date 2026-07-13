package com.emerald.lakehouse.parser.bronze

import java.sql.Connection

/**
 * Un documento reconstruido de un objeto Bronze específico — mismo concepto que
 * `BronzeDocumentReader` en lakehouse-silver-service (Fase 2), pero **lee directo el
 * archivo Parquet indicado por la notificación de MinIO** (`read_parquet('s3://bronze/<key>')`)
 * en vez de la tabla DuckLake `bronze.telemetry_readings`.
 *
 * Esto es lo que elimina la race condition documentada en la Fase 2: la tabla DuckLake
 * solo existe después de que `DuckLakeSync` (async, en lakehouse-ingestion-api) termine de
 * sincronizar, pero el objeto Parquet en sí ya es legible en el momento en que MinIO
 * dispara la notificación (`s3:ObjectCreated:*` solo se emite tras un PUT durable) — así
 * que leer el archivo directo no depende de ningún otro proceso asíncrono.
 */
data class TelemetryDocument(
    val extractionId: String,
    val idController: String,
    val technology: String?,
    val idType: String?,
    val bleVersion: String?,
    val documentSequence: Int,
    // START/END puede traer payload, pero cuando no trae datos genera una fila lógica con
    // chunk_index/data_chunk_hex NULL (ver docs/ai/lakehouse-context.md) — ambos campos
    // deben poder ser null, no hay nada que decodificar en ese caso.
    val type: String?,
    val payloadHex: String?,
)

object BronzeObjectReader {

    /** @param path path completo del objeto — `s3://<bucket>/<key>` en producción, un archivo local en tests. */
    fun readDocuments(conn: Connection, path: String): List<TelemetryDocument> {
        val documents = mutableListOf<TelemetryDocument>()
        conn.prepareStatement(
            """
            SELECT
                extraction_id,
                id_controller,
                technology,
                id_type,
                ble_version,
                document_sequence,
                type,
                string_agg(data_chunk_hex, '' ORDER BY chunk_index) AS payload_hex
            FROM read_parquet(?)
            GROUP BY extraction_id, id_controller, technology, id_type, ble_version, document_sequence, type
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, path)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    documents += TelemetryDocument(
                        extractionId = rs.getString("extraction_id"),
                        idController = rs.getString("id_controller"),
                        technology = rs.getString("technology"),
                        idType = rs.getString("id_type"),
                        bleVersion = rs.getString("ble_version"),
                        documentSequence = rs.getInt("document_sequence"),
                        type = rs.getString("type"),
                        payloadHex = rs.getString("payload_hex"),
                    )
                }
            }
        }
        return documents
    }
}
