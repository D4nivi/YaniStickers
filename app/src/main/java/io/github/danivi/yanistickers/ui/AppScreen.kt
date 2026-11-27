package io.github.danivi.yanistickers.ui

/**
 * Destinos de navegación de la aplicación.
 */
sealed interface AppScreen {
    data object PackList : AppScreen
    data class PackEditor(val packId: String) : AppScreen
    data class StaticStickerCreator(
        val packId: String,
        val forTray: Boolean = false,
        val sessionId: Long = System.nanoTime(),
    ) : AppScreen
    data class AnimatedStickerCreator(
        val packId: String,
        val sessionId: Long = System.nanoTime(),
    ) : AppScreen
    data object Backup : AppScreen
}
