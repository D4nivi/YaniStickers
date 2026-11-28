package io.github.danivi.yanistickers.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.danivi.yanistickers.data.FramingMode
import io.github.danivi.yanistickers.data.FramingSnapGuides
import io.github.danivi.yanistickers.data.LocalPackStore
import io.github.danivi.yanistickers.data.OpaqueContentBounds
import io.github.danivi.yanistickers.data.PackJsonParser
import io.github.danivi.yanistickers.data.StaticStickerEncoder
import io.github.danivi.yanistickers.data.StickerFramingSnap
import io.github.danivi.yanistickers.data.StickerFramingTransform
import io.github.danivi.yanistickers.data.StickerItem
import io.github.danivi.yanistickers.validation.ImageContainerFormat
import io.github.danivi.yanistickers.validation.ImageContainerInspector
import io.github.danivi.yanistickers.validation.PackValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StaticStickerCreatorUiState(
    val forTray: Boolean = false,
    val isEditMode: Boolean = false,
    val packName: String = "",
    val isDecoding: Boolean = false,
    val isSaving: Boolean = false,
    val sourceBitmap: Bitmap? = null,
    val hasReplacedSource: Boolean = false,
    val existingAssetValid: Boolean = false,
    val transform: StickerFramingTransform = StickerFramingTransform(),
    val snapGuides: FramingSnapGuides = FramingSnapGuides(),
    val isGestureActive: Boolean = false,
    val nameInput: String = "",
    val emojisInput: String = "😀",
    val accessibilityInput: String = "",
    val saved: Boolean = false,
    val message: String? = null,
) {
    val targetDimensionPx: Int
        get() = if (forTray) {
            PackValidator.RECOMMENDED_TRAY_DIMENSION_PX
        } else {
            PackValidator.STICKER_DIMENSION_PX
        }

    val maxBytes: Long
        get() = if (forTray) {
            PackValidator.MAX_TRAY_BYTES
        } else {
            PackValidator.MAX_STATIC_STICKER_BYTES
        }

    val canSave: Boolean
        get() {
            if (isDecoding || isSaving || sourceBitmap == null) {
                return false
            }
            if (forTray) return true
            val emojis = PackJsonParser.splitEmojisGraphemes(emojisInput)
            val emojisValid = emojis.size in PackValidator.MIN_EMOJIS..PackValidator.MAX_EMOJIS
            val a11yValid = accessibilityInput.trim().length <= PackValidator.MAX_STATIC_A11Y_LENGTH
            return emojisValid && a11yValid
        }
}

/**
 * ViewModel para previsualizar el encuadre y convertir a sticker estático (`512×512`) o icono de
 * bandeja (`96×96`) en formato WebP únicamente al pulsar «Guardar».
 */
class StaticStickerCreatorViewModel(
    application: Application,
    private val packId: String,
    private val forTray: Boolean,
    private val editingStickerIndex: Int? = null,
) : AndroidViewModel(application) {

    private val store = LocalPackStore(application)
    private val _uiState = MutableStateFlow(
        StaticStickerCreatorUiState(
            forTray = forTray,
            isEditMode = !forTray && editingStickerIndex != null,
        ),
    )
    val uiState: StateFlow<StaticStickerCreatorUiState> = _uiState.asStateFlow()

    private var currentSessionId: Long = -1L
    private var loadSessionJob: Job? = null
    private var existingContentBounds: OpaqueContentBounds? = null
    private var rawTransform: StickerFramingTransform = StickerFramingTransform()

    /**
     * Reinicia el estado del creador cuando se abre una nueva sesión desde el editor, preservando
     * el estado durante rotaciones de pantalla dentro de la misma sesión. En modo edición precarga
     * el sticker existente y sus metadatos sin abrir el selector de galería.
     */
    fun ensureSession(sessionId: Long) {
        if (currentSessionId == sessionId) return
        currentSessionId = sessionId
        loadSessionJob?.cancel()
        existingContentBounds = null
        rawTransform = StickerFramingTransform()
        val isEdit = !forTray && editingStickerIndex != null
        val packName = _uiState.value.packName
        _uiState.value = StaticStickerCreatorUiState(
            forTray = forTray,
            isEditMode = isEdit,
            packName = packName,
            isDecoding = isEdit,
        )
        loadSessionJob = viewModelScope.launch {
            loadInitialSession(isEdit)
        }
    }

    private suspend fun loadInitialSession(isEdit: Boolean) {
        val loaded = withContext(Dispatchers.IO) {
            val pack = store.getPack(packId) ?: return@withContext null
            if (!isEdit) return@withContext LoadedEditSession(packName = pack.name)

            val index = editingStickerIndex ?: return@withContext LoadedEditSession(packName = pack.name)
            val item = pack.stickers.getOrNull(index)
                ?: return@withContext LoadedEditSession(
                    packName = pack.name,
                    errorMessage = "No se encontró el sticker a editar.",
                )
            val assetName = PackJsonParser.extractStickerAssetName(item.file)
            val file = assetName?.let { store.resolveAssetFile(packId, it) }
            if (file == null) {
                return@withContext LoadedEditSession(
                    packName = pack.name,
                    item = item,
                    errorMessage = "No se encontró el archivo del sticker a editar.",
                )
            }

            val inspected = ImageContainerInspector.inspect(file)
            val isExistingValid = inspected != null &&
                inspected.format == ImageContainerFormat.WEBP &&
                !inspected.isAnimated &&
                inspected.width == PackValidator.STICKER_DIMENSION_PX &&
                inspected.height == PackValidator.STICKER_DIMENSION_PX &&
                inspected.fileSizeBytes in 1L..PackValidator.MAX_STATIC_STICKER_BYTES
            val decoded = runCatching { StaticStickerEncoder.decodeSourceBitmap(file) }
            val decodedBitmap = decoded.getOrNull()
            val bounds = decodedBitmap?.let { StaticStickerEncoder.computeOpaqueContentBounds(it) }
            LoadedEditSession(
                packName = pack.name,
                item = item,
                bitmap = decodedBitmap,
                contentBounds = bounds,
                existingAssetValid = isExistingValid,
                errorMessage = decoded.exceptionOrNull()?.message
                    ?: if (decoded.isFailure) "No se pudo abrir el sticker a editar." else null,
            )
        }

        existingContentBounds = loaded?.contentBounds
        rawTransform = StickerFramingTransform(mode = FramingMode.FIT)
        _uiState.update { state ->
            if (loaded == null) {
                state.copy(
                    isDecoding = false,
                    message = if (isEdit) "No se encontró el pack." else state.message,
                )
            } else {
                val item = loaded.item
                state.copy(
                    packName = loaded.packName,
                    isDecoding = false,
                    sourceBitmap = loaded.bitmap ?: state.sourceBitmap,
                    hasReplacedSource = false,
                    existingAssetValid = loaded.existingAssetValid,
                    transform = StickerFramingTransform(mode = FramingMode.FIT),
                    nameInput = item?.name ?: state.nameInput,
                    emojisInput = item?.emojis?.joinToString(" ") ?: state.emojisInput,
                    accessibilityInput = item?.accessibilityText ?: state.accessibilityInput,
                    message = loaded.errorMessage ?: state.message,
                )
            }
        }
    }

    fun consumeSaved() {
        _uiState.update { it.copy(saved = false) }
    }

    fun onImageSelected(uri: Uri) {
        loadSessionJob?.cancel()
        viewModelScope.launch {
            _uiState.update { it.copy(isDecoding = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    StaticStickerEncoder.decodeSourceBitmap(getApplication(), uri)
                }
            }
            result.onSuccess { bitmap ->
                existingContentBounds = null
                val resetTransform = StickerFramingTransform(mode = FramingMode.FIT)
                rawTransform = resetTransform
                _uiState.update {
                    it.copy(
                        isDecoding = false,
                        sourceBitmap = bitmap,
                        hasReplacedSource = true,
                        transform = resetTransform,
                        snapGuides = FramingSnapGuides(),
                        isGestureActive = false,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isDecoding = false,
                        message = error.message ?: "No se pudo abrir la imagen seleccionada.",
                    )
                }
            }
        }
    }

    fun setFramingMode(mode: FramingMode) {
        updateDirectTransform {
            StaticStickerEncoder.transformForFramingMode(
                mode = mode,
                rotationDegrees = rotationDegrees,
                contentBounds = existingContentBounds,
            )
        }
    }

    fun rotate90() {
        updateDirectTransform {
            StaticStickerEncoder.rotateTransform90(
                current = this,
                contentBounds = existingContentBounds,
            )
        }
    }

    fun resetTransform() {
        val currentMode = _uiState.value.transform.mode
        updateDirectTransform {
            StaticStickerEncoder.transformForFramingMode(
                mode = currentMode,
                rotationDegrees = 0,
                contentBounds = existingContentBounds,
            )
        }
    }

    fun applyGesture(zoomChange: Float, panDeltaX: Float, panDeltaY: Float, canvasSizePx: Float) {
        val bitmap = _uiState.value.sourceBitmap ?: return
        if (canvasSizePx <= 0f) return
        val nextRaw = rawTransform.copy(
            zoom = rawTransform.zoom * zoomChange,
            normalizedPanX = rawTransform.normalizedPanX + (panDeltaX / canvasSizePx),
            normalizedPanY = rawTransform.normalizedPanY + (panDeltaY / canvasSizePx),
        ).normalized()
        rawTransform = nextRaw
        val snapped = StickerFramingSnap.applySnap(
            rawTransform = nextRaw,
            srcWidth = bitmap.width,
            srcHeight = bitmap.height,
            contentBounds = existingContentBounds,
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
        if (value.length <= PackValidator.MAX_STATIC_A11Y_LENGTH) {
            _uiState.update { it.copy(accessibilityInput = value) }
        }
    }

    fun save() {
        val current = _uiState.value
        val bitmap = current.sourceBitmap ?: return
        if (current.isDecoding || current.isSaving) return
        if (!forTray) {
            val emojis = PackJsonParser.splitEmojisGraphemes(current.emojisInput)
            if (emojis.size !in PackValidator.MIN_EMOJIS..PackValidator.MAX_EMOJIS) return
            if (current.accessibilityInput.trim().length > PackValidator.MAX_STATIC_A11Y_LENGTH) return
        }

        val editIndex = editingStickerIndex?.takeIf { current.isEditMode }
        val framingChanged = current.transform.normalized() != StickerFramingTransform(mode = FramingMode.FIT)
        val canSkipReencode = editIndex != null && !current.hasReplacedSource && !framingChanged && current.existingAssetValid

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val result = runCatching {
                val emojis = PackJsonParser.splitEmojisGraphemes(current.emojisInput)
                if (canSkipReencode) {
                    withContext(Dispatchers.IO) {
                        store.updateSticker(
                            identifier = packId,
                            stickerIndex = editIndex!!,
                            emojis = emojis,
                            accessibilityText = current.accessibilityInput,
                            name = current.nameInput,
                        ) ?: error("No se encontró el sticker en el pack.")
                    }
                } else {
                    val encoded = withContext(Dispatchers.Default) {
                        StaticStickerEncoder.renderAndEncodeWebp(
                            sourceBitmap = bitmap,
                            transform = current.transform,
                            outputSizePx = current.targetDimensionPx,
                            maxBytes = current.maxBytes,
                        )
                    }
                    withContext(Dispatchers.IO) {
                        when {
                            forTray -> store.updateTrayIcon(
                                identifier = packId,
                                trayBytes = encoded.bytes,
                            ) ?: error("No se encontró el pack.")
                            editIndex != null -> store.replaceSticker(
                                identifier = packId,
                                stickerIndex = editIndex,
                                webpBytes = encoded.bytes,
                                emojis = emojis,
                                accessibilityText = current.accessibilityInput,
                                name = current.nameInput,
                            ) ?: error("No se encontró el sticker en el pack.")
                            else -> store.addSticker(
                                identifier = packId,
                                webpBytes = encoded.bytes,
                                emojis = emojis,
                                accessibilityText = current.accessibilityInput,
                                name = current.nameInput,
                            ) ?: error("No se encontró el pack.")
                        }
                    }
                }
            }
            result.onSuccess {
                _uiState.update { it.copy(isSaving = false, saved = true) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        message = error.message ?: "No se pudo guardar la imagen.",
                    )
                }
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    override fun onCleared() {
        loadSessionJob?.cancel()
    }

    private fun updateDirectTransform(transformBlock: StickerFramingTransform.() -> StickerFramingTransform) {
        if (_uiState.value.sourceBitmap == null) return
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

    private data class LoadedEditSession(
        val packName: String,
        val item: StickerItem? = null,
        val bitmap: Bitmap? = null,
        val contentBounds: OpaqueContentBounds? = null,
        val existingAssetValid: Boolean = false,
        val errorMessage: String? = null,
    )

    companion object {
        fun factory(
            application: Application,
            packId: String,
            forTray: Boolean,
            editingStickerIndex: Int? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return StaticStickerCreatorViewModel(application, packId, forTray, editingStickerIndex) as T
                }
            }
    }
}
