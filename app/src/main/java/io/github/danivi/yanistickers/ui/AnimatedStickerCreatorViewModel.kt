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
import io.github.danivi.yanistickers.data.FramingSnapGuides
import io.github.danivi.yanistickers.data.LocalPackStore
import io.github.danivi.yanistickers.data.PackJsonParser
import io.github.danivi.yanistickers.data.StaticStickerEncoder
import io.github.danivi.yanistickers.data.StickerFramingSnap
import io.github.danivi.yanistickers.data.StickerFramingTransform
import io.github.danivi.yanistickers.data.StickerItem
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
    val isEditMode: Boolean = false,
    val packName: String = "",
    val isProbing: Boolean = false,
    val isSaving: Boolean = false,
    val saveProgress: Float = 0f,
    val saveStatus: String = "",
    val probe: AnimatedSourceProbe? = null,
    val hasReplacedSource: Boolean = false,
    val transform: StickerFramingTransform = StickerFramingTransform(),
    val snapGuides: FramingSnapGuides = FramingSnapGuides(),
    val isGestureActive: Boolean = false,
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
    private val editingStickerIndex: Int? = null,
) : AndroidViewModel(application) {

    private val store = LocalPackStore(application)
    private val _uiState = MutableStateFlow(
        AnimatedStickerCreatorUiState(isEditMode = editingStickerIndex != null),
    )
    val uiState: StateFlow<AnimatedStickerCreatorUiState> = _uiState.asStateFlow()

    private var sessionDir: File = AnimatedFrameExtractor.createSessionTempDir(application)
    private var loadSessionJob: Job? = null
    private var saveJob: Job? = null
    private var currentSessionId: Long = -1L
    private var rawTransform: StickerFramingTransform = StickerFramingTransform()

    fun ensureSession(sessionId: Long) {
        if (currentSessionId == sessionId) return
        currentSessionId = sessionId
        loadSessionJob?.cancel()
        saveJob?.cancel()
        releaseProbeAndTempFiles(createFreshDir = true)
        rawTransform = StickerFramingTransform()
        val isEdit = editingStickerIndex != null
        val packName = _uiState.value.packName
        _uiState.value = AnimatedStickerCreatorUiState(
            isEditMode = isEdit,
            packName = packName,
            isProbing = isEdit,
        )
        loadSessionJob = viewModelScope.launch {
            loadInitialSession(isEdit)
        }
    }

    private suspend fun loadInitialSession(isEdit: Boolean) {
        val loaded = withContext(Dispatchers.IO) {
            val pack = store.getPack(packId) ?: return@withContext null
            if (!isEdit) return@withContext LoadedAnimatedEditSession(packName = pack.name)

            val index = editingStickerIndex ?: return@withContext LoadedAnimatedEditSession(packName = pack.name)
            val item = pack.stickers.getOrNull(index)
                ?: return@withContext LoadedAnimatedEditSession(
                    packName = pack.name,
                    errorMessage = "No se encontró el sticker a editar.",
                )
            val assetName = PackJsonParser.extractStickerAssetName(item.file)
            val file = assetName?.let { store.resolveAssetFile(packId, it) }
            if (file == null) {
                return@withContext LoadedAnimatedEditSession(
                    packName = pack.name,
                    item = item,
                    errorMessage = "No se encontró el archivo del sticker a editar.",
                )
            }

            val probed = runCatching {
                AnimatedFrameExtractor.probeExistingWebpSticker(
                    sourceFile = file,
                    sessionDir = sessionDir,
                )
            }
            LoadedAnimatedEditSession(
                packName = pack.name,
                item = item,
                probe = probed.getOrNull(),
                errorMessage = probed.exceptionOrNull()?.message
                    ?: if (probed.isFailure) "No se pudo abrir el sticker animado a editar." else null,
            )
        }

        _uiState.update { state ->
            if (loaded == null) {
                state.copy(
                    isProbing = false,
                    message = if (isEdit) "No se encontró el pack." else state.message,
                )
            } else {
                val item = loaded.item
                val newProbe = loaded.probe
                val initialEnd = if (newProbe != null) {
                    min(newProbe.totalDurationMs, PackValidator.MAX_ANIMATED_TOTAL_DURATION_MS)
                } else {
                    state.trimEndMs
                }
                rawTransform = StickerFramingTransform(mode = FramingMode.FIT)
                state.copy(
                    packName = loaded.packName,
                    isProbing = false,
                    probe = newProbe ?: state.probe,
                    hasReplacedSource = false,
                    transform = rawTransform,
                    snapGuides = FramingSnapGuides(),
                    isGestureActive = false,
                    trimStartMs = 0L,
                    trimEndMs = initialEnd,
                    nameInput = item?.name ?: state.nameInput,
                    emojisInput = item?.emojis?.joinToString(" ") ?: state.emojisInput,
                    accessibilityInput = item?.accessibilityText ?: state.accessibilityInput,
                    message = loaded.errorMessage ?: state.message,
                )
            }
        }
    }

    fun onMediaSelected(uri: Uri) {
        loadSessionJob?.cancel()
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
                rawTransform = StickerFramingTransform(mode = FramingMode.FIT)
                _uiState.update {
                    it.copy(
                        isProbing = false,
                        probe = newProbe,
                        hasReplacedSource = true,
                        transform = rawTransform,
                        snapGuides = FramingSnapGuides(),
                        isGestureActive = false,
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
        val bounds = _uiState.value.probe?.contentBounds
        updateDirectTransform {
            StaticStickerEncoder.transformForFramingMode(
                mode = mode,
                rotationDegrees = rotationDegrees,
                contentBounds = bounds,
            )
        }
    }

    fun rotate90() {
        val bounds = _uiState.value.probe?.contentBounds
        updateDirectTransform {
            StaticStickerEncoder.rotateTransform90(
                current = this,
                contentBounds = bounds,
            )
        }
    }

    fun resetTransform() {
        val currentMode = _uiState.value.transform.mode
        val bounds = _uiState.value.probe?.contentBounds
        updateDirectTransform {
            StaticStickerEncoder.transformForFramingMode(
                mode = currentMode,
                rotationDegrees = 0,
                contentBounds = bounds,
            )
        }
    }

    fun applyGesture(zoomChange: Float, panDeltaX: Float, panDeltaY: Float, canvasSizePx: Float) {
        val probe = _uiState.value.probe ?: return
        if (canvasSizePx <= 0f) return
        val nextRaw = rawTransform.copy(
            zoom = rawTransform.zoom * zoomChange,
            normalizedPanX = rawTransform.normalizedPanX + (panDeltaX / canvasSizePx),
            normalizedPanY = rawTransform.normalizedPanY + (panDeltaY / canvasSizePx),
        ).normalized()
        rawTransform = nextRaw
        val snapped = StickerFramingSnap.applySnap(
            rawTransform = nextRaw,
            srcWidth = probe.width,
            srcHeight = probe.height,
            contentBounds = probe.contentBounds,
        )
        _uiState.update {
            it.copy(
                transform = snapped.transform,
                snapGuides = snapped.guides,
                isGestureActive = true,
            )
        }
    }

    fun onTransformGestureEnd() {
        rawTransform = _uiState.value.transform
        _uiState.update {
            it.copy(
                snapGuides = FramingSnapGuides(),
                isGestureActive = false,
            )
        }
    }

    fun onTrimRangeChanged(startMs: Long, endMs: Long) {
        val probe = _uiState.value.probe ?: return
        val maxTotal = probe.totalDurationMs.coerceAtLeast(1L)
        val effectiveMinSpan = min(MIN_TRIM_SPAN_MS, maxTotal)
        val clampedStart = startMs.coerceIn(0L, max(0L, maxTotal - effectiveMinSpan))
        var clampedEnd = endMs.coerceIn(clampedStart + effectiveMinSpan, maxTotal)
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
        val editIndex = editingStickerIndex?.takeIf { current.isEditMode }
        val framingChanged = current.transform.normalized() != StickerFramingTransform(mode = FramingMode.FIT)
        val canSkipReencode = editIndex != null && !current.hasReplacedSource && !framingChanged && probe.isDirectValidWebp

        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (canSkipReencode) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        store.updateSticker(
                            identifier = packId,
                            stickerIndex = editIndex!!,
                            emojis = emojis,
                            accessibilityText = current.accessibilityInput,
                            name = current.nameInput,
                        ) ?: error("No se encontró el sticker en el pack.")
                    }
                }.onSuccess {
                    _uiState.update { it.copy(saved = true) }
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(message = error.message ?: "No se pudo guardar el sticker animado.")
                    }
                }
                return@launch
            }

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
                    if (editIndex != null) {
                        store.replaceSticker(
                            identifier = packId,
                            stickerIndex = editIndex,
                            webpBytes = encoded.bytes,
                            emojis = emojis,
                            accessibilityText = current.accessibilityInput,
                            name = current.nameInput,
                        ) ?: error("No se encontró el sticker en el pack.")
                    } else {
                        store.addSticker(
                            identifier = packId,
                            webpBytes = encoded.bytes,
                            emojis = emojis,
                            accessibilityText = current.accessibilityInput,
                            name = current.nameInput,
                        ) ?: error("No se encontró el pack.")
                    }
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

    private fun updateDirectTransform(transformBlock: StickerFramingTransform.() -> StickerFramingTransform) {
        if (_uiState.value.probe == null) return
        val next = _uiState.value.transform.transformBlock().normalized()
        rawTransform = next
        _uiState.update {
            it.copy(
                transform = next,
                snapGuides = FramingSnapGuides(),
                isGestureActive = false,
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
        loadSessionJob?.cancel()
        saveJob?.cancel()
        releaseProbeAndTempFiles(createFreshDir = false)
    }

    private data class LoadedAnimatedEditSession(
        val packName: String,
        val item: StickerItem? = null,
        val probe: AnimatedSourceProbe? = null,
        val errorMessage: String? = null,
    )

    companion object {
        private const val MIN_TRIM_SPAN_MS = 250L

        fun factory(
            application: Application,
            packId: String,
            editingStickerIndex: Int? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AnimatedStickerCreatorViewModel(application, packId, editingStickerIndex) as T
                }
            }
    }
}
