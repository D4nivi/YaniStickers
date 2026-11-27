package io.github.danivi.yanistickers.data

import io.github.danivi.yanistickers.validation.ImageContainerFormat
import io.github.danivi.yanistickers.validation.ImageContainerInspector
import io.github.danivi.yanistickers.validation.PackValidator
import io.github.danivi.yanistickers.validation.TestImageBuilders
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalPackStoreTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun createEditDuplicateAndDeletePack_updatesVersionAndDiskCleanly() {
        val packsRoot = tempFolder.newFolder("packs")
        val store = LocalPackStore(packsRoot)

        // 1. Crear pack vacío nuevo
        val created = store.createPack(name = "Mi Pack Ñoño!", publisher = "Yani", animated = false)
        assertTrue(PackJsonParser.isSafeIdentifier(created.identifier))
        assertEquals("1", created.imageDataVersion)
        assertEquals(0, created.stickers.size)

        val trayFile = store.resolveAssetFile(created.identifier, created.trayImageFile)
        assertNotNull(trayFile)
        val trayInfo = ImageContainerInspector.inspect(trayFile!!)
        assertNotNull(trayInfo)
        assertEquals(ImageContainerFormat.PNG, trayInfo!!.format)
        assertEquals(96, trayInfo.width)
        assertEquals(96, trayInfo.height)

        // 2. Añadir manualmente 3 stickers al pack para probar edición/reordenado/eliminación
        val packDir = store.getPackDirectory(created.identifier)!!
        val stickerDir = File(packDir, LocalPackStore.STICKERS_DIR_NAME)
        (1..3).forEach { idx ->
            File(stickerDir, "s$idx.webp").writeBytes(TestImageBuilders.staticVp8lWebp(512, 512))
        }
        val seeded = created.copy(
            stickers = listOf(
                StickerItem("stickers/s1.webp", listOf("😀")),
                StickerItem("stickers/s2.webp", listOf("😂")),
                StickerItem("stickers/s3.webp", listOf("❤️")),
            ),
        )
        File(packDir, LocalPackStore.PACK_JSON_FILE).writeText(PackJsonParser.toJson(seeded), Charsets.UTF_8)

        // 3. Editar metadatos sube imageDataVersion a 2 sin cambiar identifier
        val editedMeta = store.updateMetadata(created.identifier, name = "Nombre Nuevo", publisher = "Dani")!!
        assertEquals(created.identifier, editedMeta.identifier)
        assertEquals("2", editedMeta.imageDataVersion)
        assertEquals("Nombre Nuevo", editedMeta.name)
        assertEquals("Dani", editedMeta.publisher)

        // 4. Editar sticker sube versión a 3
        val editedSticker = store.updateSticker(
            identifier = created.identifier,
            stickerIndex = 0,
            emojis = listOf("🔥", "✨"),
            accessibilityText = "Sticker de fuego",
        )!!
        assertEquals("3", editedSticker.imageDataVersion)
        assertEquals(listOf("🔥", "✨"), editedSticker.stickers[0].emojis)
        assertEquals("Sticker de fuego", editedSticker.stickers[0].accessibilityText)

        // 5. Reordenar stickers sube versión a 4
        val reordered = store.reorderStickers(created.identifier, fromIndex = 0, toIndex = 2)!!
        assertEquals("4", reordered.imageDataVersion)
        assertEquals(
            listOf("stickers/s2.webp", "stickers/s3.webp", "stickers/s1.webp"),
            reordered.stickers.map { it.file },
        )

        // 6. Duplicar crea nuevo identifier con copia independiente de archivos
        val duplicated = store.duplicatePack(created.identifier, newName = "Copia")!!
        assertNotEquals(created.identifier, duplicated.identifier)
        assertEquals("1", duplicated.imageDataVersion)
        assertEquals(3, duplicated.stickers.size)
        assertNotNull(store.resolveAssetFile(duplicated.identifier, "s2.webp"))

        // 7. Eliminar sticker borra el archivo huérfano en disco del pack original y sube versión
        val afterRemove = store.removeSticker(created.identifier, stickerIndex = 0)!!
        assertEquals("5", afterRemove.imageDataVersion)
        assertEquals(2, afterRemove.stickers.size)
        assertFalse(File(stickerDir, "s2.webp").exists())
        // La copia duplicada conserva su archivo intacto
        assertNotNull(store.resolveAssetFile(duplicated.identifier, "s2.webp"))

        // 8. Bloqueo de traversal en resolveAssetFile
        assertNull(store.resolveAssetFile(created.identifier, "../pack.json"))

        // 9. Eliminar packs
        assertTrue(store.deletePack(created.identifier))
        assertNull(store.getPack(created.identifier))
        assertEquals(1, store.listPacks().size)
    }

    @Test
    fun splitEmojisGraphemes_preservesCompoundEmojisAndLimitsCleanly() {
        assertEquals(listOf("😀", "🎉", "❤️"), PackJsonParser.splitEmojisGraphemes("😀🎉❤️"))
        assertEquals(listOf("👍🏽", "🔥"), PackJsonParser.splitEmojisGraphemes("👍🏽, 🔥"))
        assertEquals(listOf("🗣️", "😈"), PackJsonParser.splitEmojisGraphemes("hola 🗣️ 123 😈"))
        assertEquals("😀 🗣️", PackJsonParser.filterEmojiInput("abc 😀 xyz 🗣️ 😈 😂", maxEmojis = 2))
        assertEquals(emptyList<String>(), PackJsonParser.splitEmojisGraphemes("texto normal 123"))
        assertEquals(emptyList<String>(), PackJsonParser.splitEmojisGraphemes("   "))
    }

    @Test
    fun addStickerAndUpdateTrayIcon_persistsAssetsAndEnforcesMaxLimit() {
        val packsRoot = tempFolder.newFolder("packs_add")
        val store = LocalPackStore(packsRoot)
        val created = store.createPack(name = "Estático", publisher = "Yani", animated = false)

        // Cambiar icono de bandeja sustituyendo el archivo anterior si cambia de nombre
        val customTray = TestImageBuilders.staticVp8lWebp(96, 96)
        val afterTray = store.updateTrayIcon(created.identifier, customTray, fileName = "custom_tray.webp")!!
        assertEquals("2", afterTray.imageDataVersion)
        assertEquals("custom_tray.webp", afterTray.trayImageFile)
        assertNull(store.resolveAssetFile(created.identifier, "tray.webp"))
        assertNotNull(store.resolveAssetFile(created.identifier, "custom_tray.webp"))

        // Añadir hasta el límite de 30 stickers
        val stickerBytes = TestImageBuilders.staticVp8lWebp(512, 512)
        var latest = afterTray
        repeat(LocalPackStore.MAX_STICKERS_PER_PACK) { idx ->
            latest = store.addSticker(
                identifier = created.identifier,
                webpBytes = stickerBytes,
                emojis = listOf("😀"),
                accessibilityText = "Sticker $idx",
                name = "Mi sticker $idx",
            )!!
        }
        assertEquals(LocalPackStore.MAX_STICKERS_PER_PACK, latest.stickers.size)
        assertEquals("Mi sticker 0", latest.stickers.first().name)
        assertEquals((2 + LocalPackStore.MAX_STICKERS_PER_PACK).toString(), latest.imageDataVersion)

        // El sticker 31 debe ser rechazado
        try {
            store.addSticker(
                identifier = created.identifier,
                webpBytes = stickerBytes,
                emojis = listOf("🔥"),
            )
            org.junit.Assert.fail("No debería permitir más de ${LocalPackStore.MAX_STICKERS_PER_PACK} stickers.")
        } catch (_: IllegalArgumentException) {
            // Esperado
        }
    }

    @Test
    fun addSticker_replacesDefaultTrayOnlyOnFirstSticker() {
        val packsRoot = tempFolder.newFolder("packs_autotray")
        val autoTrayBytes = TestImageBuilders.staticVp8lWebp(96, 96)
        var conversionCount = 0
        val store = LocalPackStore(
            packsRootDir = packsRoot,
            stickerToTrayConverter = {
                conversionCount++
                autoTrayBytes
            },
        )

        val created = store.createPack(name = "AutoIcono", publisher = "Yani", animated = false)
        assertTrue(created.usesDefaultTray)

        // Primer sticker: sustituye el icono por defecto y marca usesDefaultTray = false
        val afterFirst = store.addSticker(
            identifier = created.identifier,
            webpBytes = TestImageBuilders.staticVp8lWebp(512, 512),
            emojis = listOf("😀"),
        )!!
        assertFalse(afterFirst.usesDefaultTray)
        assertEquals(1, conversionCount)
        assertArrayEquals(
            autoTrayBytes,
            store.resolveAssetFile(created.identifier, afterFirst.trayImageFile)!!.readBytes(),
        )

        // Segundo sticker: NO vuelve a sobrescribir el icono del pack
        val afterSecond = store.addSticker(
            identifier = created.identifier,
            webpBytes = TestImageBuilders.staticVp8lWebp(512, 512),
            emojis = listOf("😂"),
        )!!
        assertFalse(afterSecond.usesDefaultTray)
        assertEquals(1, conversionCount)
    }

    @Test
    fun addAnimatedStickers_promotesDefaultTrayAndPassesValidation() {
        val packsRoot = tempFolder.newFolder("packs_animated")
        val autoTrayBytes = TestImageBuilders.staticVp8lWebp(96, 96)
        val store = LocalPackStore(
            packsRootDir = packsRoot,
            stickerToTrayConverter = { autoTrayBytes },
        )
        val pack = store.createPack(name = "Pack Animado", publisher = "Yani", animated = true)
        val animBytes = TestImageBuilders.animatedWebp(512, 512, listOf(80, 80, 80))

        repeat(3) { idx ->
            store.addSticker(
                identifier = pack.identifier,
                webpBytes = animBytes,
                emojis = listOf("🎬"),
                accessibilityText = "Animado $idx",
            )
        }

        val loaded = store.getPack(pack.identifier)!!
        assertTrue(loaded.animated)
        assertFalse(loaded.usesDefaultTray)
        assertEquals(3, loaded.stickers.size)
        val report = PackValidator.validate(store, loaded)
        assertTrue(report.isValid)
    }
}
