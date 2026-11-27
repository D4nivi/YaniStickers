package io.github.danivi.yanistickers.data

/**
 * Entrada individual de sticker almacenada dentro del `pack.json` de un pack.
 */
data class StickerItem(
    val file: String,
    val emojis: List<String>,
    val accessibilityText: String = "",
)

/**
 * Metadatos de un pack de stickers almacenado en `filesDir/packs/<packId>/pack.json`.
 */
data class StickerPack(
    val schemaVersion: Int,
    val identifier: String,
    val name: String,
    val publisher: String,
    val animated: Boolean,
    val imageDataVersion: String,
    val trayImageFile: String,
    val stickers: List<StickerItem>,
    val createdAt: String,
    val updatedAt: String,
    val avoidCache: Boolean = false,
)
