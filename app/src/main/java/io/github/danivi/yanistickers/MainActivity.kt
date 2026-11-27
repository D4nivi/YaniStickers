package io.github.danivi.yanistickers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.danivi.yanistickers.data.LocalPackStore
import io.github.danivi.yanistickers.data.SeedPackInstaller
import io.github.danivi.yanistickers.ui.PackCardUiState
import io.github.danivi.yanistickers.ui.Phase1HomeScreen
import io.github.danivi.yanistickers.ui.theme.YaniStickersTheme
import io.github.danivi.yanistickers.whatsapp.WhatsAppBridge
import io.github.danivi.yanistickers.whatsapp.WhatsAppTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YaniStickersTheme {
                var refreshTrigger by remember { mutableIntStateOf(0) }
                var isLoading by remember { mutableStateOf(true) }
                var packs by remember { mutableStateOf<List<PackCardUiState>>(emptyList()) }
                var installedTargets by remember { mutableStateOf<List<WhatsAppTarget>>(emptyList()) }

                LaunchedEffect(refreshTrigger) {
                    isLoading = true
                    val loaded = withContext(Dispatchers.IO) {
                        val appCtx = applicationContext
                        SeedPackInstaller.ensureSeedPacksInstalled(appCtx)
                        val store = LocalPackStore(appCtx)
                        val targets = WhatsAppBridge.installedTargets(appCtx)
                        val uiPacks = store.listPacks().map { pack ->
                            PackCardUiState(
                                pack = pack,
                                trayFile = store.resolveAssetFile(pack.identifier, pack.trayImageFile),
                                whitelistStatus = WhatsAppBridge.getWhitelistStatus(appCtx, pack.identifier),
                            )
                        }
                        uiPacks to targets
                    }
                    packs = loaded.first
                    installedTargets = loaded.second
                    isLoading = false
                }

                Phase1HomeScreen(
                    packs = packs,
                    installedTargets = installedTargets,
                    isLoading = isLoading,
                    onRefresh = { refreshTrigger++ },
                )
            }
        }
    }
}