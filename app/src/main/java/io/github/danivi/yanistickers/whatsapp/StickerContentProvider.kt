package io.github.danivi.yanistickers.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.danivi.yanistickers.data.LocalPackStore
import io.github.danivi.yanistickers.data.PackJsonParser
import io.github.danivi.yanistickers.data.StickerPack
import java.io.FileNotFoundException

/**
 * Expone a WhatsApp y WhatsApp Business los packs de stickers guardados en `filesDir/packs/`.
 */
class StickerContentProvider : ContentProvider() {

    private lateinit var authority: String
    private lateinit var uriMatcher: UriMatcher
    private lateinit var packStore: LocalPackStore

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        authority = WhatsAppContract.authority(ctx)
        packStore = LocalPackStore(ctx)
        uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, WhatsAppContract.METADATA, CODE_METADATA_ALL)
            addURI(authority, "${WhatsAppContract.METADATA}/*", CODE_METADATA_SINGLE)
            addURI(authority, "${WhatsAppContract.STICKERS}/*", CODE_STICKERS)
            addURI(authority, "${WhatsAppContract.STICKERS_ASSET}/*/*", CODE_STICKERS_ASSET)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        return when (uriMatcher.match(uri)) {
            CODE_METADATA_ALL -> buildMetadataCursor(uri, packStore.listPacks())
            CODE_METADATA_SINGLE -> {
                val identifier = uri.lastPathSegment.orEmpty()
                val packs = listOfNotNull(packStore.getPack(identifier))
                buildMetadataCursor(uri, packs)
            }
            CODE_STICKERS -> {
                val identifier = uri.lastPathSegment.orEmpty()
                buildStickersCursor(uri, packStore.getPack(identifier))
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    override fun getType(uri: Uri): String {
        return when (uriMatcher.match(uri)) {
            CODE_METADATA_ALL ->
                "vnd.android.cursor.dir/vnd.$authority.${WhatsAppContract.METADATA}"
            CODE_METADATA_SINGLE ->
                "vnd.android.cursor.item/vnd.$authority.${WhatsAppContract.METADATA}"
            CODE_STICKERS ->
                "vnd.android.cursor.dir/vnd.$authority.${WhatsAppContract.STICKERS}"
            CODE_STICKERS_ASSET -> {
                val fileName = uri.lastPathSegment.orEmpty()
                if (fileName.endsWith(".png", ignoreCase = true)) "image/png" else "image/webp"
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        if (uriMatcher.match(uri) != CODE_STICKERS_ASSET) {
            throw FileNotFoundException("Unsupported asset URI: $uri")
        }
        if (mode != "r") {
            throw SecurityException("Read-only provider: mode '$mode' is not allowed")
        }
        val segments = uri.pathSegments
        if (segments.size != 3) {
            throw FileNotFoundException("Expected 3 path segments, got ${segments.size}: $uri")
        }
        val identifier = segments[1]
        val fileName = segments[2]
        val file = packStore.resolveAssetFile(identifier, fileName)
            ?: throw FileNotFoundException("Sticker asset not found: $identifier/$fileName")

        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Insert is not supported")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("Delete is not supported")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        throw UnsupportedOperationException("Update is not supported")
    }

    private fun buildMetadataCursor(uri: Uri, packs: List<StickerPack>): Cursor {
        val cursor = MatrixCursor(WhatsAppContract.METADATA_COLUMNS, packs.size)
        for (pack in packs) {
            cursor.newRow()
                .add(pack.identifier)
                .add(pack.name)
                .add(pack.publisher)
                .add(pack.trayImageFile)
                .add("") // android_play_store_link
                .add("") // ios_app_download_link
                .add("") // sticker_pack_publisher_email
                .add("") // sticker_pack_publisher_website
                .add("") // sticker_pack_privacy_policy_website
                .add("") // sticker_pack_license_agreement_website
                .add(pack.imageDataVersion)
                .add(if (pack.avoidCache) 1 else 0)
                .add(if (pack.animated) 1 else 0)
        }
        return cursor.withNotificationUri(uri)
    }

    private fun buildStickersCursor(uri: Uri, pack: StickerPack?): Cursor {
        val stickers = pack?.stickers.orEmpty()
        val cursor = MatrixCursor(WhatsAppContract.STICKER_COLUMNS, stickers.size)
        for (sticker in stickers) {
            val fileName = PackJsonParser.extractStickerAssetName(sticker.file) ?: continue
            cursor.newRow()
                .add(fileName)
                .add(sticker.emojis.joinToString(","))
                .add(sticker.accessibilityText)
        }
        return cursor.withNotificationUri(uri)
    }

    private fun MatrixCursor.withNotificationUri(uri: Uri): MatrixCursor {
        context?.contentResolver?.let { setNotificationUri(it, uri) }
        return this
    }

    private companion object {
        const val CODE_METADATA_ALL = 1
        const val CODE_METADATA_SINGLE = 2
        const val CODE_STICKERS = 3
        const val CODE_STICKERS_ASSET = 4
    }
}
