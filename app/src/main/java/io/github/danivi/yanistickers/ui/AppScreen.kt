package io.github.danivi.yanistickers.ui

/**
 * Destinos de navegación de la aplicación.
 */
sealed interface AppScreen {
    data object PackList : AppScreen
    data class PackEditor(val packId: String) : AppScreen
    data object Backup : AppScreen
}
