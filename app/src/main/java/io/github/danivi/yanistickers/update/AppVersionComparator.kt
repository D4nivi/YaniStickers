package io.github.danivi.yanistickers.update

/**
 * Compara versiones semánticas (ej. `v1.2.0` frente a `1.0.0`) para detectar nuevas releases.
 */
object AppVersionComparator {

    private data class ParsedVersion(
        val segments: List<Int>,
        val isPreRelease: Boolean,
    )

    fun normalize(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    fun isNewer(remoteVersion: String, currentVersion: String): Boolean {
        val remote = parse(remoteVersion) ?: return false
        val current = parse(currentVersion) ?: return false
        val maxLen = maxOf(remote.segments.size, current.segments.size)
        for (i in 0 until maxLen) {
            val r = remote.segments.getOrElse(i) { 0 }
            val c = current.segments.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return !remote.isPreRelease && current.isPreRelease
    }

    private fun parse(raw: String): ParsedVersion? {
        val cleaned = normalize(raw)
        if (cleaned.isEmpty()) return null

        val buildStripped = cleaned.substringBefore('+')
        val dashIndex = buildStripped.indexOf('-')
        val corePart = if (dashIndex >= 0) buildStripped.substring(0, dashIndex) else buildStripped
        val isPreRelease = dashIndex >= 0

        if (corePart.isEmpty()) return null
        val segments = corePart.split('.').map { token ->
            token.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        }
        return ParsedVersion(segments = segments, isPreRelease = isPreRelease)
    }
}
