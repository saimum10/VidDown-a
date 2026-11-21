package com.viddown.app

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object Sha256 {
    fun hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun hex(file: File): String = file.inputStream().use { hex(it) }

    fun hex(bytes: ByteArray): String = hex(bytes.inputStream())
}

object Unzip {
    /** Extracts a zip stream into [dest]. Entries that would leave [dest] are rejected. */
    fun extract(input: InputStream, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalFile
        ZipInputStream(input.buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(dest, entry.name).canonicalFile
                if (out != root && !out.path.startsWith(root.path + File.separator)) {
                    throw IOException("Unsafe zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }
}

object Elf {
    const val EM_386 = 3
    const val EM_ARM = 40
    const val EM_X86_64 = 62
    const val EM_AARCH64 = 183

    fun machineFor(abi: String): Int = when (abi) {
        "arm64-v8a" -> EM_AARCH64
        "armeabi-v7a" -> EM_ARM
        "x86" -> EM_386
        "x86_64" -> EM_X86_64
        else -> throw IllegalArgumentException("Unknown ABI: $abi")
    }

    /** Reads e_machine from the ELF header of [file]. */
    fun machine(file: File): Int {
        val header = ByteArray(20)
        file.inputStream().use { input ->
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) throw IOException("${file.name}: too short for an ELF header")
                read += n
            }
        }
        if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) {
            throw IOException("${file.name}: not an ELF file")
        }
        return (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
    }
}
