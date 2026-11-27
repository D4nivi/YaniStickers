package io.github.danivi.yanistickers.whatsapp

import io.github.danivi.yanistickers.data.PackJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppContractTest {

    @Test
    fun extractStickerAssetName_stripsStickersPrefixAndRejectsUnsafePaths() {
        assertEquals("s1.webp", PackJsonParser.extractStickerAssetName("stickers/s1.webp"))
        assertEquals("a_1-test.webp", PackJsonParser.extractStickerAssetName("a_1-test.webp"))
        assertNull(PackJsonParser.extractStickerAssetName("../secret.webp"))
        assertNull(PackJsonParser.extractStickerAssetName("stickers/../secret.webp"))
        assertNull(PackJsonParser.extractStickerAssetName("stickers/sub/file.webp"))
        assertNull(PackJsonParser.extractStickerAssetName("stickers/has space.webp"))
        assertNull(PackJsonParser.extractStickerAssetName(""))
    }

    @Test
    fun safeIdentifier_acceptsExpectedIdsAndRejectsTraversal() {
        assertTrue(PackJsonParser.isSafeIdentifier("yani_test_static_v1"))
        assertTrue(PackJsonParser.isSafeIdentifier("pack-123.v2"))
        assertFalse(PackJsonParser.isSafeIdentifier(".."))
        assertFalse(PackJsonParser.isSafeIdentifier("."))
        assertFalse(PackJsonParser.isSafeIdentifier("pack/nested"))
        assertFalse(PackJsonParser.isSafeIdentifier("pack name"))
        assertFalse(PackJsonParser.isSafeIdentifier(""))
    }

    @Test
    fun metadataColumns_matchOfficialWhatsAppContract() {
        assertEquals(13, WhatsAppContract.METADATA_COLUMNS.size)
        assertEquals("sticker_pack_identifier", WhatsAppContract.METADATA_COLUMNS[0])
        assertEquals("whatsapp_will_not_cache_stickers", WhatsAppContract.METADATA_COLUMNS[11])
        assertEquals("animated_sticker_pack", WhatsAppContract.METADATA_COLUMNS[12])
        assertEquals(
            listOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text"),
            WhatsAppContract.STICKER_COLUMNS.toList(),
        )
    }
}
