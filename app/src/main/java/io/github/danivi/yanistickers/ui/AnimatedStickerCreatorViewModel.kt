package io.github.danivi.yanistickers.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.danivi.yanistickers.data.AnimatedFrameExtractor
import io.github.danivi.yanistickers.data.AnimatedSourceKind
import io.github.danivi.yanistickers.data.AnimatedSourceProbe
import io.github.danivi.yanistickers.data.AnimatedStickerEncoder
import io.github.danivi.yanistickers.data.FramingMode
import io.github.danivi.yanistickers.data.LocalPackStore
import io.github.danivi.yanistickers.data.PackJsonParser
import io.github.danivi.yanistickers.data.StickerFramingTransform
import io.github.danivi.yanistickers.validation.PackValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class AnimatedStickerCreatorUiState(
    val packName: String = "",
    val isProbing: Boolean = false,
    val isSaving: Boolean = false,
    val saveProgress: Float = 0f,
    val saveStatus: String = "",
    val probe: AnimatedSourceProbe? = null,
    val transform: StickerFramingTransform = StickerFramingTransform(),
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val nameInput: String = "",
    val emojisInput: String = "🎬",
    val accessibilityInput: String = "",
    val saved: Boolean = false,
    val message: String? = null,
) {
    val isVideoSource: Boolean
        get() = probe?.kind == AnimatedSourceKind.VIDEO

    val canSave: Boolean
        get() {
            if (isProbing || isSaving || probe == null) {
                return false
            }
            val emojis = PackJsonParser.splitEmojisGraphemes(emojisInput)
            val emojisValid = emojis.size in PackValidator.MIN_EMOJIS..PackValidator.MAX_EMOJIS
            val a11yValid = accessibilityInput.trim().length <= PackValidator.MAX_ANIMATED_A11Y_LENGTH
            return emojisValid && a11yValid
        }
}

/**
 * ViewModel para previsualizar en tiempo real vídeo, GIF o WebP animado sin reconversión previa y
 * convertir a WebP animado (`≤ 500 KB`, `≤ 10 s`) únicamente al pulsar «Guardar sticker».
 */
class AnimatedStickerCreatorViewModel(
    application: Application,
    private val packId: String,
) : AndroidViewModel(application) {

    private val store = LocalPackStore(application)
    private val _uiState = MutableStateFlow(AnimatedStickerCreatorUiState())
    val uiState: StateFlow<AnimatedStickerCreatorUiState> = _uiState.asStateFlow()

    private var sessionDir: File = AnimatedFrameExtractor.createSessionTempDir(application)
    private var saveJob: Job? = null
    private var currentSessionId: Long = -1L

    init {
        viewModelScope.launch {
            val pack = withContext(Dispatchers.IO) { store.getPack(packId) }
            _uiState.update { it.copy(packName = pack?.name.orEmpty()) }
        }
    }

    fun ensureSession(sessionId: Long) {
        if (currentSessionId == sessionId) return
        currentSessionId = sessionId
        saveJob?.cancel()
        releaseProbeAndTempFiles(createFreshDir = true)
        val packName = _uiState.value.packName
        _uiState.value = AnimatedStickerCreatorUiState(packName = packName)
    }

    fun onMediaSelected(uri: Uri) {
        saveJob?.cancel()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isProbing = true,
                    saveProgress = 0f,
                    saveStatus = "",
                )
            }
            val previousProbe = _uiState.value.probe
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AnimatedFrameExtractor.probeUri(
                        context = getApplication(),
                        uri = uri,
                        sessionDir = sessionDir,
                    )
                }
            }
            result.onSuccess { newProbe ->
                previousProbe?.previewBitmap?.let { old ->
                    if (!old.isRecycled) old.recycle()
                }
                val initialEnd = min(newProbe.totalDurationMs, PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS)
                _uiState.update {
                    it.copy(
                        isProbing = false,
                        probe = newProbe,
                        transform = StickerFramingTransform(mode = FramingMode.FIT),
                        trimStartMs = 0L,
                        trimEndMs = initialEnd,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isProbing = false,
                        message = error.message ?: "No se pudo leer el archivo multimedia seleccionado.",
                    )
                }
            }
        }
    }

    fun setFramingMode(mode: FramingMode) {
        updateTransform {
            copy(
                mode = mode,
                zoom = 1f,
                normalizedPanX = 0f,
                normalizedPanY = 0f,
            )
        }
    }

    fun rotate90() {
        updateTransform {
            copy(
                rotationDegrees = (rotationDegrees + 90) % 360,
                normalizedPanX = 0f,
                normalizedPanY = 0f,
            )
        }
    }

    fun resetTransform() {
        val currentMode = _uiState.value.transform.mode
        updateTransform {
            StickerFramingTransform(mode = currentMode)
        }
    }

    fun applyGesture(zoomChange: Float, panDeltaX: Float, panDeltaY: Float, canvasSizePx: Float) {
        if (canvasSizePx <= 0f) return
        updateTransform {
            copy(
                zoom = zoom * zoomChange,
                normalizedPanX = normalizedPanX + (panDeltaX / canvasSizePx),
                normalizedPanY = normalizedPanY + (panDeltaY / canvasSizePx),
            )
        }
    }

    fun onTrimRangeChanged(startMs: Long, endMs: Long) {
        val probe = _uiState.value.probe ?: return
        val maxTotal = probe.totalDurationMs
        val clampedStart = startMs.coerceIn(0L, max(0L, maxTotal - MIN_TRIM_SPAN_MS))
        var clampedEnd = endMs.coerceIn(clampedStart + MIN_TRIM_SPAN_MS, maxTotal)
        if (clampedEnd - clampedStart > PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS) {
            clampedEnd = clampedStart + PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS
        }
        _uiState.update {
            it.copy(
                trimStartMs = clampedStart,
                trimEndMs = clampedEnd,
            )
        }
    }

    fun onNameChange(value: String) {
        if (value.length <= PackValidator.MAX_TEXT_LENGTH) {
            _uiState.update { it.copy(nameInput = value) }
        }
    }

    fun onEmojisChange(value: String) {
        _uiState.update {
            it.copy(emojisInput = PackJsonParser.filterEmojiInput(value, PackValidator.MAX_EMOJIS))
        }
    }

    fun onAccessibilityChange(value: String) {
        if (value.length <= PackValidator.MAX_ANIMATED_A11Y_LENGTH) {
            _uiState.update { it.copy(accessibilityInput = value) }
        }
    }

    fun save() {
        val current = _uiState.value
        val probe = current.probe ?: return
        if (!current.canSave) return

        val emojis = PackJsonParser.splitEmojisGraphemes(current.emojisInput)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSaving = true,
                    saveProgress = 0.02f,
                    saveStatus = "Iniciando conversión...",
                )
            }
            try {
                val encoded = withContext(Dispatchers.Default) {
                    AnimatedStickerEncoder.encodeFromProbe(
                        probe = probe,
                        transform = current.transform,
                        trimStartMs = current.trimStartMs,
                        trimEndMs = current.trimEndMs,
                        sessionDir = sessionDir,
                        onProgress = { progress, status ->
                            _uiState.update { state ->
                                state.copy(
                                    saveProgress = max(state.saveProgress, progress.coerceIn(0f, 0.96f)),
                                    saveStatus = status,
                                )
                            }
                        },
                    )
                }

                _uiState.update {
                    it.copy(
                        saveProgress = max(it.saveProgress, 0.97f),
                        saveStatus = "Guardando en el pack...",
                    )
                }

                withContext(Dispatchers.IO) {
                    store.addSticker(
                        identifier = packId,
                        webpBytes = encoded.bytes,
                        emojis = emojis,
                        accessibilityText = current.accessibilityInput,
                        name = current.nameInput,
                    ) ?: error("No se encontró el pack.")
                }

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveProgress = 1f,
                        saveStatus = "",
                        saved = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveProgress = 0f,
                        saveStatus = "",
                    )
                }
                throw cancelled
            } catch (error: Throwable) {
                val errMsg = error.message ?: "No se pudo convertir y guardar el sticker animado."
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveProgress = 0f,
                        saveStatus = "",
                        message = errMsg,
                    )
                }
            }
        }
    }

    fun cancelSave() {
        saveJob?.cancel()
    }

    fun consumeSaved() {
        _uiState.update { it.copy(saved = false) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun updateTransform(transformBlock: StickerFramingTransform.() -> StickerFramingTransform) {
        if (_uiState.value.probe == null) return
        val next = _uiState.value.transform.transformBlock().normalized()
        _uiState.update {
            it.copy(
                transform = next,
            )
        }
    }

    private fun releaseProbeAndTempFiles(createFreshDir: Boolean) {
        _uiState.value.probe?.previewBitmap?.let { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
        }
        runCatching { sessionDir.deleteRecursively() }
        if (createFreshDir) {
            sessionDir = AnimatedFrameExtractor.createSessionTempDir(getApplication())
            AnimatedFrameExtractor.cleanupStaleSessions(getApplication(), keepDir = sessionDir)
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        releaseProbeAndTempFiles(createFreshDir = false)
    }

    companion object {
        private const val MIN_TRIM_SPAN_MS = 250L

        fun factory(
            application: Application,
            packId: String,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AnimatedStickerCreatorViewModel(application, packId) as T
                }
            }
    }
}
