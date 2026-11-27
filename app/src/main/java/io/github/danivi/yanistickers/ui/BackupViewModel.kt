package io.github.danivi.yanistickers.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.danivi.yanistickers.data.BackupException
import io.github.danivi.yanistickers.data.BackupManifest
import io.github.danivi.yanistickers.data.ConflictResolution
import io.github.danivi.yanistickers.data.ImportOutcome
import io.github.danivi.yanistickers.data.LocalPackStore
import io.github.danivi.yanistickers.data.PackBackupManager
import io.github.danivi.yanistickers.data.StagedBackup
import io.github.danivi.yanistickers.data.StickerPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

data class BackupPackItemUi(
    val pack: StickerPack,
    val trayFile: File?,
)

data class BackupUiState(
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val packs: List<BackupPackItemUi> = emptyList(),
    val pendingStagedBackup: StagedBackup? = null,
    val conflictResolutions: Map<String, ConflictResolution> = emptyMap(),
    val message: String? = null,
)

/**
 * ViewModel de la pantalla de copia de seguridad y restauración `.zip`.
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val store = LocalPackStore(application)
    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.pendingStagedBackup?.stagingRootDir?.deleteRecursively()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val loadedPacks = withContext(Dispatchers.IO) {
                loadPackItems()
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    packs = loadedPacks,
                )
            }
        }
    }

    fun suggestedAllBackupFileName(): String {
        val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        return "yanistickers_backup_$date.zip"
    }

    fun suggestedSingleBackupFileName(pack: StickerPack): String {
        return "yanistickers_${pack.identifier}.zip"
    }

    fun exportToUri(uri: Uri, packIdentifiers: List<String>? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    exportPacksArchiveToUri(
                        context = getApplication(),
                        store = store,
                        uri = uri,
                        packIdentifiers = packIdentifiers,
                    )
                }
            }
            result.onSuccess { manifest ->
                val msg = if (manifest.packs.size == 1) {
                    "Pack exportado correctamente en .zip."
                } else {
                    "Copia guardada con ${manifest.packs.size} packs."
                }
                _uiState.update { it.copy(isBusy = false, message = msg) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        message = error.message ?: "No se pudo exportar la copia de seguridad.",
                    )
                }
            }
        }
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.value.pendingStagedBackup?.stagingRootDir?.deleteRecursively()
            _uiState.update {
                it.copy(
                    isBusy = true,
                    pendingStagedBackup = null,
                    conflictResolutions = emptyMap(),
                )
            }

            val stageResult = withContext(Dispatchers.IO) {
                runCatching {
                    val app = getApplication<Application>()
                    val stagingDir = File(app.cacheDir, "backup_import_${UUID.randomUUID()}")
                    val input = app.contentResolver.openInputStream(uri)
                        ?: throw BackupException("No se pudo abrir el archivo seleccionado.")
                    input.use { stream ->
                        PackBackupManager.stageBackup(store, stream, stagingDir)
                    }
                }
            }

            stageResult.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        message = error.message ?: "No se pudo leer el archivo .zip.",
                    )
                }
                return@launch
            }

            val staged = stageResult.getOrThrow()
            if (staged.conflicts.isEmpty()) {
                commitBackup(staged, emptyMap())
            } else {
                val defaultResolutions = staged.conflicts.associate { entry ->
                    entry.pack.identifier to ConflictResolution.DUPLICATE_NEW_ID
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        pendingStagedBackup = staged,
                        conflictResolutions = defaultResolutions,
                    )
                }
            }
        }
    }

    fun setConflictResolution(packId: String, resolution: ConflictResolution) {
        _uiState.update { current ->
            current.copy(
                conflictResolutions = current.conflictResolutions + (packId to resolution),
            )
        }
    }

    fun setAllConflictResolutions(resolution: ConflictResolution) {
        _uiState.update { current ->
            val conflicts = current.pendingStagedBackup?.conflicts.orEmpty()
            current.copy(
                conflictResolutions = conflicts.associate { it.pack.identifier to resolution },
            )
        }
    }

    fun confirmConflictImport() {
        val staged = _uiState.value.pendingStagedBackup ?: return
        val resolutions = _uiState.value.conflictResolutions
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            commitBackup(staged, resolutions)
        }
    }

    fun cancelConflictImport() {
        val staged = _uiState.value.pendingStagedBackup ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                staged.stagingRootDir.deleteRecursively()
            }
            _uiState.update {
                it.copy(
                    pendingStagedBackup = null,
                    conflictResolutions = emptyMap(),
                    message = "Importación cancelada.",
                )
            }
        }
    }

    private suspend fun commitBackup(
        staged: StagedBackup,
        resolutions: Map<String, ConflictResolution>,
    ) {
        val commitResult = withContext(Dispatchers.IO) {
            runCatching {
                val summary = PackBackupManager.commitStagedBackup(store, staged, resolutions)
                val packs = loadPackItems()
                summary to packs
            }
        }

        commitResult.onSuccess { (summary, packs) ->
            val installedCount = summary.results.count { it.outcome != ImportOutcome.SKIPPED }
            val skippedCount = summary.results.count { it.outcome == ImportOutcome.SKIPPED }
            val msg = buildString {
                append("Importación completada: $installedCount pack(s)")
                if (skippedCount > 0) {
                    append(" ($skippedCount omitido(s))")
                }
                append(".")
            }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    packs = packs,
                    pendingStagedBackup = null,
                    conflictResolutions = emptyMap(),
                    message = msg,
                )
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    isBusy = false,
                    pendingStagedBackup = null,
                    conflictResolutions = emptyMap(),
                    message = error.message ?: "Error al instalar la copia de seguridad.",
                )
            }
        }
    }

    private fun loadPackItems(): List<BackupPackItemUi> {
        return store.listPacks().map { pack ->
            BackupPackItemUi(
                pack = pack,
                trayFile = store.resolveAssetFile(pack.identifier, pack.trayImageFile),
            )
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return BackupViewModel(application) as T
                }
            }
    }
}

/**
 * Escribe un `.zip` temporalmente en caché y lo vuelca en [uri] para evitar archivos SAF corruptos
 * si falla la exportación.
 */
internal fun exportPacksArchiveToUri(
    context: Context,
    store: LocalPackStore,
    uri: Uri,
    packIdentifiers: List<String>? = null,
): BackupManifest {
    val tempZip = File(context.cacheDir, "export_${UUID.randomUUID()}.zip")
    return try {
        val manifest = tempZip.outputStream().use { fos ->
            PackBackupManager.exportToZip(store, fos, packIdentifiers)
        }
        val outStream = context.contentResolver.openOutputStream(uri)
            ?: throw BackupException("No se pudo abrir el archivo de destino para escribir.")
        outStream.use { output ->
            tempZip.inputStream().use { input ->
                input.copyTo(output)
            }
        }
        manifest
    } finally {
        tempZip.delete()
    }
}
