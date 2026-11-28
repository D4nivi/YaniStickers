package io.github.danivi.yanistickers.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.danivi.yanistickers.data.LocalPackStore
import io.github.danivi.yanistickers.data.PackJsonParser
import io.github.danivi.yanistickers.data.SeedPackInstaller
import io.github.danivi.yanistickers.data.StickerPack
import io.github.danivi.yanistickers.update.ApkInstaller
import io.github.danivi.yanistickers.update.GitHubUpdateClient
import io.github.danivi.yanistickers.update.UpdateReleaseInfo
import io.github.danivi.yanistickers.validation.PackValidationReport
import io.github.danivi.yanistickers.validation.PackValidator
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

enum class PackSortOption(val label: String) {
    NAME_ASC("Nombre (A–Z)"),
    NAME_DESC("Nombre (Z–A)"),
    CREATED_DESC("Fecha de creación (más recientes)"),
    CREATED_ASC("Fecha de creación (más antiguos)"),
}

data class PackSummaryUi(
    val pack: StickerPack,
    val trayFile: File?,
    val previewStickerFiles: List<File> = emptyList(),
    val validation: PackValidationReport,
    val whitelistStatus: PackWhitelistStatus,
)

data class AppUpdateUiState(
    val currentVersionName: String = "1.0.0",
    val availableRelease: UpdateReleaseInfo? = null,
    val pendingConfirmationRelease: UpdateReleaseInfo? = null,
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgressPercent: Int? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val needsInstallPermissionPrompt: Boolean = false,
)

data class PackListUiState(
    val isLoading: Boolean = true,
    val packs: List<PackSummaryUi> = emptyList(),
    val sortOption: PackSortOption = PackSortOption.CREATED_DESC,
    val installedTargets: List<WhatsAppTarget> = emptyList(),
    val message: String? = null,
    val updateState: AppUpdateUiState = AppUpdateUiState(),
)

/**
 * ViewModel de la pantalla principal de lista de packs.
 */
class PackListViewModel(application: Application) : AndroidViewModel(application) {

    private val store = LocalPackStore(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        PackListUiState(
            sortOption = readSavedSortOption(),
            updateState = AppUpdateUiState(
                currentVersionName = ApkInstaller.readInstalledVersionName(application),
            ),
        ),
    )
    val uiState: StateFlow<PackListUiState> = _uiState.asStateFlow()

    init {
        refresh()
        checkForUpdatesOnStartup()
    }

    fun setSortOption(option: PackSortOption) {
        prefs.edit().putString(KEY_SORT_OPTION, option.name).apply()
        _uiState.update { current ->
            current.copy(
                sortOption = option,
                packs = sortPacks(current.packs, option),
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (_uiState.value.packs.isEmpty()) {
                _uiState.update { it.copy(isLoading = true) }
            }
            val currentSort = _uiState.value.sortOption
            val loaded = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                SeedPackInstaller.ensureSeedPacksInstalled(app)
                val targets = WhatsAppBridge.installedTargets(app)
                val packs = store.listPacks().map { pack ->
                    val previewFiles = pack.stickers
                        .take(PREVIEW_STICKER_COUNT)
                        .mapNotNull { item ->
                            PackJsonParser.extractStickerAssetName(item.file)
                                ?.let { assetName -> store.resolveAssetFile(pack.identifier, assetName) }
                        }
                    PackSummaryUi(
                        pack = pack,
                        trayFile = store.resolveAssetFile(pack.identifier, pack.trayImageFile),
                        previewStickerFiles = previewFiles,
                        validation = PackValidator.validate(store, pack),
                        whitelistStatus = WhatsAppBridge.getWhitelistStatus(app, pack.identifier),
                    )
                }
                sortPacks(packs, currentSort) to targets
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    packs = loaded.first,
                    installedTargets = loaded.second,
                )
            }
        }
    }

    private fun readSavedSortOption(): PackSortOption {
        val raw = prefs.getString(KEY_SORT_OPTION, null) ?: return PackSortOption.CREATED_DESC
        return PackSortOption.entries.firstOrNull { it.name == raw } ?: PackSortOption.CREATED_DESC
    }

    private fun sortPacks(
        items: List<PackSummaryUi>,
        option: PackSortOption,
    ): List<PackSummaryUi> {
        return when (option) {
            PackSortOption.NAME_ASC -> items.sortedWith(
                compareBy<PackSummaryUi, String>(String.CASE_INSENSITIVE_ORDER) { it.pack.name }
                    .thenBy { it.pack.identifier },
            )
            PackSortOption.NAME_DESC -> items.sortedWith(
                compareByDescending<PackSummaryUi, String>(String.CASE_INSENSITIVE_ORDER) { it.pack.name }
                    .thenBy { it.pack.identifier },
            )
            PackSortOption.CREATED_DESC -> items.sortedWith(
                compareByDescending<PackSummaryUi> { it.pack.createdAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.pack.name },
            )
            PackSortOption.CREATED_ASC -> items.sortedWith(
                compareBy<PackSummaryUi> { it.pack.createdAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.pack.name },
            )
        }
    }

    fun createPack(
        name: String,
        publisher: String,
        animated: Boolean,
        onCreated: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { store.createPack(name = name, publisher = publisher, animated = animated) }
            }
            result.onSuccess { created ->
                refresh()
                onCreated(created.identifier)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(message = error.message ?: "No se pudo crear el pack.")
                }
            }
        }
    }

    fun duplicatePack(identifier: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { store.duplicatePack(identifier) }
            }
            result.onSuccess { duplicated ->
                val msg = if (duplicated != null) {
                    "Pack duplicado como \"${duplicated.name}\"."
                } else {
                    "No se encontró el pack a duplicar."
                }
                _uiState.update { it.copy(message = msg) }
                refresh()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(message = error.message ?: "Error al duplicar el pack.")
                }
            }
        }
    }

    fun deletePack(identifier: String) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                store.deletePack(identifier)
            }
            _uiState.update {
                it.copy(
                    message = if (deleted) "Pack eliminado." else "No se pudo eliminar el pack.",
                )
            }
            refresh()
        }
    }

    fun exportPackToUri(identifier: String, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    exportPacksArchiveToUri(
                        context = getApplication(),
                        store = store,
                        uri = uri,
                        packIdentifiers = listOf(identifier),
                    )
                }
            }
            result.onSuccess {
                _uiState.update { it.copy(message = "Pack exportado correctamente en .zip.") }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(message = error.message ?: "No se pudo exportar el pack.")
                }
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun checkForUpdatesManually() {
        val currentUpdate = _uiState.value.updateState
        if (currentUpdate.isChecking || currentUpdate.isDownloading) return

        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    updateState = state.updateState.copy(
                        isChecking = true,
                        statusMessage = null,
                        errorMessage = null,
                        pendingConfirmationRelease = null,
                    ),
                )
            }

            val versionName = _uiState.value.updateState.currentVersionName
            val result = runCatching { GitHubUpdateClient.fetchLatestUpdate(versionName) }
            result.onSuccess { release ->
                _uiState.update { state ->
                    if (release != null) {
                        state.copy(
                            updateState = state.updateState.copy(
                                isChecking = false,
                                availableRelease = release,
                                pendingConfirmationRelease = release,
                            ),
                        )
                    } else {
                        state.copy(
                            updateState = state.updateState.copy(
                                isChecking = false,
                                availableRelease = null,
                                pendingConfirmationRelease = null,
                                statusMessage = "Ya tienes la última versión ($versionName).",
                            ),
                        )
                    }
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        updateState = state.updateState.copy(
                            isChecking = false,
                            errorMessage = error.message ?: "No se pudo comprobar si hay actualizaciones.",
                        ),
                    )
                }
            }
        }
    }

    fun dismissPendingConfirmation() {
        _uiState.update { state ->
            state.copy(
                updateState = state.updateState.copy(pendingConfirmationRelease = null),
            )
        }
    }

    fun startOrResumeUpdateInstallation() {
        val updateState = _uiState.value.updateState
        if (updateState.isDownloading) return
        val release = updateState.pendingConfirmationRelease ?: updateState.availableRelease ?: return

        val app = getApplication<Application>()
        if (!ApkInstaller.canInstallPackages(app)) {
            _uiState.update { state ->
                state.copy(
                    updateState = state.updateState.copy(
                        needsInstallPermissionPrompt = true,
                        errorMessage = null,
                    ),
                )
            }
            return
        }

        downloadAndInstallRelease(release)
    }

    fun dismissInstallPermissionPrompt() {
        _uiState.update { state ->
            state.copy(
                updateState = state.updateState.copy(needsInstallPermissionPrompt = false),
            )
        }
    }

    fun onUnknownSourcesSettingsReturned() {
        val app = getApplication<Application>()
        if (ApkInstaller.canInstallPackages(app)) {
            _uiState.update { state ->
                state.copy(
                    updateState = state.updateState.copy(needsInstallPermissionPrompt = false),
                )
            }
            startOrResumeUpdateInstallation()
        } else {
            _uiState.update { state ->
                state.copy(
                    updateState = state.updateState.copy(
                        needsInstallPermissionPrompt = false,
                        errorMessage = "Permiso necesario para instalar la actualización.",
                    ),
                )
            }
        }
    }

    fun onAboutDialogClosed() {
        _uiState.update { state ->
            state.copy(
                updateState = state.updateState.copy(
                    pendingConfirmationRelease = null,
                    statusMessage = null,
                    errorMessage = null,
                    needsInstallPermissionPrompt = false,
                ),
            )
        }
    }

    private fun checkForUpdatesOnStartup() {
        viewModelScope.launch {
            val versionName = _uiState.value.updateState.currentVersionName
            val release = runCatching { GitHubUpdateClient.fetchLatestUpdate(versionName) }.getOrNull()
            if (release != null) {
                _uiState.update { state ->
                    state.copy(
                        updateState = state.updateState.copy(availableRelease = release),
                    )
                }
            }
        }
    }

    private fun downloadAndInstallRelease(release: UpdateReleaseInfo) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            _uiState.update { state ->
                state.copy(
                    updateState = state.updateState.copy(
                        needsInstallPermissionPrompt = false,
                        pendingConfirmationRelease = null,
                        isDownloading = true,
                        downloadProgressPercent = 0,
                        statusMessage = null,
                        errorMessage = null,
                    ),
                )
            }

            val updatesDir = File(app.cacheDir, "updates")
            val downloadResult = runCatching {
                GitHubUpdateClient.downloadApk(
                    release = release,
                    updatesDir = updatesDir,
                    onProgress = { percent ->
                        _uiState.update { state ->
                            state.copy(
                                updateState = state.updateState.copy(downloadProgressPercent = percent),
                            )
                        }
                    },
                )
            }

            downloadResult.onSuccess { apkFile ->
                _uiState.update { state ->
                    state.copy(
                        updateState = state.updateState.copy(
                            isDownloading = false,
                            downloadProgressPercent = null,
                        ),
                    )
                }
                val launchResult = ApkInstaller.launchApkInstaller(app, apkFile)
                launchResult.onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            updateState = state.updateState.copy(
                                errorMessage = error.message ?: "No se pudo abrir el instalador de Android.",
                            ),
                        )
                    }
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        updateState = state.updateState.copy(
                            isDownloading = false,
                            downloadProgressPercent = null,
                            errorMessage = error.message ?: "No se pudo descargar la actualización.",
                        ),
                    )
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "yanistickers_ui_prefs"
        private const val KEY_SORT_OPTION = "pack_sort_option"
        private const val PREVIEW_STICKER_COUNT = 5

        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PackListViewModel(application) as T
                }
            }
    }
}
