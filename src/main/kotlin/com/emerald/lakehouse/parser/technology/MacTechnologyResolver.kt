package com.emerald.lakehouse.parser.technology

/**
 * Deriva la tecnología real del dispositivo a partir del prefijo MAC (OUI), puerto de la
 * tabla de `fn.py` (`solkos-in-process-analytics`) — ver
 * docs/ai/in-process-analytics-migration-plan.md §3.7 en Coolector-SDK.
 *
 * Necesario porque el campo `technology` que hoy manda el SDK (`AppConfig.kt`/
 * `ParquetIngestionClientAndroid`, default de formulario `"Softel"`) es un placeholder de
 * laboratorio, no la tecnología real. Sin este mapeo, dispositivos ELTEC/Villa nunca
 * despachan a su decoder correcto: `FirmwareProfileResolver` (en `Coolector_Parser`) solo
 * reconoce los strings exactos `"ELTEC"`/`"Villa"` — cualquier otro valor, incluido
 * `"Softel"`, cae silenciosamente al branch de dispatch de ImberaLink.
 *
 * El prefijo MAC tiene prioridad sobre el valor reportado cuando hay match conocido — es la
 * fuente de verdad más confiable disponible hoy en el lab. Si no hay match Y el valor
 * reportado ya es una de las 3 tecnologías que `FirmwareProfileResolver` reconoce, se
 * respeta tal cual (por si el SDK llega a reportarlo bien en el futuro, o para MACs reales
 * fuera de esta tabla de laboratorio). En cualquier otro caso (placeholder desconocido como
 * `"Softel"`, o valor vacío/null) cae al mismo default histórico `"ImberaLink"` que ya usaban
 * `ParserPipeline`/`TelemetryDecoder` antes de este cambio.
 */
object MacTechnologyResolver {

    const val DEFAULT_TECHNOLOGY: String = "ImberaLink"

    private val KNOWN_TECHNOLOGIES = setOf("ImberaLink", "ELTEC", "Villa")

    // fn.py: device_id[:6] in [...] -> 'ImberaLink' / 'ELTEC'. Villa no tiene tabla MAC
    // conocida en el origen -- no se inventa una.
    private val MAC_PREFIX_TO_TECHNOLOGY: Map<String, String> = mapOf(
        "001BC5" to "ImberaLink",
        "B4A2EB" to "ImberaLink",
        "3CA551" to "ELTEC",
        "00E44C" to "ELTEC",
        "F412FA" to "ELTEC",
        "7CDFA1" to "ELTEC",
        "4827E2" to "ELTEC",
    )

    fun resolve(deviceId: String?, reportedTechnology: String?): String {
        val prefix = deviceId?.replace(":", "")?.replace("-", "")?.take(6)?.uppercase()
        val byMac = prefix?.let { MAC_PREFIX_TO_TECHNOLOGY[it] }
        if (byMac != null) return byMac

        return reportedTechnology?.takeIf { it in KNOWN_TECHNOLOGIES } ?: DEFAULT_TECHNOLOGY
    }
}
