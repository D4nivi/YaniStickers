package io.github.danivi.yanistickers.ui

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import io.github.danivi.yanistickers.R
import io.github.danivi.yanistickers.ui.theme.AddedBadgeBorder
import io.github.danivi.yanistickers.ui.theme.AddedBadgeContainer
import io.github.danivi.yanistickers.ui.theme.IconActionContainer
import io.github.danivi.yanistickers.ui.theme.OnAddedBadgeContainer
import io.github.danivi.yanistickers.ui.theme.OnIconActionContainer
import io.github.danivi.yanistickers.ui.theme.OnSecondaryBlueContainer
import io.github.danivi.yanistickers.ui.theme.PrimaryBlue
import io.github.danivi.yanistickers.ui.theme.PureWhite
import io.github.danivi.yanistickers.ui.theme.SecondaryBlueContainer
import io.github.danivi.yanistickers.ui.theme.VibrantRed
import io.github.danivi.yanistickers.update.GitHubUpdateClient
import io.github.danivi.yanistickers.update.UpdateReleaseInfo

/**
 * Botón del encabezado para abrir el menú «Acerca de», preparado para usar una imagen personalizada o un icono.
 */
@Composable
fun AboutHeaderButton(
    hasUpdate: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imagePainter: Painter? = null,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (imagePainter != null) {
                Image(
                    painter = imagePainter,
                    contentDescription = stringResource(R.string.action_about),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.action_about),
                )
            }

            if (hasUpdate) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(10.dp)
                        .background(color = VibrantRed, shape = CircleShape)
                        .border(width = 1.5.dp, color = PureWhite, shape = CircleShape),
                )
            }
        }
    }
}

/**
 * Popup «Acerca de» con el logo, creador, versión actual y gestión de actualizaciones desde GitHub Releases.
 */
@Composable
fun AboutUpdateDialog(
    updateState: AppUpdateUiState,
    onCheckUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
    onDismissPendingConfirmation: () -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onDismissInstallPermissionPrompt: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    if (updateState.needsInstallPermissionPrompt) {
        AlertDialog(
            onDismissRequest = onDismissInstallPermissionPrompt,
            title = { Text("Permitir instalación") },
            text = {
                Text(
                    "Para instalar actualizaciones directamente desde YaniStickers, activa «Permitir de esta fuente» " +
                        "en los ajustes de Android.",
                )
            },
            confirmButton = {
                Button(onClick = onOpenInstallPermissionSettings) {
                    Text("Abrir ajustes")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissInstallPermissionPrompt) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = {
            if (!updateState.isDownloading) {
                onDismiss()
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.size(92.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.app_logo),
                        contentDescription = stringResource(R.string.app_name),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp),
                    )
                }

                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = stringResource(R.string.about_version_format, updateState.currentVersionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                val creatorText = buildAnnotatedString {
                    append("Creado por Daniel Vidal (")
                    withLink(
                        LinkAnnotation.Url(
                            url = GitHubUpdateClient.REPO_URL,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        ),
                    ) {
                        append("D4nivi")
                    }
                    append(")")
                }
                Text(
                    text = creatorText,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )

                UpdateSection(
                    updateState = updateState,
                    formattedSize = { bytes -> Formatter.formatShortFileSize(context, bytes) },
                    onCheckUpdates = onCheckUpdates,
                    onInstallUpdate = onInstallUpdate,
                    onDismissPendingConfirmation = onDismissPendingConfirmation,
                )
            }
        },
        confirmButton = {
            if (!updateState.isDownloading) {
                FilledTonalButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = IconActionContainer,
                        contentColor = OnIconActionContainer,
                    ),
                ) {
                    Text("Cerrar")
                }
            }
        },
    )
}

@Composable
private fun UpdateSection(
    updateState: AppUpdateUiState,
    formattedSize: (Long) -> String,
    onCheckUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
    onDismissPendingConfirmation: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            updateState.isDownloading -> {
                val progress = updateState.downloadProgressPercent
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (progress != null) {
                                "Descargando actualización… $progress%"
                            } else {
                                "Descargando actualización…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            updateState.pendingConfirmationRelease != null -> {
                ReleaseConfirmationCard(
                    release = updateState.pendingConfirmationRelease,
                    formattedSize = formattedSize,
                    onConfirmInstall = onInstallUpdate,
                    onDismiss = onDismissPendingConfirmation,
                )
            }

            else -> {
                updateState.availableRelease?.let { release ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Nueva versión disponible: ${release.tagName}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            release.apkSizeBytes?.let { size ->
                                Text(
                                    text = "Tamaño del APK: ${formattedSize(size)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                updateState.statusMessage?.let { status ->
                    Surface(
                        color = AddedBadgeContainer,
                        contentColor = OnAddedBadgeContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AddedBadgeBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                updateState.errorMessage?.let { error ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                if (updateState.availableRelease != null) {
                    Button(
                        onClick = onInstallUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.update_action_install))
                    }
                } else {
                    FilledTonalButton(
                        onClick = onCheckUpdates,
                        enabled = !updateState.isChecking,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SecondaryBlueContainer,
                            contentColor = OnSecondaryBlueContainer,
                        ),
                        border = BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (updateState.isChecking) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.update_action_checking))
                        } else {
                            Text(stringResource(R.string.update_action_check))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseConfirmationCard(
    release: UpdateReleaseInfo,
    formattedSize: (Long) -> String,
    onConfirmInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "¡Nueva versión disponible: ${release.tagName}!",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            release.apkSizeBytes?.let { size ->
                Text(
                    text = "Tamaño de descarga: ${formattedSize(size)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (release.releaseNotes.isNotEmpty()) {
                Text(
                    text = release.releaseNotes,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            Text(
                text = "¿Quieres descargar e instalar esta actualización ahora?",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_action_later))
                }
                Button(onClick = onConfirmInstall) {
                    Text(stringResource(R.string.update_action_install_now))
                }
            }
        }
    }
}
