package io.github.danivi.yanistickers.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * Serializa y parsea documentos `pack.json`, además de validar nombres e identificadores seguros.
 */
object PackJsonParser {

    const val SCHEMA_VERSION = 1
    const val MAX_IDENTIFIER_LENGTH = 128

    private val SAFE_FILENAME_REGEX = Regex("^[A-Za-z0-9._-]+$")
    private val SAFE_IDENTIFIER_REGEX = Regex("^[A-Za-z0-9._-]+$")
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")

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
                    ),
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

    fun toJson(pack: StickerPack): String {
        val stickersArray = JSONArray()
        for (sticker in pack.stickers) {
            val emojisArray = JSONArray()
            sticker.emojis.forEach { emojisArray.put(it) }
            val itemObj = JSONObject()
                .put("file", sticker.file)
                .put("emojis", emojisArray)
            if (sticker.accessibilityText.isNotEmpty()) {
                itemObj.put("accessibilityText", sticker.accessibilityText)
            }
            stickersArray.put(itemObj)
        }

        val root = JSONObject()
            .put("schemaVersion", pack.schemaVersion)
            .put("identifier", pack.identifier)
            .put("name", pack.name)
            .put("publisher", pack.publisher)
            .put("animated", pack.animated)
            .put("imageDataVersion", pack.imageDataVersion)
            .put("trayImageFile", pack.trayImageFile)
            .put("stickers", stickersArray)
            .put("createdAt", pack.createdAt)
            .put("updatedAt", pack.updatedAt)
        if (pack.avoidCache) {
            root.put("avoidCache", true)
        }
        return root.toString(2)
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
        if (name.isEmpty() || name == "." || name == ".." || name.contains("..")) return false
        if (name.contains('/') || name.contains('\\')) return false
        return SAFE_FILENAME_REGEX.matches(name)
    }

    fun isSafeIdentifier(identifier: String): Boolean {
        if (identifier.isEmpty() || identifier == "." || identifier == ".." || identifier.contains("..")) {
            return false
        }
        if (identifier.length > MAX_IDENTIFIER_LENGTH) return false
        return SAFE_IDENTIFIER_REGEX.matches(identifier)
    }

    /**
     * Genera un identificador estable, legible y único compatible con carpetas locales, URIs y WhatsApp.
     */
    fun generateSafeIdentifier(baseName: String, existingIds: Set<String>): String {
        val normalized = Normalizer.normalize(baseName, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)
        val slug = NON_ALNUM_REGEX.replace(normalized, "_")
            .trim('_')
            .ifEmpty { "pack" }
            .take(48)

        var candidate: String
        do {
            val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
            candidate = "${slug}_$suffix"
        } while (candidate in existingIds || !isSafeIdentifier(candidate))
        return candidate
    }

    /**
     * Divide texto con emojis respetando grafemas Unicode (secuencias ZWJ, tonos de piel, variantes).
     */
    fun splitEmojisGraphemes(rawInput: String): List<String> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(trimmed)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val cluster = trimmed.substring(start, end).trim()
            if (cluster.isNotEmpty() && cluster != "," && cluster != ";") {
                result.add(cluster)
            }
            start = end
            end = iterator.next()
        }
        return result
    }
}
