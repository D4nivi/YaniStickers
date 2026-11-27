package io.github.danivi.yanistickers.validation

import io.github.danivi.yanistickers.data.LocalPackStore
import io.github.danivi.yanistickers.data.PackJsonParser
import io.github.danivi.yanistickers.data.StickerItem
import io.github.danivi.yanistickers.data.StickerPack
import java.io.File

/**
 * Valida un [StickerPack] y sus archivos en disco frente a los límites verificados de
 * `WhatsApp/stickers` (`StickerPackValidator.java`).
 */
object PackValidator {

    const val MIN_STICKERS = 3
    const val MAX_STICKERS = 30
    const val MIN_EMOJIS = 1
    const val MAX_EMOJIS = 3
    const val MAX_TEXT_LENGTH = 128
    const val MAX_STATIC_A11Y_LENGTH = 125
    const val MAX_ANIMATED_A11Y_LENGTH = 255
    const val STICKER_DIMENSION_PX = 512
    const val MAX_STATIC_STICKER_KB = 100
    const val MAX_ANIMATED_STICKER_KB = 500
    const val MAX_STATIC_STICKER_BYTES = MAX_STATIC_STICKER_KB * 1024L
    const val MAX_ANIMATED_STICKER_BYTES = MAX_ANIMATED_STICKER_KB * 1024L
    const val MAX_TRAY_KB = 50
    const val MAX_TRAY_BYTES = MAX_TRAY_KB * 1024L
    const val MIN_TRAY_DIMENSION_PX = 24
    const val MAX_TRAY_DIMENSION_PX = 512
    const val RECOMMENDED_TRAY_DIMENSION_PX = 96
    const val MIN_ANIMATED_FRAME_DURATION_MS = 8
    const val MAX_ANIMATED_TOTAL_DURATION_MS = 10_000L

    fun validate(store: LocalPackStore, pack: StickerPack): PackValidationReport {
        val packDir = store.getPackDirectory(pack.identifier)
        return validate(pack = pack, packDir = packDir)
    }

    fun validate(pack: StickerPack, packDir: File?): PackValidationReport {
        val packIssues = validateMetadata(pack)
        val (trayInfo, trayIssues) = validateTray(pack, packDir)
        val stickerReports = pack.stickers.mapIndexed { index, sticker ->
            validateSticker(
                index = index,
                sticker = sticker,
                animatedPack = pack.animated,
                packDir = packDir,
            )
        }
        return PackValidationReport(
            packIdentifier = pack.identifier,
            packIssues = packIssues,
            trayIssues = trayIssues,
            trayInfo = trayInfo,
            stickerReports = stickerReports,
        )
    }

    private fun validateMetadata(pack: StickerPack): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        if (pack.identifier.isBlank()) {
            issues += error("El identificador del pack no puede estar vacío.")
        } else {
            if (pack.identifier.length > MAX_TEXT_LENGTH) {
                issues += error(
                    "El identificador supera $MAX_TEXT_LENGTH caracteres (${pack.identifier.length}).",
                )
            }
            if (!PackJsonParser.isSafeIdentifier(pack.identifier)) {
                issues += error(
                    "El identificador solo puede usar letras, números, '.', '_' y '-' (sin '..' ni espacios).",
                )
            }
        }

        if (pack.name.isBlank()) {
            issues += error("El nombre del pack no puede estar vacío.")
        } else if (pack.name.length > MAX_TEXT_LENGTH) {
            issues += error("El nombre del pack supera $MAX_TEXT_LENGTH caracteres (${pack.name.length}).")
        }

        if (pack.publisher.isBlank()) {
            issues += error("El autor del pack no puede estar vacío.")
        } else if (pack.publisher.length > MAX_TEXT_LENGTH) {
            issues += error("El autor del pack supera $MAX_TEXT_LENGTH caracteres (${pack.publisher.length}).")
        }

        if (pack.stickers.size < MIN_STICKERS) {
            issues += error(
                "El pack tiene ${pack.stickers.size} stickers (mínimo requerido por WhatsApp: $MIN_STICKERS).",
            )
        } else if (pack.stickers.size > MAX_STICKERS) {
            issues += error(
                "El pack tiene ${pack.stickers.size} stickers (máximo permitido por WhatsApp: $MAX_STICKERS).",
            )
        }

        return issues
    }

    private fun validateTray(
        pack: StickerPack,
        packDir: File?,
    ): Pair<InspectedImageInfo?, List<ValidationIssue>> {
        val issues = mutableListOf<ValidationIssue>()
        if (pack.trayImageFile.isBlank()) {
            issues += error("Falta el nombre del archivo del icono de bandeja.")
            return null to issues
        }
        if (!PackJsonParser.isSafeSingleFileName(pack.trayImageFile)) {
            issues += error("Nombre de archivo inválido para el icono de bandeja: ${pack.trayImageFile}.")
            return null to issues
        }
        if (packDir == null || !packDir.isDirectory) {
            issues += error("No se encontró la carpeta local del pack para comprobar el icono de bandeja.")
            return null to issues
        }

        val trayFile = resolveInside(packDir, pack.trayImageFile)
        if (trayFile == null || !trayFile.isFile) {
            issues += error("No existe el archivo del icono de bandeja (${pack.trayImageFile}).")
            return null to issues
        }

        val sizeBytes = trayFile.length()
        if (sizeBytes <= 0L) {
            issues += error("El icono de bandeja está vacío.")
            return null to issues
        }
        if (sizeBytes > MAX_TRAY_BYTES) {
            issues += error(
                "El icono de bandeja pesa ${formatKb(sizeBytes)} (máximo $MAX_TRAY_KB KB).",
            )
        }

        val info = ImageContainerInspector.inspect(trayFile)
        if (info == null) {
            issues += error("El icono de bandeja no es una imagen WebP o PNG válida.")
            return null to issues
        }
        if (info.isAnimated) {
            issues += error("El icono de bandeja debe ser una imagen estática, no animada.")
        }
        if (info.width !in MIN_TRAY_DIMENSION_PX..MAX_TRAY_DIMENSION_PX ||
            info.height !in MIN_TRAY_DIMENSION_PX..MAX_TRAY_DIMENSION_PX
        ) {
            issues += error(
                "El icono de bandeja mide ${info.width}×${info.height} px (WhatsApp exige entre $MIN_TRAY_DIMENSION_PX y $MAX_TRAY_DIMENSION_PX px).",
            )
        } else if (info.width != RECOMMENDED_TRAY_DIMENSION_PX || info.height != RECOMMENDED_TRAY_DIMENSION_PX) {
            issues += warning(
                "El icono de bandeja mide ${info.width}×${info.height} px; el tamaño recomendado es ${RECOMMENDED_TRAY_DIMENSION_PX}×${RECOMMENDED_TRAY_DIMENSION_PX} px.",
            )
        }

        return info to issues
    }

    private fun validateSticker(
        index: Int,
        sticker: StickerItem,
        animatedPack: Boolean,
        packDir: File?,
    ): StickerValidationReport {
        val issues = mutableListOf<ValidationIssue>()

        val validEmojis = sticker.emojis.map { it.trim() }.filter { it.isNotEmpty() }
        if (validEmojis.size < MIN_EMOJIS) {
            issues += error("Debe tener al menos $MIN_EMOJIS emoji asociado.")
        } else if (validEmojis.size > MAX_EMOJIS) {
            issues += error("Tiene ${validEmojis.size} emojis (máximo $MAX_EMOJIS).")
        }

        val maxA11y = if (animatedPack) MAX_ANIMATED_A11Y_LENGTH else MAX_STATIC_A11Y_LENGTH
        if (sticker.accessibilityText.length > maxA11y) {
            issues += error(
                "El texto de accesibilidad supera $maxA11y caracteres (${sticker.accessibilityText.length}).",
            )
        }

        val assetName = PackJsonParser.extractStickerAssetName(sticker.file)
        if (sticker.file.isBlank() || assetName == null) {
            issues += error("Ruta de sticker inválida: '${sticker.file}'.")
            return StickerValidationReport(index, sticker.file, assetName, null, issues)
        }

        if (packDir == null || !packDir.isDirectory) {
            issues += error("No existe la carpeta del pack para leer $assetName.")
            return StickerValidationReport(index, sticker.file, assetName, null, issues)
        }

        val stickerFile = resolveInside(packDir, "stickers/$assetName")
        if (stickerFile == null || !stickerFile.isFile) {
            issues += error("No se encontró el archivo del sticker ($assetName).")
            return StickerValidationReport(index, sticker.file, assetName, null, issues)
        }

        val sizeBytes = stickerFile.length()
        if (sizeBytes <= 0L) {
            issues += error("El archivo del sticker está vacío.")
            return StickerValidationReport(index, sticker.file, assetName, null, issues)
        }

        val maxBytes = if (animatedPack) MAX_ANIMATED_STICKER_BYTES else MAX_STATIC_STICKER_BYTES
        val maxKb = if (animatedPack) MAX_ANIMATED_STICKER_KB else MAX_STATIC_STICKER_KB
        if (sizeBytes > maxBytes) {
            issues += error("Pesa ${formatKb(sizeBytes)} (máximo permitido: $maxKb KB).")
        }

        val info = ImageContainerInspector.inspect(stickerFile)
        if (info == null || info.format != ImageContainerFormat.WEBP) {
            issues += error("El archivo no es un WebP válido.")
            return StickerValidationReport(index, sticker.file, assetName, info, issues)
        }

        if (info.width != STICKER_DIMENSION_PX || info.height != STICKER_DIMENSION_PX) {
            issues += error(
                "Mide ${info.width}×${info.height} px (WhatsApp exige exactamente ${STICKER_DIMENSION_PX}×${STICKER_DIMENSION_PX} px).",
            )
        }

        if (animatedPack) {
            if (info.frameCount <= 1 || !info.isAnimated) {
                issues += error("El pack es animado, pero este sticker es estático (1 frame).")
            } else {
                val minDuration = info.minFrameDurationMs
                if (minDuration != null && minDuration < MIN_ANIMATED_FRAME_DURATION_MS) {
                    issues += error(
                        "Hay un frame de $minDuration ms (mínimo requerido: $MIN_ANIMATED_FRAME_DURATION_MS ms).",
                    )
                }
                if (info.totalDurationMs > MAX_ANIMATED_TOTAL_DURATION_MS) {
                    issues += error(
                        "La duración total es ${formatSeconds(info.totalDurationMs)} (máximo 10 s).",
                    )
                }
            }
        } else if (info.frameCount > 1 || info.isAnimated) {
            issues += error(
                "El pack es estático, pero este sticker es animado (${info.frameCount} frames).",
            )
        }

        return StickerValidationReport(index, sticker.file, assetName, info, issues)
    }

    private fun resolveInside(packDir: File, relativePath: String): File? {
        val candidate = File(packDir, relativePath)
        val canonicalRoot = packDir.canonicalPath
        val canonicalTarget = candidate.canonicalPath
        if (!canonicalTarget.startsWith("$canonicalRoot${File.separator}")) return null
        return candidate
    }

    private fun error(message: String) = ValidationIssue(ValidationSeverity.ERROR, message)

    private fun warning(message: String) = ValidationIssue(ValidationSeverity.WARNING, message)

    fun formatKb(bytes: Long): String {
        val kb = bytes / 1024.0
        return String.format(java.util.Locale.US, "%.1f KB", kb)
    }

    fun formatSeconds(ms: Long): String {
        val sec = ms / 1000.0
        return String.format(java.util.Locale.US, "%.2f s", sec)
    }
}
