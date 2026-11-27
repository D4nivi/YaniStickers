package io.github.danivi.yanistickers.data

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/**
 * Copia los packs de prueba desde `assets/seed_packs/` a `filesDir/packs/` cuando no existen,
 * para que el `ContentProvider` sirva siempre desde el almacenamiento interno de la app.
 */
object SeedPackInstaller {

    private const val SEED_ROOT_ASSET = "seed_packs"

    fun ensureSeedPacksInstalled(context: Context) {
        val assets = context.assets
        val packsRoot = File(context.filesDir, LocalPackStore.PACKS_DIR_NAME)
        if (!packsRoot.exists()) {
            packsRoot.mkdirs()
        }

        val seedDirs = assets.list(SEED_ROOT_ASSET).orEmpty()
        for (dirName in seedDirs) {
            val packJsonAsset = "$SEED_ROOT_ASSET/$dirName/${LocalPackStore.PACK_JSON_FILE}"
            val pack = runCatching {
                assets.open(packJsonAsset).bufferedReader(Charsets.UTF_8).use {
                    PackJsonParser.parse(it.readText())
                }
            }.getOrNull() ?: continue

            val targetDir = File(packsRoot, pack.identifier)
            val targetPackJson = File(targetDir, LocalPackStore.PACK_JSON_FILE)
            if (targetPackJson.isFile) continue

            copyAssetTree(assets, "$SEED_ROOT_ASSET/$dirName", targetDir)
        }
    }

    private fun copyAssetTree(assets: AssetManager, assetPath: String, destDir: File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destDir.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                destDir.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        for (child in children) {
            copyAssetTree(assets, "$assetPath/$child", File(destDir, child))
        }
    }
}
