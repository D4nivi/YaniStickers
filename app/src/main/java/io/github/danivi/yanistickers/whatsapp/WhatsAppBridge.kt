package io.github.danivi.yanistickers.whatsapp

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import io.github.danivi.yanistickers.data.StickerPack

enum class WhatsAppTarget(val packageName: String, val displayName: String) {
    CONSUMER(WhatsAppContract.CONSUMER_PACKAGE, "WhatsApp"),
    BUSINESS(WhatsAppContract.BUSINESS_PACKAGE, "WhatsApp Business"),
}

data class PackWhitelistStatus(
    val inConsumer: Boolean = false,
    val inBusiness: Boolean = false,
) {
    val isAddedAnywhere: Boolean get() = inConsumer || inBusiness
}

/**
 * Utilidades para detectar clientes de WhatsApp instalados, consultar si un pack ya está añadido
 * y construir el intent `ENABLE_STICKER_PACK`.
 */
object WhatsAppBridge {

    fun installedTargets(context: Context): List<WhatsAppTarget> {
        val pm = context.packageManager
        return WhatsAppTarget.entries.filter { isPackageInstalled(pm, it.packageName) }
    }

    fun buildAddPackIntent(
        context: Context,
        pack: StickerPack,
        targetPackage: String? = null,
    ): Intent {
        return Intent(WhatsAppContract.ACTION_ENABLE_STICKER_PACK).apply {
            putExtra(WhatsAppContract.EXTRA_STICKER_PACK_ID, pack.identifier)
            putExtra(WhatsAppContract.EXTRA_STICKER_PACK_AUTHORITY, WhatsAppContract.authority(context))
            putExtra(WhatsAppContract.EXTRA_STICKER_PACK_NAME, pack.name)
            if (!targetPackage.isNullOrBlank()) {
                setPackage(targetPackage)
            }
        }
    }

    fun getWhitelistStatus(context: Context, identifier: String): PackWhitelistStatus {
        return PackWhitelistStatus(
            inConsumer = isWhitelistedIn(context, identifier, WhatsAppContract.CONSUMER_PACKAGE),
            inBusiness = isWhitelistedIn(context, identifier, WhatsAppContract.BUSINESS_PACKAGE),
        )
    }

    private fun isWhitelistedIn(
        context: Context,
        identifier: String,
        whatsappPackage: String,
    ): Boolean {
        val pm = context.packageManager
        if (!isPackageInstalled(pm, whatsappPackage)) return false

        val whitelistAuthority = "$whatsappPackage${WhatsAppContract.WHITELIST_PROVIDER_SUFFIX}"
        val providerInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveContentProvider(whitelistAuthority, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveContentProvider(whitelistAuthority, 0)
        } ?: return false

        if (!providerInfo.exported) return false

        val queryUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(whitelistAuthority)
            .path(WhatsAppContract.WHITELIST_QUERY_PATH)
            .appendQueryParameter(
                WhatsAppContract.WHITELIST_PARAM_AUTHORITY,
                WhatsAppContract.authority(context),
            )
            .appendQueryParameter(WhatsAppContract.WHITELIST_PARAM_IDENTIFIER, identifier)
            .build()

        return runCatching {
            context.contentResolver.query(queryUri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val colIndex = cursor.getColumnIndex(WhatsAppContract.WHITELIST_RESULT_COLUMN)
                colIndex >= 0 && cursor.getInt(colIndex) == 1
            } ?: false
        }.getOrDefault(false)
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }
}
