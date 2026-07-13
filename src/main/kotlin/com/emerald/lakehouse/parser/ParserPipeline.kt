package com.emerald.lakehouse.parser

import com.emerald.coolector.parser.utils.ImberaLinkCipher
import com.emerald.lakehouse.parser.bronze.BronzeObjectReader
import com.emerald.lakehouse.parser.decode.TelemetryDecoder
import com.emerald.lakehouse.parser.silver.SilverWriter
import java.sql.Connection

/**
 * Orquesta Bronze -> decode -> Silver para un único objeto (el que indica la notificación
 * de MinIO). Un archivo Bronze siempre pertenece a un solo dispositivo/extracción (el
 * Ingestion API valida `extraction_id`/`device` consistentes dentro del mismo multipart,
 * ver `IngestRoute.kt` en lakehouse-ingestion-api) — no hace falta agrupar por dispositivo
 * como sí hacía `AnalyticsPipeline` en la Fase 2, que procesaba toda la tabla Bronze junta.
 */
object ParserPipeline {

    /**
     * @param bronzeObjectPath path completo al objeto Bronze (`s3://<bucket>/<key>` en
     * producción, un archivo local en tests).
     * @param silverRoot raíz de escritura de Silver (`s3://<bucket>` en producción, un
     * directorio local en tests).
     * @return el prefijo donde quedó escrito el Silver Parquet, o null si no había nada que decodificar.
     */
    fun run(
        conn: Connection,
        bronzeObjectPath: String,
        silverRoot: String,
        cipher: ImberaLinkCipher?,
    ): String? {
        val documents = BronzeObjectReader.readDocuments(conn, bronzeObjectPath)
        if (documents.isEmpty()) return null

        val records = TelemetryDecoder.decode(documents, cipher)
        if (records.isEmpty()) return null

        val first = documents.first()
        return SilverWriter.write(
            conn = conn,
            records = records,
            technology = first.technology?.takeIf { it.isNotBlank() } ?: "ImberaLink",
            deviceId = first.idController,
            extractionId = first.extractionId,
            silverRoot = silverRoot,
        )
    }
}
