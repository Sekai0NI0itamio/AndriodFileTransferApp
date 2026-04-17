package com.githubbasedengineering.localbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun LocalBridgeApp(
    platformBridge: PlatformBridge,
) {
    val controller = remember(platformBridge) { AppController(platformBridge) }
    val uiState by controller.uiState.collectAsState()
    val errorMessage = uiState.errorMessage

    DisposableEffect(controller) {
        controller.start()
        onDispose {
            controller.stop()
        }
    }

    val colors = lightColorScheme(
        background = Color.White,
        surface = Color(0xFFF7F7F7),
        primary = Color.Black,
        onPrimary = Color.White,
        onBackground = Color.Black,
        onSurface = Color.Black,
        outline = Color(0xFF1A1A1A),
    )

    MaterialTheme(colorScheme = colors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                containerColor = Color.White,
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        HeaderSection(uiState)

                        if (errorMessage != null) {
                            MessageCard(
                                title = "Attention Needed",
                                message = errorMessage,
                                actionLabel = "Dismiss",
                                onAction = controller::dismissError,
                            )
                        }

                        DevicesSection(
                            peers = uiState.peers,
                            onSend = controller::chooseFilesForPeer,
                        )

                        TransfersSection(
                            transfers = uiState.transfers,
                            onPause = controller::pauseTransfer,
                            onResume = controller::resumeTransfer,
                            onOpen = controller::openTransfer,
                        )

                        DownloadsSection(
                            downloads = uiState.downloads,
                            onOpen = controller::openDownloaded,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(uiState: AppUiState) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "LocalBridge",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Professional local transfer for the same network.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Divider(color = Color.Black.copy(alpha = 0.08f))
            InfoRow(label = "This device", value = uiState.deviceName)
            InfoRow(label = "Local address", value = uiState.localAddress?.let { address ->
                uiState.serverPort?.let { port -> "$address:$port" } ?: address
            } ?: "Waiting for network")
            InfoRow(label = "Interface", value = uiState.localInterfaceName ?: "Detecting")
            InfoRow(label = "Routing", value = uiState.routingModeLabel ?: "Automatic")
            InfoRow(label = "Discovery", value = if (uiState.discoveryActive) "Active" else "Not started")
        }
    }
}

@Composable
private fun DevicesSection(
    peers: List<PeerDevice>,
    onSend: (PeerDevice) -> Unit,
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Nearby Devices")
            if (peers.isEmpty()) {
                EmptyState("Open LocalBridge on another device connected to the same Wi-Fi network.")
            } else {
                peers.forEach { peer ->
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.12f)),
                        colors = CardDefaults.outlinedCardColors(containerColor = Color.White),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(peer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${peer.platform} | ${peer.host}:${peer.port}", style = MaterialTheme.typography.bodyMedium)
                            Button(
                                onClick = { onSend(peer) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Black,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text("Send Files")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransfersSection(
    transfers: List<TransferRecord>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onOpen: (TransferRecord) -> Unit,
) {
    val activeTransfers = transfers.filter { transfer -> transfer.status != TransferStatus.COMPLETED }

    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Transfers")
            if (activeTransfers.isEmpty()) {
                EmptyState("No active or paused transfers yet.")
            } else {
                activeTransfers.forEach { transfer ->
                    TransferCard(
                        transfer = transfer,
                        onPause = onPause,
                        onResume = onResume,
                        onOpen = onOpen,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadsSection(
    downloads: List<DownloadedItem>,
    onOpen: (DownloadedItem) -> Unit,
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Downloaded Items")
            if (downloads.isEmpty()) {
                EmptyState("Completed downloads will appear here.")
            } else {
                downloads.forEach { item ->
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.12f)),
                        colors = CardDefaults.outlinedCardColors(containerColor = Color.White),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(item.fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("From ${item.peerName}", style = MaterialTheme.typography.bodyMedium)
                                Text(formatBytes(item.sizeBytes), style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = { onOpen(item) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Black,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text("Open")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferCard(
    transfer: TransferRecord,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onOpen: (TransferRecord) -> Unit,
) {
    val directionLabel = if (transfer.direction == TransferDirection.OUTBOUND) {
        "To ${transfer.peerName}"
    } else {
        "From ${transfer.peerName}"
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.12f)),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(transfer.fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(directionLabel, style = MaterialTheme.typography.bodyMedium)
            InfoRow(label = "Status", value = transfer.status.label())
            InfoRow(label = "Size", value = formatBytes(transfer.totalBytes))
            InfoRow(
                label = "Progress",
                value = "${formatBytes(transfer.transferredBytes)} / ${formatBytes(transfer.totalBytes)}",
            )
            InfoRow(label = "Remaining", value = formatBytes(transfer.remainingBytes))

            LinearProgressIndicator(
                progress = { transfer.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = Color.Black,
                trackColor = Color.Black.copy(alpha = 0.12f),
            )

            if (!transfer.failureMessage.isNullOrBlank()) {
                Text(
                    text = transfer.failureMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Black.copy(alpha = 0.7f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (transfer.status == TransferStatus.IN_PROGRESS) {
                    Button(
                        onClick = { onPause(transfer.id) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Black,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Pause")
                    }
                }

                if (transfer.direction == TransferDirection.OUTBOUND &&
                    (transfer.status == TransferStatus.PAUSED || transfer.status == TransferStatus.FAILED)
                ) {
                    Button(
                        onClick = { onResume(transfer.id) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Black,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Resume")
                    }
                }

                if (transfer.status == TransferStatus.COMPLETED && transfer.localFilePath != null) {
                    Button(
                        onClick = { onOpen(transfer) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Black,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Open")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFFF7F7F7),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.12f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7F7F7))
                .padding(18.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.Black.copy(alpha = 0.65f))
        Spacer(modifier = Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MessageCard(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White,
                ),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.Black.copy(alpha = 0.65f),
    )
}
