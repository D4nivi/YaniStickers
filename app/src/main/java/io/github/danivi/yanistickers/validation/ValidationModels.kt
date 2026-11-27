package io.github.danivi.yanistickers.validation

/**
 * Gravedad de una regla comprobada durante la validación de un pack.
 */
enum class ValidationSeverity {
    ERROR,
    WARNING,
}

/**
 * Incidencia individual detectada en metadatos, icono de bandeja o un sticker.
 */
data class ValidationIssue(
    val severity: ValidationSeverity,
    val message: String,
)

/**
 * Resultado de validación para un sticker individual del pack.
 */
data class StickerValidationReport(
    val index: Int,
    val storedFile: String,
    val assetName: String?,
    val imageInfo: InspectedImageInfo?,
    val issues: List<ValidationIssue>,
) {
    val errors: List<ValidationIssue>
        get() = issues.filter { it.severity == ValidationSeverity.ERROR }

    val warnings: List<ValidationIssue>
        get() = issues.filter { it.severity == ValidationSeverity.WARNING }

    val isValid: Boolean
        get() = errors.isEmpty()
}

/**
 * Informe completo de validación de un pack frente a los requisitos oficiales de WhatsApp.
 */
data class PackValidationReport(
    val packIdentifier: String,
    val packIssues: List<ValidationIssue>,
    val trayIssues: List<ValidationIssue>,
    val trayInfo: InspectedImageInfo?,
    val stickerReports: List<StickerValidationReport>,
) {
    val totalErrors: Int
        get() = packIssues.count { it.severity == ValidationSeverity.ERROR } +
            trayIssues.count { it.severity == ValidationSeverity.ERROR } +
            stickerReports.sumOf { it.errors.size }

    val totalWarnings: Int
        get() = packIssues.count { it.severity == ValidationSeverity.WARNING } +
            trayIssues.count { it.severity == ValidationSeverity.WARNING } +
            stickerReports.sumOf { it.warnings.size }

    val isValid: Boolean
        get() = totalErrors == 0

    /**
     * Lista plana y legible de todos los errores concretos del pack, bandeja y stickers.
     */
    fun allErrorMessages(): List<String> = buildList {
        packIssues.filter { it.severity == ValidationSeverity.ERROR }.forEach { add(it.message) }
        trayIssues.filter { it.severity == ValidationSeverity.ERROR }.forEach { add(it.message) }
        stickerReports.forEach { sticker ->
            sticker.errors.forEach { issue ->
                add("Sticker #${sticker.index + 1}: ${issue.message}")
            }
        }
    }
}
