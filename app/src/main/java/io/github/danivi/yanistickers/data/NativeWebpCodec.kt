package io.github.danivi.yanistickers.data

import android.graphics.Bitmap
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Puente JNI fino hacia `libwebp` (`WebPAnimEncoder` y `WebPAnimDecoder`) para codificar y
 * decodificar WebP animado frame a frame sin cargar toda la animación en memoria nativa.
 */
object NativeWebpCodec {

    init {
        System.loadLibrary("yanistickers_webp")
    }

    fun openEncoder(
        width: Int,
        height: Int,
        loopCount: Int = 0,
        minimizeSize: Boolean = false,
    ): EncoderSession = EncoderSession(width, height, loopCount, minimizeSize)

    fun openDecoder(webpBytes: ByteArray): DecoderSession = DecoderSession(webpBytes)

    class EncoderSession internal constructor(
        width: Int,
        height: Int,
        loopCount: Int,
        minimizeSize: Boolean,
    ) : Closeable {
        private var handle: Long = nativeCreateEncoder(width, height, loopCount, minimizeSize)
        private var closed = false

        init {
            check(handle != 0L) { "No se pudo inicializar el codificador WebP animado." }
        }

        fun addFrame(
            bitmap: Bitmap,
            durationMs: Int,
            quality: Float,
            lossless: Boolean = false,
            method: Int = 1,
        ) {
            check(!closed && handle != 0L) { "El codificador WebP ya fue cerrado." }
            val ok = nativeAddFrame(handle, bitmap, durationMs, quality, lossless, method)
            if (!ok) {
                val detail = nativeGetLastError(handle) ?: "Error al añadir frame WebP."
                error(detail)
            }
        }

        fun addRgbaBufferFrame(
            directRgbaBuffer: ByteBuffer,
            durationMs: Int,
            quality: Float,
            lossless: Boolean = false,
            method: Int = 1,
        ) {
            check(!closed && handle != 0L) { "El codificador WebP ya fue cerrado." }
            val ok = nativeAddRgbaBufferFrame(
                handle = handle,
                directRgbaBuffer = directRgbaBuffer,
                durationMs = durationMs,
                quality = quality,
                lossless = lossless,
                method = method,
            )
            if (!ok) {
                val detail = nativeGetLastError(handle) ?: "Error al añadir buffer RGBA WebP."
                error(detail)
            }
        }

        fun finish(): ByteArray {
            check(!closed && handle != 0L) { "El codificador WebP ya fue cerrado." }
            return nativeFinishEncoder(handle)
                ?: error(nativeGetLastError(handle) ?: "Error al ensamblar el WebP animado.")
        }

        override fun close() {
            if (!closed) {
                closed = true
                if (handle != 0L) {
                    nativeDestroyEncoder(handle)
                    handle = 0L
                }
            }
        }
    }

    class DecoderSession internal constructor(
        webpBytes: ByteArray,
    ) : Closeable {
        private var handle: Long = nativeCreateDecoder(webpBytes)
        private var closed = false

        val width: Int
        val height: Int
        val frameCount: Int

        init {
            check(handle != 0L) { "No se pudo abrir el WebP animado con libwebp." }
            val info = nativeGetDecoderInfo(handle)
            check(info != null && info.size == 3) { "Metadatos inválidos en WebP animado." }
            width = info[0]
            height = info[1]
            frameCount = info[2]
        }

        /**
         * Decodifica el siguiente frame en [targetBitmap] (`ARGB_8888`, dimensiones [width]×[height])
         * y devuelve su duración en ms, o `null` si no quedan más frames.
         */
        fun decodeNextFrame(targetBitmap: Bitmap): Int? {
            check(!closed && handle != 0L) { "El decodificador WebP ya fue cerrado." }
            val duration = nativeDecodeNextFrame(handle, targetBitmap)
            return when {
                duration == -1 -> null
                duration <= -2 -> error("Error al decodificar un frame del WebP animado.")
                else -> duration
            }
        }

        override fun close() {
            if (!closed) {
                closed = true
                if (handle != 0L) {
                    nativeDestroyDecoder(handle)
                    handle = 0L
                }
            }
        }
    }

    private external fun nativeCreateEncoder(
        width: Int,
        height: Int,
        loopCount: Int,
        minimizeSize: Boolean,
    ): Long

    private external fun nativeAddFrame(
        handle: Long,
        bitmap: Bitmap,
        durationMs: Int,
        quality: Float,
        lossless: Boolean,
        method: Int,
    ): Boolean

    private external fun nativeAddRgbaBufferFrame(
        handle: Long,
        directRgbaBuffer: ByteBuffer,
        durationMs: Int,
        quality: Float,
        lossless: Boolean,
        method: Int,
    ): Boolean

    private external fun nativeFinishEncoder(handle: Long): ByteArray?

    private external fun nativeGetLastError(handle: Long): String?

    private external fun nativeDestroyEncoder(handle: Long)

    private external fun nativeCreateDecoder(webpBytes: ByteArray): Long

    private external fun nativeGetDecoderInfo(handle: Long): IntArray?

    private external fun nativeDecodeNextFrame(handle: Long, targetBitmap: Bitmap): Int

    private external fun nativeDestroyDecoder(handle: Long)
}
