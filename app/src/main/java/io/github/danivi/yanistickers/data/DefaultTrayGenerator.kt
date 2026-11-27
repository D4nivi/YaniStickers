package io.github.danivi.yanistickers.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Genera un icono de bandeja estático `96×96` por defecto para packs recién creados.
 */
object DefaultTrayGenerator {

    private const val TRAY_SIZE = 96

    /**
     * Genera en Android un `tray.webp` (`96×96`) limpio con la inicial del pack.
     */
    fun writeAndroidWebpTray(destFile: File, packName: String, animated: Boolean) {
        val bitmap = Bitmap.createBitmap(TRAY_SIZE, TRAY_SIZE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (animated) Color.rgb(103, 80, 164) else Color.rgb(0, 106, 106)
            }
            canvas.drawRoundRect(
                RectF(4f, 4f, (TRAY_SIZE - 4).toFloat(), (TRAY_SIZE - 4).toFloat()),
                24f,
                24f,
                bgPaint,
            )

            val initial = packName.trim()
                .firstOrNull { !it.isWhitespace() }
                ?.uppercaseChar()
                ?.toString()
                ?: "S"

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 44f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val textBoundsOffset = -(textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(
                initial,
                TRAY_SIZE / 2f,
                TRAY_SIZE / 2f + textBoundsOffset,
                textPaint,
            )

            destFile.parentFile?.mkdirs()
            destFile.outputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)) {
                    "No se pudo codificar el icono de bandeja por defecto."
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Alternativa en Kotlin puro (PNG `96×96`) para pruebas JVM sin dependencias de Android.
     */
    fun writeFallbackPngTray(destFile: File, animated: Boolean) {
        val r = if (animated) 103 else 0
        val g = if (animated) 80 else 106
        val b = if (animated) 164 else 106

        val rowStride = 1 + TRAY_SIZE * 4
        val raw = ByteArray(rowStride * TRAY_SIZE)
        for (y in 0 until TRAY_SIZE) {
            val rowStart = y * rowStride
            raw[rowStart] = 0 // Tipo de filtro PNG 0 (None)
            for (x in 0 until TRAY_SIZE) {
                val px = rowStart + 1 + x * 4
                raw[px] = r.toByte()
                raw[px + 1] = g.toByte()
                raw[px + 2] = b.toByte()
                raw[px + 3] = 0xFF.toByte()
            }
        }

        val compressed = compressZlib(raw)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        val ihdr = ByteBuffer.allocate(13)
            .putInt(TRAY_SIZE)
            .putInt(TRAY_SIZE)
            .put(8.toByte()) // bit depth
            .put(6.toByte()) // RGBA
            .put(0.toByte())
            .put(0.toByte())
            .put(0.toByte())
            .array()
        writeChunk(out, "IHDR", ihdr)
        writeChunk(out, "IDAT", compressed)
        writeChunk(out, "IEND", ByteArray(0))

        destFile.parentFile?.mkdirs()
        destFile.writeBytes(out.toByteArray())
    }

    private fun compressZlib(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            if (count <= 0) break
            output.write(buffer, 0, count)
        }
        deflater.end()
        return output.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, payload: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(intToBigEndian(payload.size))
        out.write(typeBytes)
        out.write(payload)
        val crc = CRC32().apply {
            update(typeBytes)
            update(payload)
        }.value.toInt()
        out.write(intToBigEndian(crc))
    }

    private fun intToBigEndian(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    fun trayFileNameForFallback(): String = String.format(Locale.ROOT, "tray.png")
}
