package io.github.danivi.yanistickers.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.danivi.yanistickers.data.LocalPackStore
import io.github.danivi.yanistickers.data.PackJsonParser
import io.github.danivi.yanistickers.data.StaticStickerEncoder
import io.github.danivi.yanistickers.data.StickerItem
import io.github.danivi.yanistickers.data.StickerPack
import io.github.danivi.yanistickers.validation.PackValidationReport
import io.github.danivi.yanistickers.validation.PackValidator
import io.github.danivi.yanistickers.validation.StickerValidationReport
import io.github.danivi.yanistickers.whatsapp.PackWhitelistStatus
import io.github.danivi.yanistickers.whatsapp.WhatsAppBridge
import io.github.danivi.yanistickers.whatsapp.WhatsAppTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class EditorStickerUi(
    val index: Int,
    val item: StickerItem,
    val assetFile: File?,
    val validation: StickerValidationReport?,
)

data class PackEditorUiState(
    val isLoading: Boolean = true,
    val packNotFound: Boolean = false,
    val pack: StickerPack? = null,
    val trayFile: File? = null,
    val validation: PackValidationReport? = null,
    val stickers: List<EditorStickerUi> = emptyList(),
    val installedTargets: List<WhatsAppTarget> = emptyList(),
    val whitelistStatus: PackWhitelistStatus = PackWhitelistStatus(),
    val nameInput: String = "",
    val publisherInput: String = "",
    val isGridView: Boolean = false,
    val message: String? = null,
) {
    val hasMetadataChanges: Boolean
        get() = pack != null &&
            (nameInput.trim() != pack.name || publisherInput.trim() != pack.publisher)

    val canSaveMetadata: Boolean
        get() = hasMetadataChanges &&
            nameInput.trim().isNotEmpty() &&
            nameInput.trim().length <= PackValidator.MAX_TEXT_LENGTH &&
            publisherInput.trim().isNotEmpty() &&
            publisherInput.trim().length <= PackValidator.MAX_TEXT_LENGTH
}

/**
 * ViewModel del editor de un pack concreto.
 */
class PackEditorViewModel(
    application: Application,
    private val packId: String,
) : AndroidViewModel(application) {

    private val store = LocalPackStore(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        PackEditorUiState(isGridView = prefs.getBoolean(KEY_EDITOR_GRID_VIEW, false)),
    )
    val uiState: StateFlow<PackEditorUiState> = _uiState.asStateFlow()

    init {
        refresh(syncInputs = true, showLoading = true)
    }

    fun toggleViewMode() {
        val next = !_uiState.value.isGridView
        prefs.edit().putBoolean(KEY_EDITOR_GRID_VIEW, next).apply()
        _uiState.update { it.copy(isGridView = next) }
    }

    fun refresh(
        syncInputs: Boolean = false,
        showLoading: Boolean = _uiState.value.pack == null,
    ) {
        viewModelScope.launch {
            if (showLoading) {
                _uiState.update { it.copy(isLoading = true) }
            }
            val loaded = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val pack = store.getPack(packId)
                if (pack == null) {
                    null
                } else {
                    val report = PackValidator.validate(store, pack)
                    val stickerRows = pack.stickers.mapIndexed { index, item ->
                        val assetName = PackJsonParser.extractStickerAssetName(item.file)
                        EditorStickerUi(
                            index = index,
                            item = item,
                            assetFile = assetName?.let { store.resolveAssetFile(pack.identifier, it) },
                            validation = report.stickerReports.getOrNull(index),
                        )
                    }
                    LoadedEditorData(
                        pack = pack,
                        trayFile = store.resolveAssetFile(pack.identifier, pack.trayImageFile),
                        validation = report,
                        stickers = stickerRows,
                        installedTargets = WhatsAppBridge.installedTargets(app),
                        whitelistStatus = WhatsAppBridge.getWhitelistStatus(app, pack.identifier),
                    )
                }
            }

            _uiState.update { current ->
                if (loaded == null) {
                    current.copy(isLoading = false, packNotFound = true, pack = null)
                } else {
                    current.copy(
                        isLoading = false,
                        packNotFound = false,
                        pack = loaded.pack,
                        trayFile = loaded.trayFile,
                        validation = loaded.validation,
                        stickers = loaded.stickers,
                        installedTargets = loaded.installedTargets,
                        whitelistStatus = loaded.whitelistStatus,
                        nameInput = if (syncInputs || current.pack == null) loaded.pack.name else current.nameInput,
                        publisherInput = if (syncInputs || current.pack == null) {
                            loaded.pack.publisher
                        } else {
                            current.publisherInput
                        },
                    )
                }
            }
        }
    }

    fun onNameChange(value: String) {
        if (value.length <= PackValidator.MAX_TEXT_LENGTH) {
            _uiState.update { it.copy(nameInput = value) }
        }
    }

    fun onPublisherChange(value: String) {
        if (value.length <= PackValidator.MAX_TEXT_LENGTH) {
            _uiState.update { it.copy(publisherInput = value) }
        }
    }

    fun saveMetadata() {
        val state = _uiState.value
        if (!state.canSaveMetadata) return
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                store.updateMetadata(
                    identifier = packId,
                    name = state.nameInput,
                    publisher = state.publisherInput,
                )
            }
            if (updated != null) {
                _uiState.update {
                    it.copy(
                        nameInput = updated.name,
                        publisherInput = updated.publisher,
                        message = "Metadatos guardados (v${updated.imageDataVersion}).",
                    )
                }
                refresh(syncInputs = true)
            }
        }
    }

    fun updateSticker(index: Int, name: String, emojisRaw: String, accessibilityText: String) {
        val emojis = PackJsonParser.splitEmojisGraphemes(emojisRaw)
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                store.updateSticker(
                    identifier = packId,
                    stickerIndex = index,
                    emojis = emojis,
                    accessibilityText = accessibilityText,
                    name = name,
                )
            }
            if (updated != null) {
                _uiState.update {
                    it.copy(message = "Sticker actualizado (v${updated.imageDataVersion}).")
                }
                refresh()
            }
        }
    }

    fun moveStickerUp(index: Int) {
        if (index <= 0) return
        reorderSticker(index, index - 1)
    }

    fun moveStickerDown(index: Int) {
        val size = _uiState.value.stickers.size
        if (index >= size - 1) return
        reorderSticker(index, index + 1)
    }

    private fun reorderSticker(fromIndex: Int, toIndex: Int) {
        val current = _uiState.value
        val currentPack = current.pack ?: return
        if (fromIndex !in current.stickers.indices || toIndex !in current.stickers.indices || fromIndex == toIndex) {
            return
        }

        // Actualización optimista inmediata para que Compose anime el intercambio sin recargar la pantalla
        val optimisticStickers = current.stickers.toMutableList().apply {
            val moved = removeAt(fromIndex)
            add(toIndex, moved)
        }.mapIndexed { idx, row -> row.copy(index = idx) }
        val optimisticPackItems = optimisticStickers.map { it.item }
        _uiState.update {
            it.copy(
                pack = currentPack.copy(stickers = optimisticPackItems),
                stickers = optimisticStickers,
            )
        }

        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                store.reorderStickers(packId, fromIndex, toIndex)
            }
            if (updated != null) {
                refresh(showLoading = false)
            }
        }
    }

    fun setStickerAsTrayIcon(stickerIndex: Int) {
        val sticker = _uiState.value.stickers.getOrNull(stickerIndex) ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val assetName = PackJsonParser.extractStickerAssetName(sticker.item.file)
                        ?: error("Archivo de sticker inválido.")
                    val file = store.resolveAssetFile(packId, assetName)
                        ?: error("No se encontró el archivo del sticker.")
                    val trayBytes = StaticStickerEncoder.encodeWebpFileAsTray(file)
                    store.updateTrayIcon(packId, trayBytes)
                        ?: error("No se encontró el pack.")
                }
            }
            result.onSuccess {
                _uiState.update { it.copy(message = "Icono del pack actualizado.") }
                refresh(showLoading = false)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(message = error.message ?: "No se pudo actualizar el icono del pack.")
                }
            }
        }
    }

    fun removeSticker(index: Int) {
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                store.removeSticker(packId, index)
            }
            if (updated != null) {
                _uiState.update {
                    it.copy(message = "Sticker eliminado (v${updated.imageDataVersion}).")
                }
                refresh()
            }
        }
    }

    fun notifyAddStickerUpcoming() {
        _uiState.update {
            it.copy(
                message = "La creación e importación de stickers animados llegará en la Fase 5.",
            )
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private data class LoadedEditorData(
        val pack: StickerPack,
        val trayFile: File?,
        val validation: PackValidationReport,
        val stickers: List<EditorStickerUi>,
        val installedTargets: List<WhatsAppTarget>,
        val whitelistStatus: PackWhitelistStatus,
    )

    companion object {
        private const val PREFS_NAME = "yanistickers_ui_prefs"
        private const val KEY_EDITOR_GRID_VIEW = "editor_grid_view"

        fun factory(application: Application, packId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PackEditorViewModel(application, packId) as T
                }
            }
    }
}
