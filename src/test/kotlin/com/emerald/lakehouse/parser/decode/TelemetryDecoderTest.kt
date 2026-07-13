package com.emerald.lakehouse.parser.decode

import com.emerald.lakehouse.parser.bronze.TelemetryDocument
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke test de la orquestación decode -> TimeRecord contra el decoder v126 (sin cifrado,
 * no requiere ninguna llave con licencia). Mismo payload usado en la Fase 2
 * (lakehouse-silver-service) y en `ImberaLinkV126DecoderTest` de Coolector_Parser:
 * byte4=0x08 (bit36/door=1), byte5=0xA8 (bits compressor/fan/buzzer=1).
 */
class TelemetryDecoderTest {

    @Test
    fun `decodes an unencrypted v126 document into flat time records`() {
        val document = TelemetryDocument(
            extractionId = "extraction-test-001",
            idController = "B4A2EB428B04",
            technology = "ImberaLink",
            idType = null,
            bleVersion = "126",
            documentSequence = 1,
            type = "EVENTS",
            payloadHex = "000f424008a8",
        )

        val records = TelemetryDecoder.decode(document, cipher = null)

        assertTrue(records.isNotEmpty(), "expected flattened state records, got none")
        assertTrue(records.any { it.value == 1.0 }, "expected at least one ON state bit")
        assertTrue(records.all { it.timestampMillis > 0 }, "every record must carry the sample's timestamp")
    }

    @Test
    fun `unknown firmware version yields no records instead of throwing`() {
        val document = TelemetryDocument(
            extractionId = "extraction-test-002",
            idController = "B4A2EB428B04",
            technology = "ImberaLink",
            idType = null,
            bleVersion = "not-a-number",
            documentSequence = 1,
            type = "EVENTS",
            payloadHex = "000f424008a8",
        )

        assertTrue(TelemetryDecoder.decode(document, cipher = null).isEmpty())
    }

    @Test
    fun `document without type or payloadHex (START-END with no data) yields no records`() {
        val document = TelemetryDocument(
            extractionId = "extraction-test-003",
            idController = "B4A2EB428B04",
            technology = "ImberaLink",
            idType = null,
            bleVersion = "126",
            documentSequence = 1,
            type = null,
            payloadHex = null,
        )

        assertTrue(TelemetryDecoder.decode(document, cipher = null).isEmpty())
    }
}
