package io.github.danivi.yanistickers.data

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Gestiona la lectura y operaciones CRUD de los packs en `filesDir/packs/`.
 */
class LocalPackStore(
    private val packsRootDir: File,
    private val defaultTrayWriter: (destFile: File, packName: String, animated: Boolean) -> String = { dest, _, animated ->
        DefaultTrayGenerator.writeFallbackPngTray(dest, animated)
        dest.name
    },
) {

    constructor(context: Context) : this(
        packsRootDir = File(context.filesDir, PACKS_DIR_NAME),
        defaultTrayWriter = { dest, packName, animated ->
            DefaultTrayGenerator.writeAndroidWebpTray(dest, packName, animated)
            dest.name
        },
    )

    data class LoadedPack(
        val directory: File,
        val pack: StickerPack,
    )

    data class ImportedPackInstallResult(
        val outcome: ImportOutcome,
        val pack: StickerPack,
        val directory: File?,
    )

    fun listPacks(): List<StickerPack> = synchronized(STORE_LOCK) {
        loadAll().map { it.pack }
    }

    fun listLoadedPacks(): List<LoadedPack> = synchronized(STORE_LOCK) {
        loadAll()
    }

    fun getPack(identifier: String): StickerPack? = synchronized(STORE_LOCK) {
        findLoadedPack(identifier)?.pack
    }

    fun getPackDirectory(identifier: String): File? = synchronized(STORE_LOCK) {
        findLoadedPack(identifier)?.directory
    }

    /**
     * Resuelve el icono de bandeja o un sticker para [identifier] y [assetFileName], asegurando
     * que el archivo pertenezca al pack y no salga de su carpeta.
     */
    fun resolveAssetFile(identifier: String, assetFileName: String): File? = synchronized(STORE_LOCK) {
        if (!PackJsonParser.isSafeIdentifier(identifier)) return null
        if (!PackJsonParser.isSafeSingleFileName(assetFileName)) return null

        val loaded = findLoadedPack(identifier) ?: return null
        val pack = loaded.pack
        val packDir = loaded.directory

        val relativePath = when {
            assetFileName == pack.trayImageFile -> pack.trayImageFile
            pack.stickers.any { PackJsonParser.extractStickerAssetName(it.file) == assetFileName } ->
                "stickers/$assetFileName"
            else -> return null
        }

        val candidate = File(packDir, relativePath)
        val canonicalRoot = packDir.canonicalPath
        val canonicalTarget = candidate.canonicalPath
        if (!canonicalTarget.startsWith("$canonicalRoot${File.separator}")) return null
        return candidate.takeIf { it.isFile }
    }

    /**
     * Crea un pack vacío nuevo con identificador estable e icono de bandeja inicial.
     */
    fun createPack(
        name: String,
        publisher: String,
        animated: Boolean,
        customIdentifier: String? = null,
    ): StickerPack = synchronized(STORE_LOCK) {
        ensureRootExists()
        val existingIds = loadAll().map { it.pack.identifier }.toSet()
        val identifier = if (!customIdentifier.isNullOrBlank()) {
            val trimmed = customIdentifier.trim()
            require(PackJsonParser.isSafeIdentifier(trimmed)) {
                "Identificador inválido: '$trimmed'"
            }
            require(trimmed !in existingIds) {
                "Ya existe un pack con el identificador '$trimmed'"
            }
            trimmed
        } else {
            PackJsonParser.generateSafeIdentifier(name, existingIds)
        }

        val packDir = File(packsRootDir, identifier)
        val stickersDir = File(packDir, STICKERS_DIR_NAME)
        check(stickersDir.mkdirs() || stickersDir.isDirectory) {
            "No se pudo crear la carpeta del pack '$identifier'."
        }

        val trayCandidate = File(packDir, DEFAULT_TRAY_WEBP)
        val trayFileName = defaultTrayWriter(trayCandidate, name.trim(), animated)
        if (trayFileName != trayCandidate.name && trayCandidate.exists()) {
            trayCandidate.delete()
        }

        val now = nowIsoUtc()
        val pack = StickerPack(
            schemaVersion = PackJsonParser.SCHEMA_VERSION,
            identifier = identifier,
            name = name.trim(),
            publisher = publisher.trim(),
            animated = animated,
            imageDataVersion = "1",
            trayImageFile = trayFileName,
            stickers = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        writePackJsonAtomically(packDir, pack)
        pack
    }

    /**
     * Duplica un pack existente con un nuevo `identifier` único y una copia completa de sus archivos.
     */
    fun duplicatePack(
        sourceIdentifier: String,
        newName: String? = null,
    ): StickerPack? = synchronized(STORE_LOCK) {
        val source = findLoadedPack(sourceIdentifier) ?: return null
        val existingIds = loadAll().map { it.pack.identifier }.toSet()
        val targetName = (newName?.trim()?.takeIf { it.isNotEmpty() } ?: "${source.pack.name} (copia)")
            .take(PackJsonParser.MAX_IDENTIFIER_LENGTH)
        val newIdentifier = PackJsonParser.generateSafeIdentifier(targetName, existingIds)

        val destDir = File(packsRootDir, newIdentifier)
        source.directory.copyRecursively(destDir, overwrite = true)

        val now = nowIsoUtc()
        val duplicated = source.pack.copy(
            identifier = newIdentifier,
            name = targetName,
            imageDataVersion = "1",
            createdAt = now,
            updatedAt = now,
        )
        writePackJsonAtomically(destDir, duplicated)
        duplicated
    }

    /**
     * Actualiza nombre y autor manteniendo el `identifier` estable e incrementando `imageDataVersion`.
     */
    fun updateMetadata(
        identifier: String,
        name: String,
        publisher: String,
    ): StickerPack? = synchronized(STORE_LOCK) {
        val loaded = findLoadedPack(identifier) ?: return null
        val updated = loaded.pack.withNextEditVersion(
            name = name.trim(),
            publisher = publisher.trim(),
        )
        writePackJsonAtomically(loaded.directory, updated)
        updated
    }

    /**
     * Edita los emojis y el texto de accesibilidad de un sticker concreto e incrementa versión.
     */
    fun updateSticker(
        identifier: String,
        stickerIndex: Int,
        emojis: List<String>,
        accessibilityText: String,
    ): StickerPack? = synchronized(STORE_LOCK) {
        val loaded = findLoadedPack(identifier) ?: return null
        if (stickerIndex !in loaded.pack.stickers.indices) return null

        val cleanEmojis = emojis.map { it.trim() }.filter { it.isNotEmpty() }
        val updatedStickers = loaded.pack.stickers.toMutableList().apply {
            this[stickerIndex] = this[stickerIndex].copy(
                emojis = cleanEmojis,
                accessibilityText = accessibilityText.trim(),
            )
        }
        val updated = loaded.pack.withNextEditVersion(stickers = updatedStickers)
        writePackJsonAtomically(loaded.directory, updated)
        updated
    }

    /**
     * Reordena un sticker dentro del pack e incrementa `imageDataVersion`.
     */
    fun reorderStickers(
        identifier: String,
        fromIndex: Int,
        toIndex: Int,
    ): StickerPack? = synchronized(STORE_LOCK) {
        val loaded = findLoadedPack(identifier) ?: return null
        val size = loaded.pack.stickers.size
        if (fromIndex !in 0 until size || toIndex !in 0 until size || fromIndex == toIndex) {
            return loaded.pack
        }
        val reordered = loaded.pack.stickers.toMutableList().apply {
            val item = removeAt(fromIndex)
            add(toIndex, item)
        }
        val updated = loaded.pack.withNextEditVersion(stickers = reordered)
        writePackJsonAtomically(loaded.directory, updated)
        updated
    }

    /**
     * Elimina un sticker del pack, borra su archivo si no queda referenciado e incrementa versión.
     */
    fun removeSticker(
        identifier: String,
        stickerIndex: Int,
    ): StickerPack? = synchronized(STORE_LOCK) {
        val loaded = findLoadedPack(identifier) ?: return null
        if (stickerIndex !in loaded.pack.stickers.indices) return null

        val remaining = loaded.pack.stickers.toMutableList()
        val removed = remaining.removeAt(stickerIndex)
        val updated = loaded.pack.withNextEditVersion(stickers = remaining)
        writePackJsonAtomically(loaded.directory, updated)

        val assetName = PackJsonParser.extractStickerAssetName(removed.file)
        if (assetName != null && remaining.none { PackJsonParser.extractStickerAssetName(it.file) == assetName }) {
            val target = resolveInside(loaded.directory, "$STICKERS_DIR_NAME/$assetName")
            if (target != null && target.isFile) {
                target.delete()
            }
        }
        updated
    }

    /**
     * Elimina por completo la carpeta del pack en `filesDir/packs/`.
     */
    fun deletePack(identifier: String): Boolean = synchronized(STORE_LOCK) {
        val loaded = findLoadedPack(identifier) ?: return false
        val canonicalRoot = packsRootDir.canonicalPath
        val canonicalPack = loaded.directory.canonicalPath
        if (!canonicalPack.startsWith("$canonicalRoot${File.separator}")) return false
        loaded.directory.deleteRecursively()
    }

    /**
     * Instala en `filesDir/packs/` un pack validado previamente en [stagedDir], aplicando
     * [conflictResolution] si ya existe otro pack con el mismo `identifier`.
     */
    fun installImportedPack(
        stagedDir: File,
        importedPack: StickerPack,
        conflictResolution: ConflictResolution? = null,
    ): ImportedPackInstallResult = synchronized(STORE_LOCK) {
        ensureRootExists()
        require(PackJsonParser.isSafeIdentifier(importedPack.identifier)) {
            "Identificador de pack inválido: '${importedPack.identifier}'."
        }

        val existing = findLoadedPack(importedPack.identifier)
        if (existing != null && (conflictResolution == null || conflictResolution == ConflictResolution.SKIP)) {
            return@synchronized ImportedPackInstallResult(
                outcome = ImportOutcome.SKIPPED,
                pack = existing.pack,
                directory = existing.directory,
            )
        }

        val existingIds = loadAll().map { it.pack.identifier }.toSet()
        val outcome: ImportOutcome
        val finalPack: StickerPack

        if (existing == null) {
            outcome = ImportOutcome.IMPORTED
            finalPack = importedPack.copy(
                schemaVersion = PackJsonParser.SCHEMA_VERSION,
                imageDataVersion = importedPack.imageDataVersion.ifBlank { "1" },
            )
        } else if (conflictResolution == ConflictResolution.DUPLICATE_NEW_ID) {
            outcome = ImportOutcome.DUPLICATED
            val newId = PackJsonParser.generateSafeIdentifier(importedPack.name, existingIds)
            val now = nowIsoUtc()
            finalPack = importedPack.copy(
                schemaVersion = PackJsonParser.SCHEMA_VERSION,
                identifier = newId,
                imageDataVersion = "1",
                createdAt = now,
                updatedAt = now,
            )
        } else {
            outcome = ImportOutcome.REPLACED
            val existingVersion = existing.pack.imageDataVersion.toIntOrNull() ?: 1
            val importedVersion = importedPack.imageDataVersion.toIntOrNull() ?: 1
            val nextVersion = (maxOf(existingVersion, importedVersion) + 1).toString()
            finalPack = importedPack.copy(
                schemaVersion = PackJsonParser.SCHEMA_VERSION,
                imageDataVersion = nextVersion,
                updatedAt = nowIsoUtc(),
            )
        }

        val targetDir = resolveInside(packsRootDir, finalPack.identifier)
            ?: error("Ruta de destino inválida para '${finalPack.identifier}'.")

        val tempImportDir = File(packsRootDir, ".importing_${finalPack.identifier}_${System.nanoTime()}")
        if (tempImportDir.exists()) {
            tempImportDir.deleteRecursively()
        }

        try {
            copyCleanPackAssets(stagedDir = stagedDir, destDir = tempImportDir, pack = finalPack)
            writePackJsonAtomically(tempImportDir, finalPack)

            if (existing != null && outcome == ImportOutcome.REPLACED) {
                val backupDir = File(packsRootDir, ".backup_${finalPack.identifier}_${System.nanoTime()}")
                if (!existing.directory.renameTo(backupDir)) {
                    existing.directory.copyRecursively(backupDir, overwrite = true)
                    existing.directory.deleteRecursively()
                }
                try {
                    moveOrCopyDirectory(tempImportDir, targetDir)
                    backupDir.deleteRecursively()
                } catch (t: Throwable) {
                    if (!targetDir.exists() && backupDir.exists()) {
                        moveOrCopyDirectory(backupDir, targetDir)
                    }
                    throw t
                }
            } else {
                moveOrCopyDirectory(tempImportDir, targetDir)
            }
        } finally {
            if (tempImportDir.exists()) {
                tempImportDir.deleteRecursively()
            }
        }

        ImportedPackInstallResult(
            outcome = outcome,
            pack = finalPack,
            directory = targetDir,
        )
    }

    private fun copyCleanPackAssets(stagedDir: File, destDir: File, pack: StickerPack) {
        val stickersDestDir = File(destDir, STICKERS_DIR_NAME)
        check(stickersDestDir.mkdirs() || stickersDestDir.isDirectory) {
            "No se pudo crear la carpeta de stickers para '${pack.identifier}'."
        }

        val trayName = pack.trayImageFile
        require(PackJsonParser.isSafeSingleFileName(trayName)) {
            "Nombre de icono de bandeja inválido: '$trayName'."
        }
        val sourceTray = resolveInside(stagedDir, trayName)
        require(sourceTray != null && sourceTray.isFile) {
            "Falta el icono de bandeja '$trayName' en el paquete."
        }
        val destTray = resolveInside(destDir, trayName)
            ?: error("Ruta de destino inválida para el icono '$trayName'.")
        sourceTray.copyTo(destTray, overwrite = true)

        val seenStickers = mutableSetOf<String>()
        for (sticker in pack.stickers) {
            val assetName = PackJsonParser.extractStickerAssetName(sticker.file)
                ?: throw IllegalArgumentException("Ruta de sticker inválida: '${sticker.file}'.")
            if (!seenStickers.add(assetName)) continue

            val sourceSticker = resolveInside(stagedDir, "$STICKERS_DIR_NAME/$assetName")
            require(sourceSticker != null && sourceSticker.isFile) {
                "Falta el archivo de sticker '$assetName' en el paquete."
            }
            val destSticker = resolveInside(destDir, "$STICKERS_DIR_NAME/$assetName")
                ?: error("Ruta de destino inválida para '$assetName'.")
            sourceSticker.copyTo(destSticker, overwrite = true)
        }
    }

    private fun moveOrCopyDirectory(sourceDir: File, targetDir: File) {
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        if (!sourceDir.renameTo(targetDir)) {
            sourceDir.copyRecursively(targetDir, overwrite = true)
            sourceDir.deleteRecursively()
        }
    }

    private fun StickerPack.withNextEditVersion(
        name: String = this.name,
        publisher: String = this.publisher,
        stickers: List<StickerItem> = this.stickers,
    ): StickerPack {
        val nextVersion = ((imageDataVersion.toIntOrNull() ?: 1) + 1).toString()
        return copy(
            name = name,
            publisher = publisher,
            imageDataVersion = nextVersion,
            stickers = stickers,
            updatedAt = nowIsoUtc(),
        )
    }

    private fun writePackJsonAtomically(packDir: File, pack: StickerPack) {
        val json = PackJsonParser.toJson(pack)
        val tempFile = File(packDir, "$PACK_JSON_FILE.tmp")
        val targetFile = File(packDir, PACK_JSON_FILE)
        tempFile.writeText(json, Charsets.UTF_8)
        if (!tempFile.renameTo(targetFile)) {
            targetFile.writeText(json, Charsets.UTF_8)
            tempFile.delete()
        }
    }

    private fun ensureRootExists() {
        if (!packsRootDir.exists()) {
            packsRootDir.mkdirs()
        }
    }

    private fun resolveInside(packDir: File, relativePath: String): File? {
        val candidate = File(packDir, relativePath)
        val canonicalRoot = packDir.canonicalPath
        val canonicalTarget = candidate.canonicalPath
        if (!canonicalTarget.startsWith("$canonicalRoot${File.separator}")) return null
        return candidate
    }

    private fun loadAll(): List<LoadedPack> {
        if (!packsRootDir.isDirectory) return emptyList()
        val dirs = packsRootDir.listFiles { file ->
            file.isDirectory && !file.name.startsWith(".")
        } ?: return emptyList()
        return dirs
            .sortedBy { it.name }
            .mapNotNull { dir -> readPackFromDir(dir) }
    }

    private fun findLoadedPack(identifier: String): LoadedPack? {
        if (!PackJsonParser.isSafeIdentifier(identifier)) return null
        val directDir = File(packsRootDir, identifier)
        readPackFromDir(directDir)?.takeIf { it.pack.identifier == identifier }?.let { return it }
        return loadAll().firstOrNull { it.pack.identifier == identifier }
    }

    private fun readPackFromDir(dir: File): LoadedPack? {
        val packFile = File(dir, PACK_JSON_FILE)
        if (!packFile.isFile) return null
        return runCatching {
            val pack = PackJsonParser.parse(packFile.readText(Charsets.UTF_8))
            LoadedPack(directory = dir, pack = pack)
        }.getOrNull()
    }

    private fun nowIsoUtc(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    companion object {
        const val PACKS_DIR_NAME = "packs"
        const val STICKERS_DIR_NAME = "stickers"
        const val PACK_JSON_FILE = "pack.json"
        private const val DEFAULT_TRAY_WEBP = "tray.webp"
        private val STORE_LOCK = Any()
    }
}
