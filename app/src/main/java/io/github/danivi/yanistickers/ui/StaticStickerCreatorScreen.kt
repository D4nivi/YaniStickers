package io.github.danivi.yanistickers.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danivi.yanistickers.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaticStickerCreatorScreen(
    viewModel: StaticStickerCreatorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var launchedInitialPicker by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.onImageSelected(uri)
        }
    }

    fun openPhotoPicker() {
        pickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    LaunchedEffect(state.isEditMode) {
        if (!state.isEditMode && !launchedInitialPicker && state.sourceBitmap == null && !state.isDecoding) {
            launchedInitialPicker = true
            openPhotoPicker()
        }
    }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            onBack()
        }
    }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeMessage()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when {
                                state.forTray -> R.string.creator_tray_title
                                state.isEditMode -> R.string.creator_edit_static_title
                                else -> R.string.creator_static_title
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.sourceBitmap == null) {
                EmptyImageSelectionCard(
                    isDecoding = state.isDecoding,
                    forTray = state.forTray,
                    onPickImage = ::openPhotoPicker,
                )
            } else {
                StickerFramingCanvas(
                    bitmap = state.sourceBitmap!!,
                    transform = state.transform,
                    snapGuides = state.snapGuides,
                    isGestureActive = state.isGestureActive,
                    onTransformGesture = viewModel::applyGesture,
                    onTransformGestureEnd = viewModel::onTransformGestureEnd,
                )

                StickerFramingControlsRow(
                    mode = state.transform.mode,
                    onSelectMode = viewModel::setFramingMode,
                    onRotate90 = viewModel::rotate90,
                    onReset = viewModel::resetTransform,
                    onChangeSource = ::openPhotoPicker,
                    changeSourceIcon = Icons.Outlined.AddPhotoAlternate,
                    changeSourceContentDescription = "Cambiar imagen",
                )

                if (!state.forTray) {
                    AdvancedStickerMetadataCard(
                        expanded = showAdvanced,
                        onToggleExpanded = { showAdvanced = !showAdvanced },
                        nameText = state.nameInput,
                        onNameTextChange = viewModel::onNameChange,
                        emojisText = state.emojisInput,
                        onEmojisTextChange = viewModel::onEmojisChange,
                        accessibilityText = state.accessibilityInput,
                        onAccessibilityTextChange = viewModel::onAccessibilityChange,
                        animatedPack = false,
                    )
                }

                Button(
                    onClick = viewModel::save,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.isSaving) {
                            "Guardando..."
                        } else {
                            stringResource(
                                when {
                                    state.forTray -> R.string.action_save_tray
                                    state.isEditMode -> R.string.action_save_changes
                                    else -> R.string.action_save_sticker
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyImageSelectionCard(
    isDecoding: Boolean,
    forTray: Boolean,
    onPickImage: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isDecoding) {
                CircularProgressIndicator()
                Text("Cargando imagen...")
            } else {
                Icon(
                    imageVector = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (forTray) {
                        "Elige una imagen para el icono de bandeja (96×96 px)"
                    } else {
                        "Elige una imagen o PNG transparente para tu sticker (512×512 px)"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onPickImage) {
                    Text(stringResource(R.string.action_pick_image))
                }
            }
        }
    }
}
