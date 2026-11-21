package com.viddown.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UtilTest {
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun tempDir(): File = File.createTempFile("viddown", "dir").apply { delete(); mkdirs() }

    @Test
    fun sha256OfKnownString() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".toByteArray()))
    }

    @Test
    fun sha256DetectsCorruption() {
        val good = "viddown".toByteArray()
        val bad = good.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNotEquals(Sha256.hex(good), Sha256.hex(bad))
    }

    @Test
    fun unzipExtractsFilesAndDirectories() {
        val dest = tempDir()
        Unzip.extract(zip("a/b.txt" to "hello").inputStream(), dest)
        assertEquals("hello", File(dest, "a/b.txt").readText())
    }

    @Test
    fun unzipRejectsPathTraversal() {
        val dest = tempDir()
        try {
            Unzip.extract(zip("../evil.txt" to "x").inputStream(), dest)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Unsafe"))
        }
        assertFalse(File(dest.parentFile, "evil.txt").exists())
    }

    @Test
    fun elfMachineIsReadFromHeader() {
        val file = File.createTempFile("fake", ".so")
        val header = ByteArray(64)
        header[0] = 0x7f
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[18] = Elf.EM_AARCH64.toByte() // 183 fits in the low byte
        header[19] = 0
        file.writeBytes(header)
        assertEquals(Elf.machineFor("arm64-v8a"), Elf.machine(file))
    }

    @Test
    fun elfRejectsNonElf() {
        val file = File.createTempFile("not", ".so").apply { writeBytes(ByteArray(40)) }
        try {
            Elf.machine(file)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("not an ELF"))
        }
    }
}
