package com.emerald.lakehouse.parser.decode

import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CipherKeyLoaderTest {

    @Test
    fun `returns null when the seed file is missing`() {
        val blobPath = Files.createTempFile("imbera-blob", ".bin").apply { writeBytes(ByteArray(16) { 1 }) }
        val missingSeedPath = "/tmp/does-not-exist-${System.nanoTime()}"

        assertNull(CipherKeyLoader.load(missingSeedPath, blobPath.toString()))
    }

    @Test
    fun `returns null when the blob file is missing`() {
        val seedPath = Files.createTempFile("parsing-seed", ".bin").apply { writeBytes(ByteArray(16) { 1 }) }
        val missingBlobPath = "/tmp/does-not-exist-${System.nanoTime()}"

        assertNull(CipherKeyLoader.load(seedPath.toString(), missingBlobPath))
    }

    @Test
    fun `returns null when either file is empty (Docker bind-mount placeholder)`() {
        val emptySeed = Files.createTempFile("parsing-seed-empty", ".bin")
        val blobPath = Files.createTempFile("imbera-blob", ".bin").apply { writeBytes(ByteArray(16) { 1 }) }

        assertNull(CipherKeyLoader.load(emptySeed.toString(), blobPath.toString()))
    }

    @Test
    fun `derives a cipher when both files are present and non-empty`() {
        val seedPath = Files.createTempFile("parsing-seed", ".bin").apply { writeBytes(ByteArray(16) { (it + 1).toByte() }) }
        val blobPath = Files.createTempFile("imbera-blob", ".bin").apply { writeBytes(ByteArray(16) { (it + 100).toByte() }) }

        assertNotNull(CipherKeyLoader.load(seedPath.toString(), blobPath.toString()))
    }
}
