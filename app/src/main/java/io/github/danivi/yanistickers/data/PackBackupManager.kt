package io.github.danivi.yanistickers.data

import io.github.danivi.yanistickers.validation.PackValidator
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Error controlado durante la exportación o importación de copias de seguridad `.zip`.
 */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Gestiona la exportación e importación de packs en formato `.zip` con validación de esquema,
 * protección frente a *zip-slip* y resolución de conflictos de `identifier`.
 */
object PackBackupManager {

    const val SCHEMA_VERSION = 1
    const val MANIFEST_FILE = "manifest.json"
    private const val MAX_ZIP_ENTRIES = 2_000
    private const val MAX_UNCOMPRESSED_BYTES = 250L * 1024L * 1024L
    private const val BUFFER_SIZE = 8 * 1024

    /**
     * Empaqueta todos los packs (o los indicados en [packIdentifiers]) en [outputStream].
     * No cierra [outputStream] para permitir que el llamador gestione su ciclo de vida.
     */
    fun exportToZip(
        store: LocalPackStore,
        outputStream: OutputStream,
        packIdentifiers: List<String>? = null,
    ): BackupManifest {
        val allLoaded = store.listLoadedPacks()
        val selectedPacks = if (packIdentifiers == null) {
            allLoaded
        } else {
            val byId = allLoaded.associateBy { it.pack.identifier }
            packIdentifiers.map { id ->
                byId[id] ?: throw BackupException("No se encontró el pack '$id' para exportar.")
            }
        }

        if (selectedPacks.isEmpty()) {
            throw BackupException("No hay packs disponibles para exportar.")
        }

        val packExports = selectedPacks.map { loaded -> preparePackExport(loaded) }
        val manifest = BackupManifest(
            schemaVersion = SCHEMA_VERSION,
            packs = packExports.map { it.pack.identifier },
            exportedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        )

        val zos = ZipOutputStream(BufferedOutputStream(outputStream))
        writeTextEntry(zos, MANIFEST_FILE, manifestToJson(manifest))

        for (prepared in packExports) {
            val id = prepared.pack.identifier
            writeTextEntry(zos, "$id/${LocalPackStore.PACK_JSON_FILE}", PackJsonParser.toJson(prepared.pack))
            writeFileEntry(zos, "$id/${prepared.pack.trayImageFile}", prepared.trayFile)
            for ((assetName, file) in prepared.stickerFiles) {
                writeFileEntry(zos, "$id/${LocalPackStore.STICKERS_DIR_NAME}/$assetName", file)
            }
        }

        zos.finish()
        zos.flush()
        return manifest
    }

    /**
     * Extrae y valida un `.zip` en [stagingRootDir] sin modificar aún los packs instalados.
     * Si ocurre cualquier error de formato, esquema o seguridad (*zip-slip*), elimina [stagingRootDir].
     */
    fun stageBackup(
        store: LocalPackStore,
        inputStream: InputStream,
        stagingRootDir: File,
    ): StagedBackup {
        if (stagingRootDir.exists()) {
            stagingRootDir.deleteRecursively()
        }
        if (!stagingRootDir.mkdirs() && !stagingRootDir.isDirectory) {
            throw BackupException("No se pudo preparar la carpeta temporal para importar.")
        }

        try {
            extractZipSafely(inputStream, stagingRootDir)

            val manifestFile = resolveStagingPath(stagingRootDir, MANIFEST_FILE)
                ?: throw BackupException("Ruta inválida para $MANIFEST_FILE.")
            if (!manifestFile.isFile) {
                throw BackupException(
                    "El archivo seleccionado no es una copia de seguridad válida (falta $MANIFEST_FILE).",
                )
            }

            val manifest = parseAndValidateManifest(manifestFile.readText(Charsets.UTF_8))
            val existingById = store.listPacks().associateBy { it.identifier }

            val entries = manifest.packs.map { packId ->
                stageSinglePack(
                    packId = packId,
                    stagingRootDir = stagingRootDir,
                    existingPack = existingById[packId],
                )
            }

            return StagedBackup(
                manifest = manifest,
                stagingRootDir = stagingRootDir,
                entries = entries,
            )
        } catch (e: BackupException) {
            stagingRootDir.deleteRecursively()
            throw e
        } catch (t: Throwable) {
            stagingRootDir.deleteRecursively()
            throw BackupException("No se pudo leer la copia de seguridad: ${t.message ?: "archivo inválido"}", t)
        }
    }

    /**
     * Instala en [store] los packs preparados en [staged] aplicando [resolutions] sobre los conflictos
     * y limpia la carpeta temporal al finalizar.
     */
    fun commitStagedBackup(
        store: LocalPackStore,
        staged: StagedBackup,
        resolutions: Map<String, ConflictResolution>,
    ): ImportSummary {
        try {
            val results = staged.entries.map { entry ->
                val resolution = if (entry.hasConflict) {
                    resolutions[entry.pack.identifier] ?: ConflictResolution.SKIP
                } else {
                    null
                }
                val installed = store.installImportedPack(
                    stagedDir = entry.stagedDir,
                    importedPack = entry.pack,
                    conflictResolution = resolution,
                )
                val finalValidation = if (installed.outcome == ImportOutcome.SKIPPED) {
                    entry.validation
                } else {
                    PackValidator.validate(store, installed.pack)
                }
                val finalTray = if (installed.outcome == ImportOutcome.SKIPPED) {
                    null
                } else {
                    store.resolveAssetFile(installed.pack.identifier, installed.pack.trayImageFile)
                }
                ImportedPackResult(
                    outcome = installed.outcome,
                    pack = installed.pack,
                    trayFile = finalTray,
                    validation = finalValidation,
                )
            }
            return ImportSummary(
                exportedAt = staged.manifest.exportedAt,
                results = results,
            )
        } finally {
            staged.stagingRootDir.deleteRecursively()
        }
    }

    internal fun manifestToJson(manifest: BackupManifest): String {
        val packsArray = JSONArray()
        manifest.packs.forEach { packsArray.put(it) }
        return JSONObject()
            .put("schemaVersion", manifest.schemaVersion)
            .put("packs", packsArray)
            .put("exportedAt", manifest.exportedAt)
            .toString(2)
    }

    private fun parseAndValidateManifest(jsonText: String): BackupManifest {
        val root = runCatching { JSONObject(jsonText) }.getOrElse {
            throw BackupException("El archivo $MANIFEST_FILE no contiene un JSON válido.", it)
        }

        if (!root.has("schemaVersion")) {
            throw BackupException("Falta el campo 'schemaVersion' en $MANIFEST_FILE.")
        }
        val schemaVersion = root.optInt("schemaVersion", -1)
        if (schemaVersion != SCHEMA_VERSION) {
            throw BackupException("Versión de copia de seguridad no compatible: $schemaVersion.")
        }

        val packsArray = root.optJSONArray("packs")
            ?: throw BackupException("Falta la lista 'packs' en $MANIFEST_FILE.")
        if (packsArray.length() == 0) {
            throw BackupException("La copia de seguridad no contiene ningún pack.")
        }

        val packs = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (i in 0 until packsArray.length()) {
            val packId = packsArray.optString(i, "").trim()
            if (!PackJsonParser.isSafeIdentifier(packId)) {
                throw BackupException("Identificador de pack inválido en $MANIFEST_FILE: '$packId'.")
            }
            if (!seen.add(packId)) {
                throw BackupException("Identificador duplicado en $MANIFEST_FILE: '$packId'.")
            }
            packs += packId
        }

        return BackupManifest(
            schemaVersion = schemaVersion,
            packs = packs,
            exportedAt = root.optString("exportedAt", ""),
        )
    }

    private fun stageSinglePack(
        packId: String,
        stagingRootDir: File,
        existingPack: StickerPack?,
    ): StagedPackEntry {
        val packDir = resolveStagingPath(stagingRootDir, packId)
            ?: throw BackupException("Ruta de pack inválida: '$packId'.")
        if (!packDir.isDirectory) {
            throw BackupException("Falta la carpeta del pack '$packId' en el archivo .zip.")
        }

        val packJsonFile = resolveStagingPath(packDir, LocalPackStore.PACK_JSON_FILE)
            ?: throw BackupException("Ruta inválida de pack.json en '$packId'.")
        if (!packJsonFile.isFile) {
            throw BackupException("Falta ${LocalPackStore.PACK_JSON_FILE} en el pack '$packId'.")
        }

        val pack = runCatching {
            PackJsonParser.parse(packJsonFile.readText(Charsets.UTF_8))
        }.getOrElse {
            throw BackupException("El ${LocalPackStore.PACK_JSON_FILE} de '$packId' está dañado o incompleto.", it)
        }

        if (pack.schemaVersion != PackJsonParser.SCHEMA_VERSION) {
            throw BackupException(
                "Versión de esquema no compatible en '$packId': ${pack.schemaVersion}.",
            )
        }
        if (pack.identifier != packId) {
            throw BackupException(
                "El identificador del pack ('${pack.identifier}') no coincide con su carpeta ('$packId').",
            )
        }
        if (!PackJsonParser.isSafeSingleFileName(pack.trayImageFile)) {
            throw BackupException("Nombre de icono de bandeja inválido en '$packId': '${pack.trayImageFile}'.")
        }

        val trayFile = resolveStagingPath(packDir, pack.trayImageFile)
        if (trayFile == null || !trayFile.isFile) {
            throw BackupException("Falta el icono de bandeja '${pack.trayImageFile}' en '$packId'.")
        }

        for (sticker in pack.stickers) {
            val assetName = PackJsonParser.extractStickerAssetName(sticker.file)
                ?: throw BackupException("Ruta de sticker inválida en '$packId': '${sticker.file}'.")
            val stickerFile = resolveStagingPath(packDir, "${LocalPackStore.STICKERS_DIR_NAME}/$assetName")
            if (stickerFile == null || !stickerFile.isFile) {
                throw BackupException("Falta el archivo de sticker '$assetName' en '$packId'.")
            }
        }

        val validation = PackValidator.validate(pack = pack, packDir = packDir)
        return StagedPackEntry(
            pack = pack,
            stagedDir = packDir,
            validation = validation,
            existingPack = existingPack,
        )
    }

    private fun extractZipSafely(inputStream: InputStream, stagingRootDir: File) {
        val canonicalRoot = stagingRootDir.canonicalPath
        val zis = ZipInputStream(BufferedInputStream(inputStream))
        var entryCount = 0
        var totalUncompressedBytes = 0L
        val buffer = ByteArray(BUFFER_SIZE)

        while (true) {
            val entry = zis.nextEntry ?: break
            try {
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw BackupException("El archivo .zip supera el máximo de entradas permitido.")
                }

                val normalizedName = sanitizeZipEntryPath(entry.name)
                if (shouldIgnoreSystemMetadata(normalizedName)) {
                    continue
                }

                val targetFile = File(stagingRootDir, normalizedName)
                val canonicalTarget = targetFile.canonicalPath
                if (!canonicalTarget.startsWith("$canonicalRoot${File.separator}")) {
                    throw BackupException("Entrada peligrosa detectada en el .zip (zip-slip): '${entry.name}'.")
                }

                if (entry.isDirectory) {
                    if (!targetFile.exists() && !targetFile.mkdirs()) {
                        throw BackupException("No se pudo crear el directorio '$normalizedName'.")
                    }
                    continue
                }

                val parent = targetFile.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw BackupException("No se pudo crear la carpeta contenedora de '$normalizedName'.")
                }

                FileOutputStream(targetFile).use { fos ->
                    while (true) {
                        val read = zis.read(buffer)
                        if (read < 0) break
                        totalUncompressedBytes += read
                        if (totalUncompressedBytes > MAX_UNCOMPRESSED_BYTES) {
                            throw BackupException("La copia de seguridad supera el tamaño máximo permitido (250 MB).")
                        }
                        fos.write(buffer, 0, read)
                    }
                }
            } finally {
                zis.closeEntry()
            }
        }
    }

    private fun sanitizeZipEntryPath(rawName: String): String {
        if (rawName.contains('\u0000')) {
            throw BackupException("Entrada inválida en el .zip (carácter nulo).")
        }
        val normalized = rawName.replace('\\', '/')
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains(':')) {
            throw BackupException("Ruta absoluta o inválida en el .zip: '$rawName'.")
        }
        val trimmed = normalized.trimEnd('/')
        if (trimmed.isEmpty()) {
            throw BackupException("Ruta vacía en el .zip: '$rawName'.")
        }
        val segments = trimmed.split('/')
        for (segment in segments) {
            if (segment.isEmpty() || segment == "." || segment == "..") {
                throw BackupException("Ruta insegura detectada en el .zip: '$rawName'.")
            }
        }
        return trimmed
    }

    private fun shouldIgnoreSystemMetadata(normalizedPath: String): Boolean {
        val firstSegment = normalizedPath.substringBefore('/')
        val fileName = normalizedPath.substringAfterLast('/')
        return firstSegment == "__MACOSX" || fileName == ".DS_Store" || fileName == "Thumbs.db"
    }

    private fun resolveStagingPath(baseDir: File, relativePath: String): File? {
        val candidate = File(baseDir, relativePath)
        val canonicalBase = baseDir.canonicalPath
        val canonicalCandidate = candidate.canonicalPath
        return if (canonicalCandidate.startsWith("$canonicalBase${File.separator}")) candidate else null
    }

    private fun preparePackExport(loaded: LocalPackStore.LoadedPack): PreparedPackExport {
        val pack = loaded.pack
        if (!PackJsonParser.isSafeIdentifier(pack.identifier)) {
            throw BackupException("El pack '${pack.name}' tiene un identificador inválido: '${pack.identifier}'.")
        }
        if (!PackJsonParser.isSafeSingleFileName(pack.trayImageFile)) {
            throw BackupException("El pack '${pack.name}' tiene un icono de bandeja inválido.")
        }
        val trayFile = resolveStagingPath(loaded.directory, pack.trayImageFile)
            ?.takeIf { it.isFile }
            ?: throw BackupException("Falta el icono de bandeja del pack '${pack.name}'.")

        val stickerFiles = linkedMapOf<String, File>()
        for (sticker in pack.stickers) {
            val assetName = PackJsonParser.extractStickerAssetName(sticker.file)
                ?: throw BackupException("Ruta de sticker inválida en '${pack.name}': '${sticker.file}'.")
            if (assetName in stickerFiles) continue
            val stickerFile = resolveStagingPath(loaded.directory, "${LocalPackStore.STICKERS_DIR_NAME}/$assetName")
                ?.takeIf { it.isFile }
                ?: throw BackupException("Falta el sticker '$assetName' del pack '${pack.name}'.")
            stickerFiles[assetName] = stickerFile
        }

        return PreparedPackExport(
            pack = pack,
            trayFile = trayFile,
            stickerFiles = stickerFiles,
        )
    }

    private fun writeTextEntry(zos: ZipOutputStream, entryName: String, content: String) {
        zos.putNextEntry(ZipEntry(entryName))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun writeFileEntry(zos: ZipOutputStream, entryName: String, file: File) {
        zos.putNextEntry(ZipEntry(entryName))
        file.inputStream().buffered().use { input ->
            input.copyTo(zos, BUFFER_SIZE)
        }
        zos.closeEntry()
    }

    private data class PreparedPackExport(
        val pack: StickerPack,
        val trayFile: File,
        val stickerFiles: Map<String, File>,
    )
}
