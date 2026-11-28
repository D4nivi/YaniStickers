package io.github.danivi.yanistickers.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkAssetName: String,
    val apkSizeBytes: Long?,
    val htmlUrl: String,
)

/**
 * Cliente ligero sobre `HttpURLConnection` para consultar GitHub Releases y descargar el APK.
 */
object GitHubUpdateClient {

    const val REPO_URL = "https://github.com/D4nivi/YaniStickers"
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/D4nivi/YaniStickers/releases/latest"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 5
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val SAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")

    suspend fun fetchLatestUpdate(
        currentVersionName: String,
        endpointUrl: String = LATEST_RELEASE_API_URL,
    ): UpdateReleaseInfo? = withContext(Dispatchers.IO) {
        val connection = openGetConnection(endpointUrl, acceptHeader = "application/vnd.github+json")
        try {
            val code = connection.responseCode
            when {
                code == HttpURLConnection.HTTP_NOT_FOUND -> return@withContext null
                code == HttpURLConnection.HTTP_FORBIDDEN || code == 429 -> {
                    throw IOException("GitHub ha limitado temporalmente las consultas. Inténtalo en unos minutos.")
                }
                code !in 200..299 -> {
                    throw IOException("Error al consultar GitHub (código $code).")
                }
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseReleaseIfNewer(body, currentVersionName)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseReleaseIfNewer(
        jsonBody: String,
        currentVersionName: String,
    ): UpdateReleaseInfo? {
        val root = JSONObject(jsonBody)
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
            return null
        }

        val tagName = root.optString("tag_name", "").trim()
        if (tagName.isEmpty() || !AppVersionComparator.isNewer(tagName, currentVersionName)) {
            return null
        }

        val assets = root.optJSONArray("assets") ?: JSONArray()
        val selectedAsset = selectApkAsset(assets)
            ?: throw IOException("La versión $tagName aún no tiene ningún APK adjunto.")

        val downloadUrl = selectedAsset.optString("browser_download_url", "").trim()
        if (!downloadUrl.startsWith("https://")) {
            throw IOException("La URL de descarga del APK no es válida.")
        }

        val assetName = selectedAsset.optString("name", "").trim()
            .ifEmpty { "YaniStickers-$tagName.apk" }
        val sizeBytes = selectedAsset.optLong("size", -1L).takeIf { it > 0L }

        return UpdateReleaseInfo(
            tagName = tagName,
            versionName = AppVersionComparator.normalize(tagName),
            releaseNotes = root.optString("body", "").trim(),
            apkDownloadUrl = downloadUrl,
            apkAssetName = assetName,
            apkSizeBytes = sizeBytes,
            htmlUrl = root.optString("html_url", REPO_URL).trim().ifEmpty { REPO_URL },
        )
    }

    suspend fun downloadApk(
        release: UpdateReleaseInfo,
        updatesDir: File,
        onProgress: (Int?) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        if (!updatesDir.exists() && !updatesDir.mkdirs()) {
            throw IOException("No se pudo preparar la carpeta temporal de descarga.")
        }

        // Limpia descargas anteriores para no acumular APKs en caché.
        updatesDir.listFiles()?.forEach { oldFile ->
            if (oldFile.isFile) oldFile.delete()
        }

        val safeName = release.apkAssetName
            .replace(SAFE_FILE_CHARS, "_")
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
        val tempFile = File(updatesDir, "$safeName.part")
        val targetFile = File(updatesDir, safeName)

        val connection = openGetConnection(
            url = release.apkDownloadUrl,
            acceptHeader = "application/octet-stream",
        )
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("No se pudo descargar el APK (código $code).")
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
                ?: release.apkSizeBytes?.takeIf { it > 0L }
            onProgress(if (totalBytes != null) 0 else null)

            var downloadedBytes = 0L
            var lastReportedPercent = -1

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes != null) {
                                val percent = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                if (percent != lastReportedPercent) {
                                    lastReportedPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                    output.fd.sync()
                }
            }

            if (downloadedBytes < 4L || !hasZipSignature(tempFile)) {
                tempFile.delete()
                throw IOException("El archivo descargado no parece un APK válido.")
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            onProgress(100)
            targetFile
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }

    private fun selectApkAsset(assets: JSONArray): JSONObject? {
        var fallbackApk: JSONObject? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            val url = asset.optString("browser_download_url", "")
            if (!name.endsWith(".apk", ignoreCase = true) || !url.startsWith("https://")) {
                continue
            }
            val contentType = asset.optString("content_type", "")
            if (contentType.equals(APK_MIME, ignoreCase = true)) {
                return asset
            }
            if (fallbackApk == null) {
                fallbackApk = asset
            }
        }
        return fallbackApk
    }

    private fun openGetConnection(
        url: String,
        acceptHeader: String,
    ): HttpURLConnection {
        var currentUrl = url
        repeat(MAX_REDIRECTS) {
            val parsedUrl = URL(currentUrl)
            if (!parsedUrl.protocol.equals("https", ignoreCase = true)) {
                throw IOException("Solo se permiten conexiones HTTPS.")
            }
            val conn = (parsedUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", acceptHeader)
                setRequestProperty("User-Agent", "YaniStickers-Android")
            }
            val code = conn.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")?.trim().orEmpty()
                conn.disconnect()
                if (location.isEmpty()) {
                    throw IOException("Redirección sin destino válido.")
                }
                currentUrl = URL(parsedUrl, location).toString()
            } else {
                return conn
            }
        }
        throw IOException("Demasiadas redirecciones al conectar con GitHub.")
    }

    private fun hasZipSignature(file: File): Boolean {
        return file.inputStream().use { input ->
            val b0 = input.read()
            val b1 = input.read()
            b0 == 0x50 && b1 == 0x4B
        }
    }
}
