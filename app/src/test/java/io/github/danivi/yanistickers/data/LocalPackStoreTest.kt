package io.github.danivi.yanistickers.data

import io.github.danivi.yanistickers.validation.ImageContainerFormat
import io.github.danivi.yanistickers.validation.ImageContainerInspector
import io.github.danivi.yanistickers.validation.TestImageBuilders
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
        assertEquals(emptyList<String>(), PackJsonParser.splitEmojisGraphemes("   "))
    }
}
