package io.github.danivi.yanistickers

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.danivi.yanistickers.ui.AppScreen
import io.github.danivi.yanistickers.ui.BackupScreen
import io.github.danivi.yanistickers.ui.BackupViewModel
import io.github.danivi.yanistickers.ui.PackEditorScreen
import io.github.danivi.yanistickers.ui.PackEditorViewModel
import io.github.danivi.yanistickers.ui.PackListScreen
import io.github.danivi.yanistickers.ui.PackListViewModel
import io.github.danivi.yanistickers.ui.theme.YaniStickersTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YaniStickersTheme {
                val app = LocalContext.current.applicationContext as Application
                val backStack = remember { mutableStateListOf<AppScreen>(AppScreen.PackList) }
                val currentScreen = backStack.last()

                BackHandler(enabled = backStack.size > 1) {
                    backStack.removeAt(backStack.lastIndex)
                }

                when (currentScreen) {
                    is AppScreen.PackList -> {
                        val listViewModel: PackListViewModel = viewModel(
                            factory = PackListViewModel.factory(app),
                        )
                        LaunchedEffect(currentScreen) {
                            listViewModel.refresh()
                        }
                        PackListScreen(
                            viewModel = listViewModel,
                            onOpenEditor = { packId -> backStack.add(AppScreen.PackEditor(packId)) },
                            onOpenBackup = { backStack.add(AppScreen.Backup) },
                        )
                    }
                    is AppScreen.PackEditor -> {
                        val editorViewModel: PackEditorViewModel = viewModel(
                            key = "editor_${currentScreen.packId}",
                            factory = PackEditorViewModel.factory(app, currentScreen.packId),
                        )
                        LaunchedEffect(currentScreen) {
                            editorViewModel.refresh()
                        }
                        PackEditorScreen(
                            viewModel = editorViewModel,
                            onBack = {
                                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                            },
                        )
                    }
                    is AppScreen.Backup -> {
                        val backupViewModel: BackupViewModel = viewModel(
                            factory = BackupViewModel.factory(app),
                        )
                        LaunchedEffect(currentScreen) {
                            backupViewModel.refresh()
                        }
                        BackupScreen(
                            viewModel = backupViewModel,
                            onBack = {
                                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                            },
                        )
                    }
                }
            }
        }
    }
}
