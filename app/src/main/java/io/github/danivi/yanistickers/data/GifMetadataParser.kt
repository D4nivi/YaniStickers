package io.github.danivi.yanistickers.data

import io.github.danivi.yanistickers.validation.PackValidator
import java.io.File

/**
 * Metadatos básicos extraídos de la cabecera y bloques de un archivo GIF (`GIF87a`/`GIF89a`).
 */
data class GifMetadata(
    val width: Int,
    val height: Int,
    val frameDurationsMs: List<Int>,
) {
    val frameCount: Int
        get() = frameDurationsMs.size

    val totalDurationMs: Long
        get() = frameDurationsMs.fold(0L) { acc, d -> acc + d.toLong() }
}

/**
 * Parser binario ligero en Kotlin puro para extraer dimensiones y duraciones por frame de archivos GIF.
 */
object GifMetadataParser {

    private const val DEFAULT_DELAY_MS = 100

    fun parse(file: File): GifMetadata? {
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return parseBytes(bytes)
    }

    fun parseBytes(bytes: ByteArray): GifMetadata? {
        if (bytes.size < 14) return null
        val header = String(bytes, 0, 6, Charsets.US_ASCII)
        if (header != "GIF87a" && header != "GIF89a") return null

        val width = readUInt16LE(bytes, 6)
        val height = readUInt16LE(bytes, 8)
        if (width <= 0 || height <= 0) return null

        var pos = 13
        val packed = bytes[10].toInt() and 0xFF
        if ((packed and 0x80) != 0) {
            val gctEntries = 1 shl ((packed and 0x07) + 1)
            pos += gctEntries * 3
        }
        if (pos > bytes.size) return null

        val durations = mutableListOf<Int>()
        var pendingDelayMs: Int? = null

        while (pos < bytes.size) {
            when (val blockId = bytes[pos].toInt() and 0xFF) {
                0x3B -> break // Trailer
                0x21 -> { // Extension
                    if (pos + 1 >= bytes.size) break
                    val label = bytes[pos + 1].toInt() and 0xFF
                    pos += 2
                    if (label == 0xF9 && pos + 5 < bytes.size) {
                        val blockSize = bytes[pos].toInt() and 0xFF
                        if (blockSize == 4 && pos + 5 < bytes.size) {
                            val delayCs = readUInt16LE(bytes, pos + 2)
                            pendingDelayMs = if (delayCs <= 1) {
                                DEFAULT_DELAY_MS
                            } else {
                                (delayCs * 10).coerceAtLeast(PackValidator.MIN_ANIMATED_FRAME_DURATION_MS)
                            }
                        }
                        pos = skipSubBlocks(bytes, pos)
                    } else {
                        pos = skipSubBlocks(bytes, pos)
                    }
                }
                0x2C -> { // Image Descriptor
                    if (pos + 10 >= bytes.size) break
                    val imgPacked = bytes[pos + 9].toInt() and 0xFF
                    pos += 10
                    if ((imgPacked and 0x80) != 0) {
                        val lctEntries = 1 shl ((imgPacked and 0x07) + 1)
                        pos += lctEntries * 3
                    }
                    if (pos >= bytes.size) break
                    pos += 1 // LZW minimum code size
                    pos = skipSubBlocks(bytes, pos)
                    durations += (pendingDelayMs ?: DEFAULT_DELAY_MS)
                    pendingDelayMs = null
                }
                else -> {
                    // Bloque desconocido: avanzar de forma segura para evitar bucle infinito
                    if (blockId == 0x00) {
                        pos += 1
                    } else {
                        break
                    }
                }
            }
        }

        if (durations.isEmpty()) return null
        return GifMetadata(
            width = width,
            height = height,
            frameDurationsMs = durations,
        )
    }

    private fun skipSubBlocks(bytes: ByteArray, startOffset: Int): Int {
        var pos = startOffset
        while (pos < bytes.size) {
            val blockSize = bytes[pos].toInt() and 0xFF
            pos += 1
            if (blockSize == 0) break
            pos += blockSize
            if (pos > bytes.size) return bytes.size
        }
        return pos
    }

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }
}
