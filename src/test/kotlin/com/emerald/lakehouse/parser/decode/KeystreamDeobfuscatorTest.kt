package com.emerald.lakehouse.parser.decode

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

/**
 * No hay fixtures con el material real (`IMBERA_KEY_BLOB`) en este repo a propósito -- estos
 * tests validan solo las propiedades matemáticas del algoritmo XOR/keystream (autoinverso,
 * determinista, sensible a la semilla), no un valor de salida esperado real.
 */
class KeystreamDeobfuscatorTest {

    private val seed = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)

    @Test
    fun `is self-inverse -- applying it twice with the same seed recovers the original bytes`() {
        val original = byteArrayOf(0x41, 0xBF.toByte(), 0x3F, 0xBE.toByte(), 0x13, 0xCB.toByte(), 0x1D, 0x46)

        val obfuscated = KeystreamDeobfuscator.deobfuscate(original, seed)
        val recovered = KeystreamDeobfuscator.deobfuscate(obfuscated, seed)

        assertContentEquals(original, recovered)
    }

    @Test
    fun `is deterministic -- same blob and seed always produce the same output`() {
        val blob = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130.toByte(), 140.toByte(), 150.toByte(), 160.toByte())

        val a = KeystreamDeobfuscator.deobfuscate(blob, seed)
        val b = KeystreamDeobfuscator.deobfuscate(blob, seed)

        assertContentEquals(a, b)
    }

    @Test
    fun `a different seed produces a different result`() {
        val blob = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130.toByte(), 140.toByte(), 150.toByte(), 160.toByte())
        val otherSeed = byteArrayOf(16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1)

        val withSeed = KeystreamDeobfuscator.deobfuscate(blob, seed)
        val withOtherSeed = KeystreamDeobfuscator.deobfuscate(blob, otherSeed)

        assertFalse(withSeed.contentEquals(withOtherSeed))
    }
}
