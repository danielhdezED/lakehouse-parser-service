package com.emerald.lakehouse.parser.decode

/**
 * Puerto local del algoritmo de desofuscación de `LicenseGate.deobfuscate`
 * (Coolector-SDK, `coolector-core/.../internal/security/LicenseGate.kt`) — duplicado
 * deliberadamente en vez de agregar una dependencia nueva a Coolector-SDK, para que este
 * cambio no toque el SDK en absoluto (decisión explícita del usuario, ver
 * docs/ai/in-process-analytics-migration-plan.md §7.-1 en Coolector-SDK).
 *
 * Es solo el algoritmo (XOR/keystream determinista, matemática pública) — no incluye ningún
 * valor embebido. El blob real (`IMBERA_KEY_BLOB`) y la semilla (`parsingKey`) se aprovisionan
 * por separado como archivos fuera de este repo, nunca en código — ninguno de los dos por sí
 * solo permite reconstruir la llave real, mismo principio de diseño que el SDK.
 */
internal object KeystreamDeobfuscator {

    fun deobfuscate(blob: ByteArray, seed: ByteArray): ByteArray {
        val ks = keyStream(seed, blob.size)
        val out = ByteArray(blob.size)
        for (i in blob.indices) {
            out[i] = (blob[i].toInt() xor (ks[i].toInt() and 0xFF)).toByte()
        }
        return out
    }

    private fun keyStream(seed: ByteArray, len: Int): ByteArray {
        var s = 0xC0DEC0DE.toInt()
        for (b in seed) {
            val bi = b.toInt() and 0xFF
            s = (s + bi + (s shl 5) + (s ushr 2))
            s = s xor (s ushr 13)
        }

        val out = ByteArray(len)
        for (i in 0 until len) {
            val mixed = s xor (s ushr 16)
            s = mixed * 0x045D9F3B
            s = s + (seed[i % seed.size].toInt() and 0xFF) + i
            out[i] = (s xor (s ushr 8) xor (s ushr 16) xor (s ushr 24)).toByte()
        }
        return out
    }
}
