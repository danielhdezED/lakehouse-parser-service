package com.emerald.lakehouse.parser

import com.emerald.coolector.parser.utils.ImberaLinkCipher
import com.emerald.lakehouse.parser.bronze.BronzeObjectReader
import com.emerald.lakehouse.parser.decode.TelemetryDecoder
import com.emerald.lakehouse.parser.silver.SilverWriter
import com.emerald.lakehouse.parser.technology.MacTechnologyResolver
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

        // Corrige `technology` (MAC-derivada, ver MacTechnologyResolver) antes de decodificar
        // -- no solo para el path de partición de Silver, también porque
        // FirmwareProfileResolver despacha ELTEC/Villa por ese mismo valor exacto.
        val resolvedDocuments = documents.map { it.copy(technology = MacTechnologyResolver.resolve(it.idController, it.technology)) }

        val records = TelemetryDecoder.decode(resolvedDocuments, cipher)
        if (records.isEmpty()) return null

        val first = resolvedDocuments.first()
        return SilverWriter.write(
            conn = conn,
            records = records,
            technology = first.technology ?: MacTechnologyResolver.DEFAULT_TECHNOLOGY,
            deviceId = first.idController,
            extractionId = first.extractionId,
            silverRoot = silverRoot,
        )
    }
}
