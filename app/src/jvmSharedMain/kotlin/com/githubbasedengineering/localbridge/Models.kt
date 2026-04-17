package com.githubbasedengineering.localbridge

import kotlinx.serialization.Serializable

@Serializable
data class SelectedLocalFile(
    val locator: String,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val lastModifiedMillis: Long? = null,
)

@Serializable
data class PeerDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val platform: String,
    val lastSeenMillis: Long,
)

@Serializable
enum class TransferDirection {
    INBOUND,
    OUTBOUND,
}

@Serializable
enum class TransferStatus {
    PREPARING,
    WAITING_FOR_PEER,
    IN_PROGRESS,
    PAUSED,
    COMPLETED,
    FAILED,
}

@Serializable
data class TransferRecord(
    val id: String,
    val direction: TransferDirection,
    val peerId: String,
    val peerName: String,
    val peerHost: String? = null,
    val peerPort: Int? = null,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val mimeType: String? = null,
    val fingerprint: String? = null,
    val sourceLocator: String? = null,
    val localFilePath: String? = null,
    val status: TransferStatus,
    val failureMessage: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    val progressFraction: Float
        get() = if (totalBytes <= 0) 0f else (transferredBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)

    val remainingBytes: Long
        get() = (totalBytes - transferredBytes).coerceAtLeast(0L)
}

@Serializable
data class DownloadedItem(
    val id: String,
    val fileName: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val peerName: String,
    val completedAtMillis: Long,
)

@Serializable
data class PersistedState(
    val deviceId: String = "",
    val transfers: List<TransferRecord> = emptyList(),
    val downloads: List<DownloadedItem> = emptyList(),
)

@Serializable
data class TransferOffer(
    val transferId: String,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val lastModifiedMillis: Long? = null,
    val fingerprint: String,
)

@Serializable
data class TransferOfferResponse(
    val accepted: Boolean,
    val resumeBytes: Long,
    val message: String? = null,
)

@Serializable
data class UploadResult(
    val success: Boolean,
    val message: String,
)

data class AppUiState(
    val deviceName: String,
    val localAddress: String? = null,
    val localInterfaceName: String? = null,
    val routingModeLabel: String? = null,
    val serverPort: Int? = null,
    val discoveryActive: Boolean = false,
    val peers: List<PeerDevice> = emptyList(),
    val transfers: List<TransferRecord> = emptyList(),
    val downloads: List<DownloadedItem> = emptyList(),
    val errorMessage: String? = null,
)

fun formatBytes(value: Long): String {
    if (value <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var result = value.toDouble()
    var unitIndex = 0
    while (result >= 1024.0 && unitIndex < units.lastIndex) {
        result /= 1024.0
        unitIndex += 1
    }
    val formatted = if (result >= 100 || unitIndex == 0) {
        result.toInt().toString()
    } else {
        "%.1f".format(result)
    }
    return "$formatted ${units[unitIndex]}"
}

fun TransferStatus.label(): String = when (this) {
    TransferStatus.PREPARING -> "Preparing"
    TransferStatus.WAITING_FOR_PEER -> "Waiting"
    TransferStatus.IN_PROGRESS -> "In Progress"
    TransferStatus.PAUSED -> "Paused"
    TransferStatus.COMPLETED -> "Completed"
    TransferStatus.FAILED -> "Failed"
}
