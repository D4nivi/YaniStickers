package io.github.danivi.yanistickers.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.danivi.yanistickers.data.FramingMode
import io.github.danivi.yanistickers.data.LocalPackStore
import io.github.danivi.yanistickers.data.PackJsonParser
import io.github.danivi.yanistickers.data.StaticStickerEncoder
import io.github.danivi.yanistickers.data.StickerFramingTransform
import io.github.danivi.yanistickers.validation.PackValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StaticStickerCreatorUiState(
    val forTray: Boolean = false,
    val packName: String = "",
    val isDecoding: Boolean = false,
    val isEncoding: Boolean = false,
    val isSaving: Boolean = false,
    val sourceBitmap: Bitmap? = null,
    val transform: StickerFramingTransform = StickerFramingTransform(),
    val encodedBytes: ByteArray? = null,
    val usedLossless: Boolean = false,
    val encodeError: String? = null,
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
            if (isDecoding || isSaving || sourceBitmap == null || encodedBytes == null || encodeError != null) {
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
 * ViewModel para crear un sticker estático (`512×512`) o personalizar el icono de bandeja (`96×96`).
 */
class StaticStickerCreatorViewModel(
    application: Application,
    private val packId: String,
    private val forTray: Boolean,
) : AndroidViewModel(application) {

    private val store = LocalPackStore(application)
    private val _uiState = MutableStateFlow(StaticStickerCreatorUiState(forTray = forTray))
    val uiState: StateFlow<StaticStickerCreatorUiState> = _uiState.asStateFlow()

    private var encodeJob: Job? = null
    private var currentSessionId: Long = -1L

    init {
        viewModelScope.launch {
            val pack = withContext(Dispatchers.IO) { store.getPack(packId) }
            _uiState.update { it.copy(packName = pack?.name.orEmpty()) }
        }
    }

    /**
     * Reinicia el estado del creador cuando se abre una nueva sesión de añadido desde el editor,
     * preservando el estado durante rotaciones de pantalla dentro de la misma sesión.
     */
    fun ensureSession(sessionId: Long) {
        if (currentSessionId == sessionId) return
        currentSessionId = sessionId
        encodeJob?.cancel()
        val packName = _uiState.value.packName
        _uiState.value = StaticStickerCreatorUiState(
            forTray = forTray,
            packName = packName,
        )
    }

    fun consumeSaved() {
        _uiState.update { it.copy(saved = false) }
    }

    fun onImageSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDecoding = true,
                    encodeError = null,
                    encodedBytes = null,
                )
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    StaticStickerEncoder.decodeSourceBitmap(getApplication(), uri)
                }
            }
            result.onSuccess { bitmap ->
                val resetTransform = StickerFramingTransform(mode = FramingMode.FIT)
                _uiState.update {
                    it.copy(
                        isDecoding = false,
                        sourceBitmap = bitmap,
                        transform = resetTransform,
                    )
                }
                scheduleEncode(bitmap, resetTransform, immediate = true)
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

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val encoded = StaticStickerEncoder.renderAndEncodeWebp(
                        sourceBitmap = bitmap,
                        transform = current.transform,
                        outputSizePx = current.targetDimensionPx,
                        maxBytes = current.maxBytes,
                    )
                    if (forTray) {
                        store.updateTrayIcon(
                            identifier = packId,
                            trayBytes = encoded.bytes,
                        ) ?: error("No se encontró el pack.")
                    } else {
                        val emojis = PackJsonParser.splitEmojisGraphemes(current.emojisInput)
                        store.addSticker(
                            identifier = packId,
                            webpBytes = encoded.bytes,
                            emojis = emojis,
                            accessibilityText = current.accessibilityInput,
                            name = current.nameInput,
                        ) ?: error("No se encontró el pack.")
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

    private fun updateTransform(transformBlock: StickerFramingTransform.() -> StickerFramingTransform) {
        val bitmap = _uiState.value.sourceBitmap ?: return
        val next = _uiState.value.transform.transformBlock().normalized()
        _uiState.update { it.copy(transform = next) }
        scheduleEncode(bitmap, next, immediate = false)
    }

    private fun scheduleEncode(
        bitmap: Bitmap,
        transform: StickerFramingTransform,
        immediate: Boolean,
    ) {
        encodeJob?.cancel()
        val stateSnapshot = _uiState.value
        encodeJob = viewModelScope.launch {
            _uiState.update { it.copy(isEncoding = true, encodeError = null) }
            if (!immediate) {
                delay(150)
            }
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    StaticStickerEncoder.renderAndEncodeWebp(
                        sourceBitmap = bitmap,
                        transform = transform,
                        outputSizePx = stateSnapshot.targetDimensionPx,
                        maxBytes = stateSnapshot.maxBytes,
                    )
                }
            }
            result.onSuccess { encoded ->
                _uiState.update {
                    it.copy(
                        isEncoding = false,
                        encodedBytes = encoded.bytes,
                        usedLossless = encoded.usedLossless,
                        encodeError = null,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isEncoding = false,
                        encodedBytes = null,
                        encodeError = error.message ?: "Error al codificar WebP.",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(
            application: Application,
            packId: String,
            forTray: Boolean,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return StaticStickerCreatorViewModel(application, packId, forTray) as T
                }
            }
    }
}
