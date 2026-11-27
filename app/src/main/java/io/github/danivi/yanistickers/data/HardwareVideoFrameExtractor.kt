package io.github.danivi.yanistickers.data

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.danivi.yanistickers.validation.PackValidator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Extrae frames de un tramo de vídeo en una única pasada secuencial por hardware
 * (`MediaExtractor` + `MediaCodec` + `SurfaceTexture`/GLES20), aplicando el encuadre `512×512` en GPU
 * y entregando buffers RGBA directos listos para `libwebp` sin crear `Bitmap` por frame.
 */
internal object HardwareVideoFrameExtractor {

    private const val CODEC_TIMEOUT_US = 10_000L
    private const val FRAME_WAIT_TIMEOUT_MS = 2_500L

    private const val VERTEX_SHADER = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uStMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vTexCoord = (uStMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """

    private const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """

    fun extractFrames(
        videoFile: File,
        samplePoints: List<VideoSamplePoint>,
        transform: StickerFramingTransform,
        onFrameRgba: (index: Int, durationMs: Int, directRgbaBuffer: ByteBuffer) -> Unit,
    ) {
        require(samplePoints.size >= 2) { "Se necesitan al menos 2 frames." }
        val sizePx = PackValidator.STICKER_DIMENSION_PX
        val startUs = samplePoints.first().timestampMs * 1000L

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var glSession: GlVideoSurfaceSession? = null

        try {
            extractor.setDataSource(videoFile.absolutePath)
            val trackIndex = findVideoTrack(extractor)
            require(trackIndex >= 0) { "No se encontró pista de vídeo." }
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: error("Formato de vídeo sin MIME.")
            val rawW = format.getInteger(MediaFormat.KEY_WIDTH)
            val rawH = format.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                format.getInteger(MediaFormat.KEY_ROTATION)
            } else {
                0
            }
            val orientedW = if (rotation % 180 != 0) rawH else rawW
            val orientedH = if (rotation % 180 != 0) rawW else rawH

            glSession = GlVideoSurfaceSession(
                outputSizePx = sizePx,
                srcWidth = orientedW,
                srcHeight = orientedH,
                transform = transform,
            )

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, glSession.surface, null, 0)
                start()
            }

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleIndex = 0
            val rgbaBuffer = ByteBuffer.allocateDirect(sizePx * sizePx * 4)
                .order(ByteOrder.nativeOrder())

            while (!outputDone && sampleIndex < samplePoints.size) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val ptsUs = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, ptsUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                if (outIndex >= 0) {
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val ptsUs = bufferInfo.presentationTimeUs
                    val targetUs = samplePoints[sampleIndex].timestampMs * 1000L
                    val shouldRender = bufferInfo.size > 0 && (ptsUs >= targetUs || isEos)

                    codec.releaseOutputBuffer(outIndex, shouldRender)
                    if (shouldRender) {
                        glSession.renderNextFrameToRgba(rgbaBuffer)
                        // Si el FPS del vídeo era más bajo que el muestreado, asignar la suma de
                        // los puntos cubiertos por este frame sin duplicar trabajo innecesario
                        var combinedDuration = 0
                        val currentSampleIndex = sampleIndex
                        while (sampleIndex < samplePoints.size) {
                            val nextTargetUs = samplePoints[sampleIndex].timestampMs * 1000L
                            if (sampleIndex == currentSampleIndex || ptsUs >= nextTargetUs) {
                                combinedDuration += samplePoints[sampleIndex].outputDurationMs
                                sampleIndex++
                            } else {
                                break
                            }
                        }
                        onFrameRgba(
                            currentSampleIndex,
                            combinedDuration.coerceAtLeast(PackValidator.MIN_ANIMATED_FRAME_DURATION_MS),
                            rgbaBuffer,
                        )
                    }
                    if (isEos) {
                        outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { glSession?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("video/")) return i
        }
        return -1
    }

    private class GlVideoSurfaceSession(
        private val outputSizePx: Int,
        srcWidth: Int,
        srcHeight: Int,
        transform: StickerFramingTransform,
    ) {
        private val eglDisplay: EGLDisplay
        private val eglContext: EGLContext
        private val eglSurface: EGLSurface
        private val oesTexId: Int
        private val program: Int
        private val positionBuffer: FloatBuffer
        private val texCoordBuffer: FloatBuffer
        private val stMatrix = FloatArray(16)
        private val frameThread = HandlerThread("YaniVideoGlDecode").apply { start() }
        private val frameSemaphore = Semaphore(0)
        private val surfaceTexture: SurfaceTexture
        val surface: Surface

        private val aPositionHandle: Int
        private val aTexCoordHandle: Int
        private val uStMatrixHandle: Int

        init {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "EGL display no disponible." }
            val version = IntArray(2)
            check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                "No se pudo inicializar EGL."
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
                "No se encontró configuración EGL RGBA8."
            }

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "No se pudo crear contexto EGL." }

            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, outputSizePx,
                EGL14.EGL_HEIGHT, outputSizePx,
                EGL14.EGL_NONE,
            )
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "No se pudo crear superficie Pbuffer EGL." }

            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "eglMakeCurrent falló."
            }

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            oesTexId = texIds[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            surfaceTexture = SurfaceTexture(oesTexId).apply {
                setDefaultBufferSize(srcWidth.coerceAtLeast(1), srcHeight.coerceAtLeast(1))
                setOnFrameAvailableListener(
                    { frameSemaphore.release() },
                    Handler(frameThread.looper),
                )
            }
            surface = Surface(surfaceTexture)

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            uStMatrixHandle = GLES20.glGetUniformLocation(program, "uStMatrix")

            // Transformar las 4 esquinas con la misma matriz que el lienzo Compose para que
            // glReadPixels produzca un buffer RGBA top-down idéntico al encuadre mostrado
            val matrix = StaticStickerEncoder.buildRenderMatrix(
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                canvasSizePx = outputSizePx.toFloat(),
                transform = transform,
            )
            val corners = floatArrayOf(
                0f, 0f,
                srcWidth.toFloat(), 0f,
                0f, srcHeight.toFloat(),
                srcWidth.toFloat(), srcHeight.toFloat(),
            )
            matrix.mapPoints(corners)
            val sizeF = outputSizePx.toFloat()
            val ndcVertices = floatArrayOf(
                (corners[0] / sizeF) * 2f - 1f, (corners[1] / sizeF) * 2f - 1f,
                (corners[2] / sizeF) * 2f - 1f, (corners[3] / sizeF) * 2f - 1f,
                (corners[4] / sizeF) * 2f - 1f, (corners[5] / sizeF) * 2f - 1f,
                (corners[6] / sizeF) * 2f - 1f, (corners[7] / sizeF) * 2f - 1f,
            )
            val texCoords = floatArrayOf(
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f,
            )
            positionBuffer = toDirectFloatBuffer(ndcVertices)
            texCoordBuffer = toDirectFloatBuffer(texCoords)
        }

        fun renderNextFrameToRgba(outBuffer: ByteBuffer) {
            check(frameSemaphore.tryAcquire(FRAME_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "Tiempo agotado esperando frame decodificado del vídeo."
            }
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)

            GLES20.glViewport(0, 0, outputSizePx, outputSizePx)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES20.glUniformMatrix4fv(uStMatrixHandle, 1, false, stMatrix, 0)

            positionBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aPositionHandle)
            GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)

            texCoordBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aTexCoordHandle)
            GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glFinish()

            outBuffer.clear()
            GLES20.glReadPixels(0, 0, outputSizePx, outputSizePx, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, outBuffer)
            outBuffer.rewind()
        }

        fun release() {
            runCatching { surface.release() }
            runCatching { surfaceTexture.release() }
            runCatching { frameThread.quitSafely() }
            runCatching {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (program != 0) GLES20.glDeleteProgram(program)
                val tex = intArrayOf(oesTexId)
                GLES20.glDeleteTextures(1, tex, 0)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        }

        private fun toDirectFloatBuffer(values: FloatArray): FloatBuffer {
            return ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vShader)
            GLES20.glAttachShader(prog, fShader)
            GLES20.glLinkProgram(prog)
            GLES20.glDeleteShader(vShader)
            GLES20.glDeleteShader(fShader)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            check(linkStatus[0] == GLES20.GL_TRUE) { "Error al enlazar programa GLES." }
            return prog
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] == GLES20.GL_TRUE) {
                "Error al compilar shader GLES: ${GLES20.glGetShaderInfoLog(shader)}"
            }
            return shader
        }
    }
}
