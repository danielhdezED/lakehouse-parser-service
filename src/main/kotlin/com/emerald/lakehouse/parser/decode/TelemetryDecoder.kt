package com.emerald.lakehouse.parser.decode

import com.emerald.coolector.parser.DecodedSampleFlattener
import com.emerald.coolector.parser.FirmwareProfileResolver
import com.emerald.coolector.parser.TimeRecord
import com.emerald.coolector.parser.utils.ImberaLinkCipher
import com.emerald.lakehouse.parser.bronze.TelemetryDocument
import org.slf4j.LoggerFactory

/**
 * Decodifica un [TelemetryDocument] a filas planas `(name, timestamp, value)` usando
 * `Coolector_Parser` — la misma orquestación que vivía en `lakehouse-silver-service` en la
 * Fase 2, movida tal cual a este servicio dedicado (§ "Aclaración" del plan de Fase 3:
 * la librería no cambia, solo el límite de ejecución).
 *
 * **Este es el único servicio del ecosistema con acceso al [cipher].** Firmwares v136/v621
 * vienen cifrados con una llave que es material con licencia (ver
 * `docs/ai/security-guidelines.md` en Coolector-SDK) — se aprovisiona vía
 * `AppConfig.parserCipherKeyPath`, nunca se genera ni se embebe en código. Sin `cipher`,
 * cualquier documento que necesite descifrado simplemente no se decodifica (mismo
 * comportamiento que `FirmwareProfileResolver` cuando `cipher == null` para esos firmwares).
 */
object TelemetryDecoder {
    private val logger = LoggerFactory.getLogger("TelemetryDecoder")

    fun decode(document: TelemetryDocument, cipher: ImberaLinkCipher?): List<TimeRecord> {
        // START/END sin payload real (ver TelemetryDocument.type) — nada que decodificar.
        val type = document.type ?: return emptyList()
        val payloadHex = document.payloadHex ?: return emptyList()

        val decoder = FirmwareProfileResolver.resolveDecoder(
            firmwareVersion = document.bleVersion ?: "",
            cipher = cipher,
            technology = document.technology ?: "ImberaLink",
            idType = document.idType,
        ) ?: return emptyList()

        return try {
            val sample = decoder.decodeSample(payloadHex, type, document.idController)
            DecodedSampleFlattener.flatten(sample)
        } catch (e: Exception) {
            logger.warn(
                "[TelemetryDecoder] failed to decode extraction={} device={} seq={}: {}",
                document.extractionId,
                document.idController,
                document.documentSequence,
                e.message,
            )
            emptyList()
        }
    }

    fun decode(documents: List<TelemetryDocument>, cipher: ImberaLinkCipher?): List<TimeRecord> =
        documents.flatMap { decode(it, cipher) }
}
