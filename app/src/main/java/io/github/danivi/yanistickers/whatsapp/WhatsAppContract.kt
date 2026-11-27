package io.github.danivi.yanistickers.whatsapp

import android.content.Context

/**
 * Constantes alineadas con el contrato oficial de ContentProvider e Intent de `WhatsApp/stickers`.
 */
object WhatsAppContract {

    const val METADATA = "metadata"
    const val STICKERS = "stickers"
    const val STICKERS_ASSET = "stickers_asset"

    const val COL_PACK_IDENTIFIER = "sticker_pack_identifier"
    const val COL_PACK_NAME = "sticker_pack_name"
    const val COL_PACK_PUBLISHER = "sticker_pack_publisher"
    const val COL_PACK_ICON = "sticker_pack_icon"
    const val COL_ANDROID_PLAY_STORE_LINK = "android_play_store_link"
    const val COL_IOS_APP_DOWNLOAD_LINK = "ios_app_download_link"
    const val COL_PUBLISHER_EMAIL = "sticker_pack_publisher_email"
    const val COL_PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
    const val COL_PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
    const val COL_LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
    const val COL_IMAGE_DATA_VERSION = "image_data_version"
    const val COL_AVOID_CACHE = "whatsapp_will_not_cache_stickers"
    const val COL_ANIMATED_PACK = "animated_sticker_pack"

    val METADATA_COLUMNS = arrayOf(
        COL_PACK_IDENTIFIER,
        COL_PACK_NAME,
        COL_PACK_PUBLISHER,
        COL_PACK_ICON,
        COL_ANDROID_PLAY_STORE_LINK,
        COL_IOS_APP_DOWNLOAD_LINK,
        COL_PUBLISHER_EMAIL,
        COL_PUBLISHER_WEBSITE,
        COL_PRIVACY_POLICY_WEBSITE,
        COL_LICENSE_AGREEMENT_WEBSITE,
        COL_IMAGE_DATA_VERSION,
        COL_AVOID_CACHE,
        COL_ANIMATED_PACK,
    )

    const val COL_STICKER_FILE_NAME = "sticker_file_name"
    const val COL_STICKER_EMOJI = "sticker_emoji"
    const val COL_STICKER_ACCESSIBILITY_TEXT = "sticker_accessibility_text"

    val STICKER_COLUMNS = arrayOf(
        COL_STICKER_FILE_NAME,
        COL_STICKER_EMOJI,
        COL_STICKER_ACCESSIBILITY_TEXT,
    )

    const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    const val EXTRA_STICKER_PACK_ID = "sticker_pack_id"
    const val EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority"
    const val EXTRA_STICKER_PACK_NAME = "sticker_pack_name"
    const val EXTRA_VALIDATION_ERROR = "validation_error"

    const val CONSUMER_PACKAGE = "com.whatsapp"
    const val BUSINESS_PACKAGE = "com.whatsapp.w4b"

    const val WHITELIST_PROVIDER_SUFFIX = ".provider.sticker_whitelist_check"
    const val WHITELIST_QUERY_PATH = "is_whitelisted"
    const val WHITELIST_PARAM_AUTHORITY = "authority"
    const val WHITELIST_PARAM_IDENTIFIER = "identifier"
    const val WHITELIST_RESULT_COLUMN = "result"

    fun authority(context: Context): String = "${context.packageName}.stickercontentprovider"
}
