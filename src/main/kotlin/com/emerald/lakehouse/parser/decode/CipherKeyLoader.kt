package com.emerald.lakehouse.parser.decode

import com.emerald.coolector.parser.utils.ImberaLinkCipher
import java.io.File
import org.slf4j.LoggerFactory

/**
 * Carga la llave de descifrado de `ImberaLinkCipher` desde un archivo montado de solo
 * lectura (`AppConfig.parserCipherKeyPath`), fuera de cualquier repo — mismo patrón que la
 * llave privada de la Intermediate CA de mTLS en `lakehouse-ingestion-api`
 * (`IntermediateCertificateAuthority.kt`).
 *
 * Best-effort y perezoso: si el archivo no existe (ej. entorno de test, o el material
 * todavía no se colocó en el servidor), el servicio sigue funcionando decodificando solo
 * firmwares sin cifrar (v126) — no debe tumbar el arranque.
 */
object CipherKeyLoader {
    private val logger = LoggerFactory.getLogger("CipherKeyLoader")

    fun load(path: String): ImberaLinkCipher? {
        val file = File(path)
        if (!file.exists()) {
            logger.warn(
                "[CipherKeyLoader] {} no existe — solo se decodificarán firmwares sin cifrar (v126)",
                path,
            )
            return null
        }
        return ImberaLinkCipher(file.readBytes().toUByteArray())
    }
}
