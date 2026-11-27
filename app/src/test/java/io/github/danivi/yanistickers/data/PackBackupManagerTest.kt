package io.github.danivi.yanistickers.data

import io.github.danivi.yanistickers.validation.PackValidator
import io.github.danivi.yanistickers.validation.TestImageBuilders
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class PackBackupManagerTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun exportAndImportRoundTrip_preservesIdentifiersVersionsAndAssets() {
        val sourceStore = LocalPackStore(tempFolder.newFolder("source_packs"))
        val staticPack = seedPack(
            store = sourceStore,
            identifier = "pack_estatico_1",
            name = "Pack Estático",
            publisher = "Yani",
            animated = false,
            stickerPrefix = "s",
            imageDataVersion = "4",
        )
        val animatedPack = seedPack(
            store = sourceStore,
            identifier = "pack_animado_2",
            name = "Pack Animado",
            publisher = "Dani",
            animated = true,
            stickerPrefix = "a",
            imageDataVersion = "7",
        )

        val zipBytes = ByteArrayOutputStream().use { baos ->
            val manifest = PackBackupManager.exportToZip(sourceStore, baos)
            assertEquals(PackBackupManager.SCHEMA_VERSION, manifest.schemaVersion)
            assertEquals(setOf(staticPack.identifier, animatedPack.identifier), manifest.packs.toSet())
            baos.toByteArray()
        }

        // Comprobar estructura interna del .zip
        val entryNames = readZipEntryNames(zipBytes)
        assertTrue(PackBackupManager.MANIFEST_FILE in entryNames)
        assertTrue("${staticPack.identifier}/pack.json" in entryNames)
        assertTrue("${staticPack.identifier}/${staticPack.trayImageFile}" in entryNames)
        assertTrue("${staticPack.identifier}/stickers/s1.webp" in entryNames)
        assertTrue("${animatedPack.identifier}/stickers/a3.webp" in entryNames)

        // Simular reinstalación en almacenamiento vacío
        val targetStore = LocalPackStore(tempFolder.newFolder("restored_packs"))
        val stagingDir = tempFolder.newFolder("staging_roundtrip")
        val staged = PackBackupManager.stageBackup(
            store = targetStore,
            inputStream = ByteArrayInputStream(zipBytes),
            stagingRootDir = stagingDir,
        )
        assertEquals(0, staged.conflicts.size)
        assertEquals(2, staged.entries.size)

        val summary = PackBackupManager.commitStagedBackup(targetStore, staged, emptyMap())
        assertFalse(stagingDir.exists())
        assertEquals(2, summary.results.size)
        assertTrue(summary.results.all { it.outcome == ImportOutcome.IMPORTED })
        assertTrue(summary.results.all { it.validation.isValid })

        val restoredStatic = targetStore.getPack(staticPack.identifier)!!
        assertEquals(staticPack.identifier, restoredStatic.identifier)
        assertEquals("4", restoredStatic.imageDataVersion)
        assertEquals(staticPack.stickers, restoredStatic.stickers)
        assertTrue(PackValidator.validate(targetStore, restoredStatic).isValid)

        val restoredAnimated = targetStore.getPack(animatedPack.identifier)!!
        assertEquals(animatedPack.identifier, restoredAnimated.identifier)
        assertEquals("7", restoredAnimated.imageDataVersion)
        assertTrue(restoredAnimated.animated)
        assertTrue(PackValidator.validate(targetStore, restoredAnimated).isValid)

        // Los bytes de stickers se mantienen idénticos
        val origSticker = sourceStore.resolveAssetFile(staticPack.identifier, "s2.webp")!!
        val restoredSticker = targetStore.resolveAssetFile(staticPack.identifier, "s2.webp")!!
        assertArrayEquals(origSticker.readBytes(), restoredSticker.readBytes())
    }

    @Test
    fun exportSinglePack_restoresOnlySelectedPack() {
        val sourceStore = LocalPackStore(tempFolder.newFolder("single_source"))
        val p1 = seedPack(sourceStore, "uno", "Pack Uno", "Yani", false, "u", "2")
        seedPack(sourceStore, "dos", "Pack Dos", "Yani", false, "d", "3")

        val zipBytes = ByteArrayOutputStream().use { baos ->
            val manifest = PackBackupManager.exportToZip(sourceStore, baos, listOf(p1.identifier))
            assertEquals(listOf("uno"), manifest.packs)
            baos.toByteArray()
        }

        val targetStore = LocalPackStore(tempFolder.newFolder("single_target"))
        val staged = PackBackupManager.stageBackup(
            store = targetStore,
            inputStream = ByteArrayInputStream(zipBytes),
            stagingRootDir = tempFolder.newFolder("single_staging"),
        )
        PackBackupManager.commitStagedBackup(targetStore, staged, emptyMap())

        assertEquals(listOf("uno"), targetStore.listPacks().map { it.identifier })
    }

    @Test
    fun conflictResolutions_replaceDuplicateAndSkipBehaveAsExpected() {
        val backupStore = LocalPackStore(tempFolder.newFolder("conflict_backup_src"))
        seedPack(backupStore, "pack_replace", "Nuevo Replace", "Yani", false, "newr", "5")
        seedPack(backupStore, "pack_dup", "Nuevo Dup", "Yani", false, "newd", "2")
        seedPack(backupStore, "pack_skip", "Nuevo Skip", "Yani", false, "news", "9")

        val zipBytes = ByteArrayOutputStream().use { baos ->
            PackBackupManager.exportToZip(backupStore, baos)
            baos.toByteArray()
        }

        val targetStore = LocalPackStore(tempFolder.newFolder("conflict_target"))
        seedPack(targetStore, "pack_replace", "Viejo Replace", "Dani", false, "oldr", "8")
        seedPack(targetStore, "pack_dup", "Viejo Dup", "Dani", false, "oldd", "1")
        seedPack(targetStore, "pack_skip", "Viejo Skip", "Dani", false, "olds", "3")

        val stagingDir = tempFolder.newFolder("conflict_staging")
        val staged = PackBackupManager.stageBackup(
            store = targetStore,
            inputStream = ByteArrayInputStream(zipBytes),
            stagingRootDir = stagingDir,
        )
        assertEquals(3, staged.conflicts.size)

        val summary = PackBackupManager.commitStagedBackup(
            store = targetStore,
            staged = staged,
            resolutions = mapOf(
                "pack_replace" to ConflictResolution.REPLACE,
                "pack_dup" to ConflictResolution.DUPLICATE_NEW_ID,
                "pack_skip" to ConflictResolution.SKIP,
            ),
        )
        assertFalse(stagingDir.exists())

        val byOutcome = summary.results.associateBy { it.outcome }
        // REPLACE: mismo identifier, versión > max(8, 5), archivos antiguos limpiados
        val replaced = byOutcome[ImportOutcome.REPLACED]!!.pack
        assertEquals("pack_replace", replaced.identifier)
        assertEquals("Nuevo Replace", replaced.name)
        assertEquals("9", replaced.imageDataVersion)
        assertNull(targetStore.resolveAssetFile("pack_replace", "oldr1.webp"))
        assertNotNull(targetStore.resolveAssetFile("pack_replace", "newr1.webp"))

        // DUPLICATE_NEW_ID: nuevo identifier único, versión 1, original intacto
        val duplicated = byOutcome[ImportOutcome.DUPLICATED]!!.pack
        assertNotEquals("pack_dup", duplicated.identifier)
        assertEquals("1", duplicated.imageDataVersion)
        assertEquals("Nuevo Dup", duplicated.name)
        assertEquals("Viejo Dup", targetStore.getPack("pack_dup")!!.name)

        // SKIP: original intacto
        assertEquals(ImportOutcome.SKIPPED, byOutcome[ImportOutcome.SKIPPED]!!.outcome)
        assertEquals("Viejo Skip", targetStore.getPack("pack_skip")!!.name)
        assertEquals("3", targetStore.getPack("pack_skip")!!.imageDataVersion)
        assertEquals(4, targetStore.listPacks().size)
    }

    @Test
    fun stageBackup_blocksZipSlipEntriesAndCleansStaging() {
        val targetStore = LocalPackStore(tempFolder.newFolder("zipslip_target"))
        val outsideMarker = File(tempFolder.root, "evil.txt")
        assertFalse(outsideMarker.exists())

        val traversalPayloads = listOf(
            "../../evil.txt",
            "pack_a/../../evil.txt",
            "pack_a\\..\\..\\evil.txt",
            "/abs/evil.txt",
        )

        for ((idx, maliciousName) in traversalPayloads.withIndex()) {
            val zipBytes = buildCustomZip(
                maliciousName to "pwned".toByteArray(),
            )
            val stagingDir = File(tempFolder.root, "zipslip_stage_$idx")
            try {
                PackBackupManager.stageBackup(
                    store = targetStore,
                    inputStream = ByteArrayInputStream(zipBytes),
                    stagingRootDir = stagingDir,
                )
                fail("Debió rechazar la entrada zip-slip: $maliciousName")
            } catch (_: BackupException) {
                assertFalse(outsideMarker.exists())
                assertFalse(stagingDir.exists())
            }
        }
    }

    @Test
    fun stageBackup_rejectsInvalidSchemaMismatchOrMissingFiles() {
        val targetStore = LocalPackStore(tempFolder.newFolder("invalid_target"))

        // 1. Archivo no-ZIP / sin manifest.json
        assertStageFails(targetStore, "no es un zip".toByteArray(), "bad_bytes")

        // 2. schemaVersion incompatible
        val badSchemaManifest = JSONObject()
            .put("schemaVersion", 99)
            .put("packs", listOf("p1"))
            .toString()
        assertStageFails(
            targetStore,
            buildCustomZip("manifest.json" to badSchemaManifest.toByteArray()),
            "bad_schema",
        )

        // 3. Identifier en pack.json distinto al de la carpeta
        val validManifest = PackBackupManager.manifestToJson(
            BackupManifest(1, listOf("carpeta_a"), "2026-11-27T12:00:00Z"),
        )
        val mismatchedPackJson = PackJsonParser.toJson(
            StickerPack(
                schemaVersion = 1,
                identifier = "otro_id",
                name = "Test",
                publisher = "Yani",
                animated = false,
                imageDataVersion = "1",
                trayImageFile = "tray.png",
                stickers = emptyList(),
                createdAt = "2026-11-27T12:00:00Z",
                updatedAt = "2026-11-27T12:00:00Z",
            ),
        )
        assertStageFails(
            targetStore,
            buildCustomZip(
                "manifest.json" to validManifest.toByteArray(),
                "carpeta_a/pack.json" to mismatchedPackJson.toByteArray(),
                "carpeta_a/tray.png" to TestImageBuilders.png(96, 96),
            ),
            "mismatch_id",
        )

        // 4. Sticker referenciado ausente en el .zip
        val packWithMissingSticker = PackJsonParser.toJson(
            StickerPack(
                schemaVersion = 1,
                identifier = "carpeta_a",
                name = "Test",
                publisher = "Yani",
                animated = false,
                imageDataVersion = "1",
                trayImageFile = "tray.png",
                stickers = listOf(StickerItem("stickers/falta.webp", listOf("😀"))),
                createdAt = "2026-11-27T12:00:00Z",
                updatedAt = "2026-11-27T12:00:00Z",
            ),
        )
        assertStageFails(
            targetStore,
            buildCustomZip(
                "manifest.json" to validManifest.toByteArray(),
                "carpeta_a/pack.json" to packWithMissingSticker.toByteArray(),
                "carpeta_a/tray.png" to TestImageBuilders.png(96, 96),
            ),
            "missing_sticker",
        )
    }

    private fun assertStageFails(store: LocalPackStore, zipBytes: ByteArray, label: String) {
        val stagingDir = File(tempFolder.root, "stage_fail_$label")
        try {
            PackBackupManager.stageBackup(
                store = store,
                inputStream = ByteArrayInputStream(zipBytes),
                stagingRootDir = stagingDir,
            )
            fail("Se esperaba BackupException para '$label'.")
        } catch (_: BackupException) {
            assertFalse(stagingDir.exists())
        }
    }

    private fun seedPack(
        store: LocalPackStore,
        identifier: String,
        name: String,
        publisher: String,
        animated: Boolean,
        stickerPrefix: String,
        imageDataVersion: String,
    ): StickerPack {
        val created = store.createPack(
            name = name,
            publisher = publisher,
            animated = animated,
            customIdentifier = identifier,
        )
        val packDir = store.getPackDirectory(identifier)!!
        val stickersDir = File(packDir, LocalPackStore.STICKERS_DIR_NAME)
        val items = (1..3).map { idx ->
            val fileName = "$stickerPrefix$idx.webp"
            val bytes = if (animated) {
                TestImageBuilders.animatedWebp(512, 512, listOf(60, 80))
            } else {
                TestImageBuilders.staticVp8lWebp(512, 512)
            }
            File(stickersDir, fileName).writeBytes(bytes)
            StickerItem(
                file = "stickers/$fileName",
                emojis = listOf("😀"),
                accessibilityText = "Sticker $idx",
            )
        }
        val finalized = created.copy(
            imageDataVersion = imageDataVersion,
            stickers = items,
        )
        File(packDir, LocalPackStore.PACK_JSON_FILE).writeText(
            PackJsonParser.toJson(finalized),
            Charsets.UTF_8,
        )
        return finalized
    }

    private fun buildCustomZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        return ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                for ((name, bytes) in entries) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
            baos.toByteArray()
        }
    }

    private fun readZipEntryNames(zipBytes: ByteArray): Set<String> {
        val names = linkedSetOf<String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                names += entry.name
                zis.closeEntry()
            }
        }
        return names
    }
}
