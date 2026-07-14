package com.emerald.lakehouse.parser.decode

import com.emerald.coolector.parser.utils.ImberaLinkCipher
import java.io.File
import org.slf4j.LoggerFactory

/**
 * Deriva la llave de descifrado de `ImberaLinkCipher` a partir de DOS archivos montados de
 * solo lectura, fuera de cualquier repo (`AppConfig.parserCipherKeyPath` = semilla,
 * `AppConfig.parserImberaBlobPath` = blob ofuscado) — mismo patrón que la llave privada de la
 * Intermediate CA de mTLS en `lakehouse-ingestion-api`.
 *
 * **Corrección real (2026-07-14):** antes, `parserCipherKeyPath` se interpretaba como la llave
 * final, cuando en realidad solo contiene la semilla `parsingKey` — el mismo valor que el SDK
 * usa como semilla XOR para desofuscar `IMBERA_KEY_BLOB` en runtime (`CoreSecrets.initParsing`
 * en Coolector-SDK). Ninguno de los dos archivos por sí solo permite reconstruir la llave real
 * (mismo principio de diseño que el SDK) — se combinan aquí vía [KeystreamDeobfuscator], sin
 * tocar Coolector-SDK (ver docs/ai/in-process-analytics-migration-plan.md §7.-1 en ese repo).
 *
 * Best-effort y perezoso: si cualquiera de los dos archivos no existe (ej. entorno de test, o
 * el material todavía no se colocó en el servidor), el servicio sigue funcionando decodificando
 * solo firmwares sin cifrar (v126) — no debe tumbar el arranque.
 */
object CipherKeyLoader {
    private val logger = LoggerFactory.getLogger("CipherKeyLoader")

    fun load(seedPath: String, blobPath: String): ImberaLinkCipher? {
        val seed = readNonEmptyFile(seedPath, "semilla parsingKey") ?: return null
        val blob = readNonEmptyFile(blobPath, "blob IMBERA_KEY_BLOB") ?: return null

        return try {
            val imberaKey = KeystreamDeobfuscator.deobfuscate(blob, seed)
            ImberaLinkCipher(imberaKey.toUByteArray())
        } catch (e: Exception) {
            logger.error("[CipherKeyLoader] no se pudo derivar la llave: {}", e.message, e)
            null
        }
    }

    private fun readNonEmptyFile(path: String, label: String): ByteArray? {
        val file = File(path)
        // Un bind mount de Docker crea un directorio vacío en el host si el archivo fuente
        // todavía no existe (en vez de fallar) — hay que tratar eso, y un archivo vacío
        // (placeholder), igual que "no existe".
        if (!file.isFile || file.length() == 0L) {
            logger.warn(
                "[CipherKeyLoader] {} ({}) no existe o está vacío — solo se decodificarán firmwares sin cifrar (v126)",
                label,
                path,
            )
            return null
        }
        return file.readBytes()
    }
}
