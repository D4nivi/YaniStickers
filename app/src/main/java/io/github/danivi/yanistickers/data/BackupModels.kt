package io.github.danivi.yanistickers.data

import io.github.danivi.yanistickers.validation.PackValidationReport
import java.io.File

/**
 * Estrategia para resolver conflictos cuando un `.zip` importado contiene un `identifier`
 * que ya existe en el almacenamiento local.
 */
enum class ConflictResolution {
    REPLACE,
    DUPLICATE_NEW_ID,
    SKIP,
}

/**
 * Resultado de la instalación de un pack individual durante la importación.
 */
enum class ImportOutcome {
    IMPORTED,
    REPLACED,
    DUPLICATED,
    SKIPPED,
}

/**
 * Contenido del archivo `manifest.json` en la raíz del `.zip` de copia de seguridad.
 */
data class BackupManifest(
    val schemaVersion: Int,
    val packs: List<String>,
    val exportedAt: String,
)

/**
 * Pack extraído y validado estructuralmente en una carpeta temporal previa a su instalación.
 */
data class StagedPackEntry(
    val pack: StickerPack,
    val stagedDir: File,
    val validation: PackValidationReport,
    val existingPack: StickerPack?,
) {
    val hasConflict: Boolean
        get() = existingPack != null
}

/**
 * Resultado completo de inspeccionar y extraer un `.zip` de backup en staging.
 */
data class StagedBackup(
    val manifest: BackupManifest,
    val stagingRootDir: File,
    val entries: List<StagedPackEntry>,
) {
    val conflicts: List<StagedPackEntry>
        get() = entries.filter { it.hasConflict }
}

/**
 * Resultado por pack tras confirmar la importación en `LocalPackStore`.
 */
data class ImportedPackResult(
    val outcome: ImportOutcome,
    val pack: StickerPack,
    val trayFile: File?,
    val validation: PackValidationReport,
)

/**
 * Resumen final de una operación de importación.
 */
data class ImportSummary(
    val exportedAt: String,
    val results: List<ImportedPackResult>,
) {
    val installedPacks: List<ImportedPackResult>
        get() = results.filter { it.outcome != ImportOutcome.SKIPPED }
}
