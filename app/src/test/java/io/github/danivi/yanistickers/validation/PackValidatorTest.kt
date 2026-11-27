package io.github.danivi.yanistickers.validation

import io.github.danivi.yanistickers.data.StickerItem
import io.github.danivi.yanistickers.data.StickerPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackValidatorTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun validate_acceptsValidStaticPack() {
        val packDir = tempFolder.newFolder("static_ok")
        writeFile(packDir, "tray.png", TestImageBuilders.png(96, 96))
        val stickers = (1..3).map { idx ->
            writeFile(packDir, "stickers/s$idx.webp", TestImageBuilders.staticVp8lWebp(512, 512))
            StickerItem(file = "stickers/s$idx.webp", emojis = listOf("😀"))
        }

        val pack = basePack(identifier = "static_ok", animated = false, stickers = stickers)
        val report = PackValidator.validate(pack, packDir)

        assertTrue(report.isValid)
        assertEquals(0, report.totalErrors)
        assertEquals(0, report.totalWarnings)
    }

    @Test
    fun validate_acceptsValidAnimatedPack() {
        val packDir = tempFolder.newFolder("anim_ok")
        writeFile(packDir, "tray.png", TestImageBuilders.png(96, 96))
        val stickers = (1..3).map { idx ->
            writeFile(
                packDir,
                "stickers/a$idx.webp",
                TestImageBuilders.animatedWebp(512, 512, listOf(80, 120)),
            )
            StickerItem(file = "stickers/a$idx.webp", emojis = listOf("🔥", "🎉"))
        }

        val pack = basePack(identifier = "anim_ok", animated = true, stickers = stickers)
        val report = PackValidator.validate(pack, packDir)

        assertTrue(report.isValid)
        assertEquals(0, report.totalErrors)
    }

    @Test
    fun validate_rejectsStickerCountsOutside3To30AndBadMetadata() {
        val packDir = tempFolder.newFolder("bad_meta")
        writeFile(packDir, "tray.png", TestImageBuilders.png(96, 96))
        val stickers = (1..2).map { idx ->
            writeFile(packDir, "stickers/s$idx.webp", TestImageBuilders.staticVp8lWebp(512, 512))
            StickerItem(file = "stickers/s$idx.webp", emojis = listOf("😀"))
        }

        val badPack = basePack(
            identifier = "id invalido",
            name = "",
            publisher = "a".repeat(129),
            animated = false,
            stickers = stickers,
        )
        val report = PackValidator.validate(badPack, packDir)

        assertFalse(report.isValid)
        assertTrue(report.packIssues.size >= 4)
        assertTrue(report.allErrorMessages().any { it.contains("3") })

        val thirtyOneStickers = (1..31).map { idx ->
            writeFile(packDir, "stickers/m$idx.webp", TestImageBuilders.staticVp8lWebp(512, 512))
            StickerItem(file = "stickers/m$idx.webp", emojis = listOf("👍"))
        }
        val tooManyReport = PackValidator.validate(
            basePack(identifier = "too_many", animated = false, stickers = thirtyOneStickers),
            packDir,
        )
        assertFalse(tooManyReport.isValid)
        assertTrue(tooManyReport.packIssues.any { it.message.contains("30") })
    }

    @Test
    fun validate_enforcesStaticAndAnimatedStickerSizeDimensionsAndDurations() {
        val packDir = tempFolder.newFolder("limits")
        writeFile(packDir, "tray.png", TestImageBuilders.png(96, 96))

        // Caso estático con 3 stickers defectuosos distintos: >100 KB, != 512x512 y 0 emojis + animado mezclado
        val baseStatic = TestImageBuilders.staticVp8lWebp(512, 512)
        val over100Kb = TestImageBuilders.staticVp8lWebp(
            width = 512,
            height = 512,
            extraJunkBytes = (PackValidator.MAX_STATIC_STICKER_BYTES - baseStatic.size + 100).toInt(),
        )
        writeFile(packDir, "stickers/s1.webp", over100Kb)
        writeFile(packDir, "stickers/s2.webp", TestImageBuilders.staticVp8lWebp(256, 512))
        writeFile(packDir, "stickers/s3.webp", TestImageBuilders.animatedWebp(512, 512, listOf(50, 50)))

        val staticReport = PackValidator.validate(
            basePack(
                identifier = "limits",
                animated = false,
                stickers = listOf(
                    StickerItem("stickers/s1.webp", listOf("😀")),
                    StickerItem("stickers/s2.webp", emptyList(), accessibilityText = "x".repeat(126)),
                    StickerItem("stickers/s3.webp", listOf("😀", "🎉", "🔥", "❤️")),
                ),
            ),
            packDir,
        )

        assertFalse(staticReport.isValid)
        assertTrue(staticReport.stickerReports[0].errors.any { it.message.contains("100 KB") })
        assertTrue(staticReport.stickerReports[1].errors.any { it.message.contains("512×512") })
        assertTrue(staticReport.stickerReports[1].errors.any { it.message.contains("emoji") })
        assertTrue(staticReport.stickerReports[1].errors.any { it.message.contains("125") })
        assertTrue(staticReport.stickerReports[2].errors.any { it.message.contains("animado") })
        assertTrue(staticReport.stickerReports[2].errors.any { it.message.contains("máximo 3") })

        // Caso animado: frame < 8ms, > 10s, > 500KB y estático mezclado
        writeFile(packDir, "stickers/a1.webp", TestImageBuilders.animatedWebp(512, 512, listOf(5, 50)))
        writeFile(packDir, "stickers/a2.webp", TestImageBuilders.animatedWebp(512, 512, listOf(6000, 4500)))
        val baseAnim = TestImageBuilders.animatedWebp(512, 512, listOf(50, 50))
        val over500Kb = TestImageBuilders.animatedWebp(
            width = 512,
            height = 512,
            frameDurationsMs = listOf(50, 50),
            extraJunkBytes = (PackValidator.MAX_ANIMATED_STICKER_BYTES - baseAnim.size + 100).toInt(),
        )
        writeFile(packDir, "stickers/a3.webp", over500Kb)

        val animReport = PackValidator.validate(
            basePack(
                identifier = "limits",
                animated = true,
                stickers = listOf(
                    StickerItem("stickers/a1.webp", listOf("😀")),
                    StickerItem("stickers/a2.webp", listOf("😀")),
                    StickerItem("stickers/a3.webp", listOf("😀"), accessibilityText = "x".repeat(256)),
                ),
            ),
            packDir,
        )

        assertFalse(animReport.isValid)
        assertTrue(animReport.stickerReports[0].errors.any { it.message.contains("8 ms") })
        assertTrue(animReport.stickerReports[1].errors.any { it.message.contains("10 s") })
        assertTrue(animReport.stickerReports[2].errors.any { it.message.contains("500 KB") })
        assertTrue(animReport.stickerReports[2].errors.any { it.message.contains("255") })
    }

    @Test
    fun validate_checksTrayDimensionsAndSizeLimit() {
        val packDir = tempFolder.newFolder("tray_checks")
        val stickers = (1..3).map { idx ->
            writeFile(packDir, "stickers/s$idx.webp", TestImageBuilders.staticVp8lWebp(512, 512))
            StickerItem("stickers/s$idx.webp", listOf("😀"))
        }

        // Tray válido 128x128: pasa con warning recomendando 96x96
        writeFile(packDir, "tray.png", TestImageBuilders.png(128, 128))
        val warnReport = PackValidator.validate(basePack("tray_checks", false, stickers), packDir)
        assertTrue(warnReport.isValid)
        assertEquals(1, warnReport.totalWarnings)

        // Tray demasiado pequeño (20x20)
        writeFile(packDir, "tray.png", TestImageBuilders.png(20, 20))
        val smallTrayReport = PackValidator.validate(basePack("tray_checks", false, stickers), packDir)
        assertFalse(smallTrayReport.isValid)
        assertTrue(smallTrayReport.trayIssues.any { it.message.contains("24") })

        // Tray > 50 KB
        writeFile(packDir, "tray.png", TestImageBuilders.png(96, 96, extraPayloadSize = 52_000))
        val heavyTrayReport = PackValidator.validate(basePack("tray_checks", false, stickers), packDir)
        assertFalse(heavyTrayReport.isValid)
        assertTrue(heavyTrayReport.trayIssues.any { it.message.contains("50 KB") })
    }

    private fun basePack(
        identifier: String,
        animated: Boolean,
        stickers: List<StickerItem>,
        name: String = "Pack de prueba",
        publisher: String = "Yani",
    ) = StickerPack(
        schemaVersion = 1,
        identifier = identifier,
        name = name,
        publisher = publisher,
        animated = animated,
        imageDataVersion = "1",
        trayImageFile = "tray.png",
        stickers = stickers,
        createdAt = "2026-11-27T12:00:00Z",
        updatedAt = "2026-11-27T12:00:00Z",
    )

    private fun writeFile(root: File, relativePath: String, bytes: ByteArray) {
        val target = File(root, relativePath)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }
}
