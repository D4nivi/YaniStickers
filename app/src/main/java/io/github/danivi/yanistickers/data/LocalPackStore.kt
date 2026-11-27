package io.github.danivi.yanistickers.data

import android.content.Context
import java.io.File

/**
 * Lee los packs de stickers y resuelve sus archivos directamente desde `filesDir/packs/`.
 */
class LocalPackStore(private val packsRootDir: File) {

    constructor(context: Context) : this(File(context.filesDir, PACKS_DIR_NAME))

    data class LoadedPack(
        val directory: File,
        val pack: StickerPack,
    )

    fun listPacks(): List<StickerPack> = loadAll().map { it.pack }

    fun getPack(identifier: String): StickerPack? = findLoadedPack(identifier)?.pack

    /**
     * Resuelve el icono de bandeja o un sticker para [identifier] y [assetFileName], asegurando
     * que el archivo pertenezca al pack y no salga de su carpeta.
     */
    fun resolveAssetFile(identifier: String, assetFileName: String): File? {
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

    private fun loadAll(): List<LoadedPack> {
        if (!packsRootDir.isDirectory) return emptyList()
        val dirs = packsRootDir.listFiles { file -> file.isDirectory } ?: return emptyList()
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

    companion object {
        const val PACKS_DIR_NAME = "packs"
        const val PACK_JSON_FILE = "pack.json"
    }
}
