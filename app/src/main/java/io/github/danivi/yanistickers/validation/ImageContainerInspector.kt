package io.github.danivi.yanistickers.validation

import java.io.File

/**
 * Formato de contenedor de imagen soportado en los packs de WhatsApp.
 */
enum class ImageContainerFormat {
    WEBP,
    PNG,
}

/**
 * Metadatos técnicos extraídos de la cabecera binaria de una imagen WebP o PNG.
 */
data class InspectedImageInfo(
    val format: ImageContainerFormat,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long,
    val isAnimated: Boolean,
    val frameCount: Int,
    val frameDurationsMs: List<Int>,
    val totalDurationMs: Long,
) {
    val minFrameDurationMs: Int?
        get() = frameDurationsMs.minOrNull()
}

/**
 * Inspector binario en Kotlin puro para WebP (`RIFF`/`WEBP`) y PNG (`IHDR`).
 * Evita dependencias nativas pesadas y permite ejecutar la validación tanto en Android como en tests JVM.
 */
object ImageContainerInspector {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

    fun inspect(file: File): InspectedImageInfo? {
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return inspectBytes(bytes, file.length())
    }

    fun inspectBytes(bytes: ByteArray, reportedSizeBytes: Long = bytes.size.toLong()): InspectedImageInfo? {
        if (bytes.size >= 12 && matchesAscii(bytes, 0, "RIFF") && matchesAscii(bytes, 8, "WEBP")) {
            return inspectWebP(bytes, reportedSizeBytes)
        }
        if (bytes.size >= 29 && matchesPngSignature(bytes)) {
            return inspectPng(bytes, reportedSizeBytes)
        }
        return null
    }

    private fun inspectPng(bytes: ByteArray, fileSizeBytes: Long): InspectedImageInfo? {
        val ihdrLength = readUInt32BE(bytes, 8)
        if (ihdrLength != 13L || !matchesAscii(bytes, 12, "IHDR")) return null
        val width = readUInt32BE(bytes, 16)
        val height = readUInt32BE(bytes, 20)
        if (width <= 0L || height <= 0L || width > Int.MAX_VALUE || height > Int.MAX_VALUE) {
            return null
        }
        return InspectedImageInfo(
            format = ImageContainerFormat.PNG,
            width = width.toInt(),
            height = height.toInt(),
            fileSizeBytes = fileSizeBytes,
            isAnimated = false,
            frameCount = 1,
            frameDurationsMs = emptyList(),
            totalDurationMs = 0L,
        )
    }

    private fun inspectWebP(bytes: ByteArray, fileSizeBytes: Long): InspectedImageInfo? {
        val riffSize = readUInt32LE(bytes, 4)
        if (riffSize < 4L || 8L + riffSize > bytes.size.toLong()) return null

        var width = -1
        var height = -1
        var hasAnimFlag = false
        var hasImageChunk = false
        val frameDurations = mutableListOf<Int>()

        var offset = 12L
        val limit = 8L + riffSize
        while (offset + 8L <= limit) {
            val pos = offset.toInt()
            val chunkSize = readUInt32LE(bytes, pos + 4)
            val dataStart = offset + 8L
            val dataEnd = dataStart + chunkSize
            if (dataEnd > limit) return null

            val start = dataStart.toInt()
            when {
                matchesAscii(bytes, pos, "VP8X") -> {
                    if (chunkSize < 10L) return null
                    val flags = bytes[start].toInt() and 0xFF
                    hasAnimFlag = (flags and 0x02) != 0
                    width = readUInt24LE(bytes, start + 4) + 1
                    height = readUInt24LE(bytes, start + 7) + 1
                }
                matchesAscii(bytes, pos, "VP8 ") -> {
                    val dims = parseVp8Dimensions(bytes, start, chunkSize) ?: return null
                    if (width <= 0) width = dims.first
                    if (height <= 0) height = dims.second
                    hasImageChunk = true
                }
                matchesAscii(bytes, pos, "VP8L") -> {
                    val dims = parseVp8LDimensions(bytes, start, chunkSize) ?: return null
                    if (width <= 0) width = dims.first
                    if (height <= 0) height = dims.second
                    hasImageChunk = true
                }
                matchesAscii(bytes, pos, "ANMF") -> {
                    if (chunkSize < 16L) return null
                    frameDurations.add(readUInt24LE(bytes, start + 12))
                }
            }

            offset = dataEnd + (chunkSize and 1L)
        }

        if (width <= 0 || height <= 0) return null
        if (frameDurations.isEmpty() && !hasImageChunk) return null
        if (hasAnimFlag && frameDurations.isEmpty()) return null

        val frameCount = if (frameDurations.isNotEmpty()) frameDurations.size else 1
        val totalDuration = frameDurations.fold(0L) { acc, ms -> acc + ms.toLong() }

        return InspectedImageInfo(
            format = ImageContainerFormat.WEBP,
            width = width,
            height = height,
            fileSizeBytes = fileSizeBytes,
            isAnimated = frameCount > 1,
            frameCount = frameCount,
            frameDurationsMs = frameDurations,
            totalDurationMs = totalDuration,
        )
    }

    private fun parseVp8Dimensions(bytes: ByteArray, start: Int, chunkSize: Long): Pair<Int, Int>? {
        if (chunkSize < 10L) return null
        val isKeyFrame = (bytes[start].toInt() and 0x01) == 0
        if (!isKeyFrame) return null
        if (
            (bytes[start + 3].toInt() and 0xFF) != 0x9D ||
            (bytes[start + 4].toInt() and 0xFF) != 0x01 ||
            (bytes[start + 5].toInt() and 0xFF) != 0x2A
        ) {
            return null
        }
        val rawW = (bytes[start + 6].toInt() and 0xFF) or ((bytes[start + 7].toInt() and 0xFF) shl 8)
        val rawH = (bytes[start + 8].toInt() and 0xFF) or ((bytes[start + 9].toInt() and 0xFF) shl 8)
        val w = rawW and 0x3FFF
        val h = rawH and 0x3FFF
        if (w <= 0 || h <= 0) return null
        return w to h
    }

    private fun parseVp8LDimensions(bytes: ByteArray, start: Int, chunkSize: Long): Pair<Int, Int>? {
        if (chunkSize < 5L) return null
        if ((bytes[start].toInt() and 0xFF) != 0x2F) return null
        val packed = readUInt32LE(bytes, start + 1)
        val w = (packed and 0x3FFFL).toInt() + 1
        val h = ((packed ushr 14) and 0x3FFFL).toInt() + 1
        if (w <= 0 || h <= 0) return null
        return w to h
    }

    private fun matchesPngSignature(bytes: ByteArray): Boolean {
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) return false
        }
        return true
    }

    private fun matchesAscii(bytes: ByteArray, offset: Int, fourCc: String): Boolean {
        for (i in 0 until 4) {
            if (bytes[offset + i].toInt().toChar() != fourCc[i]) return false
        }
        return true
    }

    private fun readUInt24LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }

    private fun readUInt32BE(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
    }
}
