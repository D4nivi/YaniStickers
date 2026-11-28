package io.github.danivi.yanistickers.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class GitHubUpdateClientTest {

    @Test
    fun `parses newer release and selects apk asset`() {
        val json = """
            {
              "tag_name": "v1.1.0",
              "draft": false,
              "prerelease": false,
              "html_url": "https://github.com/D4nivi/YaniStickers/releases/tag/v1.1.0",
              "body": "Añadidos stickers con sonido.",
              "assets": [
                {
                  "name": "checksums.txt",
                  "content_type": "text/plain",
                  "size": 128,
                  "browser_download_url": "https://github.com/D4nivi/YaniStickers/releases/download/v1.1.0/checksums.txt"
                },
                {
                  "name": "YaniStickers-v1.1.0.apk",
                  "content_type": "application/vnd.android.package-archive",
                  "size": 18432000,
                  "browser_download_url": "https://github.com/D4nivi/YaniStickers/releases/download/v1.1.0/YaniStickers-v1.1.0.apk"
                }
              ]
            }
        """.trimIndent()

        val result = GitHubUpdateClient.parseReleaseIfNewer(json, currentVersionName = "1.0.0")
        assertNotNull(result)
        assertEquals("v1.1.0", result?.tagName)
        assertEquals("1.1.0", result?.versionName)
        assertEquals("YaniStickers-v1.1.0.apk", result?.apkAssetName)
        assertEquals(18432000L, result?.apkSizeBytes)
        assertEquals("Añadidos stickers con sonido.", result?.releaseNotes)
    }

    @Test
    fun `ignores equal, older, draft and prerelease entries`() {
        val sameVersionJson = """
            {
              "tag_name": "v1.0.0",
              "draft": false,
              "prerelease": false,
              "assets": []
            }
        """.trimIndent()
        assertNull(GitHubUpdateClient.parseReleaseIfNewer(sameVersionJson, "1.0.0"))

        val prereleaseJson = """
            {
              "tag_name": "v2.0.0",
              "draft": false,
              "prerelease": true,
              "assets": []
            }
        """.trimIndent()
        assertNull(GitHubUpdateClient.parseReleaseIfNewer(prereleaseJson, "1.0.0"))

        val draftJson = """
            {
              "tag_name": "v2.0.0",
              "draft": true,
              "prerelease": false,
              "assets": []
            }
        """.trimIndent()
        assertNull(GitHubUpdateClient.parseReleaseIfNewer(draftJson, "1.0.0"))
    }

    @Test(expected = IOException::class)
    fun `throws when newer release has no apk asset`() {
        val json = """
            {
              "tag_name": "v1.1.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "source.zip",
                  "content_type": "application/zip",
                  "size": 1000,
                  "browser_download_url": "https://github.com/D4nivi/YaniStickers/releases/download/v1.1.0/source.zip"
                }
              ]
            }
        """.trimIndent()

        GitHubUpdateClient.parseReleaseIfNewer(json, "1.0.0")
    }
}
