package io.github.danivi.yanistickers.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsea documentos `pack.json` y valida nombres de archivo seguros para los assets.
 */
object PackJsonParser {

    private val SAFE_FILENAME_REGEX = Regex("^[A-Za-z0-9._-]+$")
    private val SAFE_IDENTIFIER_REGEX = Regex("^[A-Za-z0-9._-]+$")

    fun parse(jsonText: String): StickerPack {
        val root = JSONObject(jsonText)
        val stickersArray = root.optJSONArray("stickers") ?: JSONArray()
        val stickers = buildList(stickersArray.length()) {
            for (i in 0 until stickersArray.length()) {
                val item = stickersArray.getJSONObject(i)
                val emojisArray = item.optJSONArray("emojis") ?: JSONArray()
                val emojis = buildList(emojisArray.length()) {
                    for (j in 0 until emojisArray.length()) {
                        val emoji = emojisArray.optString(j).trim()
                        if (emoji.isNotEmpty()) add(emoji)
                    }
                }
                add(
                    StickerItem(
                        file = item.getString("file"),
                        emojis = emojis,
                        accessibilityText = item.optString("accessibilityText", ""),
                    )
                )
            }
        }

        return StickerPack(
            schemaVersion = root.getInt("schemaVersion"),
            identifier = root.getString("identifier"),
            name = root.getString("name"),
            publisher = root.getString("publisher"),
            animated = root.getBoolean("animated"),
            imageDataVersion = root.getString("imageDataVersion"),
            trayImageFile = root.getString("trayImageFile"),
            stickers = stickers,
            createdAt = root.optString("createdAt", ""),
            updatedAt = root.optString("updatedAt", ""),
            avoidCache = root.optBoolean("avoidCache", false),
        )
    }

    /**
     * Convierte una ruta relativa almacenada (p. ej. `stickers/abc.webp`) en el nombre base
     * que exige el contrato URI `stickers_asset/<packId>/<fileName>` de WhatsApp.
     */
    fun extractStickerAssetName(storedFile: String): String? {
        val normalized = storedFile.removePrefix("stickers/")
        return if (isSafeSingleFileName(normalized)) normalized else null
    }

    fun isSafeSingleFileName(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return false
        if (name.contains('/') || name.contains('\\')) return false
        return SAFE_FILENAME_REGEX.matches(name)
    }

    fun isSafeIdentifier(identifier: String): Boolean {
        if (identifier.isEmpty() || identifier == "." || identifier == "..") return false
        return SAFE_IDENTIFIER_REGEX.matches(identifier)
    }
}
