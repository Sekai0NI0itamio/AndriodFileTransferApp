package com.githubbasedengineering.localbridge

import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient

class AppController(
    private val platformBridge: PlatformBridge,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateStorage = StateStorage(platformBridge.appDataDir)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var httpClient: OkHttpClient? = null
    private val transferClient = TransferClient(
        clientProvider = { requireNotNull(httpClient) { "Transfer networking is not ready yet." } },
        json = json,
    )
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val pauseRequests = ConcurrentHashMap.newKeySet<String>()
    private val progressPersistTimestamps = ConcurrentHashMap<String, Long>()
    private val inboundOffers = ConcurrentHashMap<String, TransferOffer>()
    private val localTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}

        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}

        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }

    private var deviceId: String = ""
    private var server: TransferServer? = null
    private var discovery: MdnsPeerService? = null

    private val _uiState = MutableStateFlow(AppUiState(deviceName = platformBridge.deviceName))
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    fun start() {
        if (server != null || discovery != null) return

        scope.launch {
            val persistedState = stateStorage.load()
            deviceId = persistedState.deviceId.ifBlank { UUID.randomUUID().toString() }

            val restoredTransfers = persistedState.transfers.map { transfer ->
                if (transfer.status == TransferStatus.IN_PROGRESS || transfer.status == TransferStatus.WAITING_FOR_PEER) {
                    transfer.copy(status = TransferStatus.PAUSED, updatedAtMillis = System.currentTimeMillis())
                } else {
                    transfer
                }
            }

            mutateState(persist = false) {
                it.copy(
                    deviceName = platformBridge.deviceName,
                    transfers = restoredTransfers.sortedByDescending { record -> record.updatedAtMillis },
                    downloads = persistedState.downloads.sortedByDescending { item -> item.completedAtMillis },
                )
            }
            persistSnapshot()

            startNetworking()
        }
    }

    fun stop() {
        activeCalls.values.forEach { it.cancel() }
        activeCalls.clear()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        discovery?.close()
        discovery = null
        server?.stop()
        server = null
        platformBridge.onNetworkingDidStop()
        disposeHttpClient(httpClient)
        httpClient = null
        scope.cancel()
    }

    fun dismissError() {
        mutateState { it.copy(errorMessage = null) }
    }

    fun chooseFilesForPeer(peer: PeerDevice) {
        platformBridge.requestPickFiles { files ->
            if (files.isEmpty()) return@requestPickFiles
            queueFilesForPeer(peer, files)
        }
    }

    fun pauseTransfer(transferId: String) {
        pauseRequests += transferId
        activeCalls.remove(transferId)?.cancel()
        activeJobs.remove(transferId)?.cancel()
        mutateTransfer(transferId) { transfer ->
            transfer.copy(
                status = TransferStatus.PAUSED,
                failureMessage = null,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    fun resumeTransfer(transferId: String) {
        val transfer = findTransfer(transferId) ?: return
        if (transfer.direction != TransferDirection.OUTBOUND) return
        launchTransfer(transferId)
    }

    fun openDownloaded(item: DownloadedItem) {
        platformBridge.openDownloadedFile(item.absolutePath, item.mimeType)
    }

    fun openTransfer(record: TransferRecord) {
        val path = record.localFilePath ?: return
        platformBridge.openDownloadedFile(path, record.mimeType)
    }

    private suspend fun startNetworking() {
        try {
            platformBridge.appDataDir.mkdirs()
            platformBridge.downloadsDir.mkdirs()
            platformBridge.onNetworkingWillStart()

            val binding = requireNotNull(NetworkUtils.findPreferredIpv4Binding()) {
                "No local IPv4 address was found on the active network."
            }
            val hostAddress = requireNotNull(binding.address.hostAddress) {
                "Unable to resolve the selected IPv4 address."
            }
            rebuildHttpClient(binding)

            val port = NetworkUtils.findOpenPort(binding.address)
            server = TransferServer(
                host = hostAddress,
                deviceId = deviceId,
                deviceName = platformBridge.deviceName,
                platformLabel = platformBridge.platformLabel,
                port = port,
                onOffer = { offer -> handleIncomingOffer(offer) },
                onUpload = { fingerprint, offset, channel -> handleIncomingUpload(fingerprint, offset, channel) },
            ).also { it.start() }

            discovery = MdnsPeerService(
                bindAddress = binding.address,
                selfDeviceId = deviceId,
                selfDeviceName = platformBridge.deviceName,
                platformLabel = platformBridge.platformLabel,
                port = port,
                onPeerResolved = { peer -> upsertPeer(peer) },
                onPeerRemoved = { peerId -> removePeer(peerId) },
            ).also { it.start() }

            mutateState {
                it.copy(
                    localAddress = hostAddress,
                    localInterfaceName = binding.interfaceName,
                    routingModeLabel = if (binding.vpnBypassActive) {
                        "Physical interface (${binding.interfaceName})"
                    } else {
                        "Direct local network"
                    },
                    serverPort = port,
                    discoveryActive = true,
                    errorMessage = null,
                )
            }
        } catch (error: Throwable) {
            discovery?.close()
            discovery = null
            server?.stop()
            server = null
            disposeHttpClient(httpClient)
            httpClient = null
            platformBridge.onNetworkingDidStop()
            mutateState {
                it.copy(
                    localAddress = null,
                    localInterfaceName = null,
                    routingModeLabel = null,
                    discoveryActive = false,
                    errorMessage = "Local networking could not start: ${error.message ?: "unknown error"}",
                )
            }
        }
    }

    private fun rebuildHttpClient(binding: LocalNetworkBinding?) {
        val previous = httpClient
        httpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .sslSocketFactory(createLocalSslSocketFactory(), localTrustManager)
            .apply {
                NetworkUtils.createBoundSocketFactory(binding)?.let(::socketFactory)
            }
            .build()
        disposeHttpClient(previous)
    }

    private fun createLocalSslSocketFactory(): SSLSocketFactory {
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(localTrustManager), SecureRandom())
        }.socketFactory
    }

    private fun disposeHttpClient(client: OkHttpClient?) {
        client ?: return
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun queueFilesForPeer(peer: PeerDevice, files: List<SelectedLocalFile>) {
        files.forEach { selectedFile ->
            val now = System.currentTimeMillis()
            val record = TransferRecord(
                id = UUID.randomUUID().toString(),
                direction = TransferDirection.OUTBOUND,
                peerId = peer.id,
                peerName = peer.name,
                peerHost = peer.host,
                peerPort = peer.port,
                fileName = selectedFile.displayName,
                totalBytes = selectedFile.sizeBytes,
                transferredBytes = 0L,
                mimeType = selectedFile.mimeType,
                fingerprint = null,
                sourceLocator = selectedFile.locator,
                localFilePath = null,
                status = TransferStatus.PREPARING,
                failureMessage = null,
                createdAtMillis = now,
                updatedAtMillis = now,
            )
            upsertTransfer(record)
            launchTransfer(record.id)
        }
    }

    private fun launchTransfer(transferId: String) {
        if (activeJobs.containsKey(transferId)) return
        val job = scope.launch {
            try {
                runTransfer(transferId)
            } finally {
                activeCalls.remove(transferId)
                activeJobs.remove(transferId)
            }
        }
        activeJobs[transferId] = job
    }

    private suspend fun runTransfer(transferId: String) {
        val existing = findTransfer(transferId) ?: return
        val sourceLocator = existing.sourceLocator
        if (sourceLocator.isNullOrBlank()) {
            markTransferFailed(transferId, "The original file can no longer be found.")
            return
        }

        try {
            val latestMetadata = platformBridge.refreshMetadata(sourceLocator)
            if (latestMetadata == null) {
                markTransferFailed(transferId, "The original file is no longer accessible.")
                return
            }

            mutateTransfer(transferId) { transfer ->
                transfer.copy(
                    fileName = latestMetadata.displayName,
                    totalBytes = latestMetadata.sizeBytes,
                    mimeType = latestMetadata.mimeType,
                    status = TransferStatus.PREPARING,
                    failureMessage = null,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }

            val fingerprint = existing.fingerprint ?: withContext(Dispatchers.IO) {
                sha256Hex { platformBridge.openInputStream(sourceLocator) }
            }

            mutateTransfer(transferId) { transfer ->
                transfer.copy(
                    fingerprint = fingerprint,
                    status = TransferStatus.WAITING_FOR_PEER,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }

            val peer = resolvePeer(findTransfer(transferId) ?: existing)
            if (peer == null) {
                markTransferFailed(transferId, "The target device is not currently discoverable on the network.")
                return
            }

            val current = findTransfer(transferId) ?: return
            val offer = TransferOffer(
                transferId = transferId,
                sourceDeviceId = deviceId,
                sourceDeviceName = platformBridge.deviceName,
                fileName = current.fileName,
                sizeBytes = current.totalBytes,
                mimeType = current.mimeType,
                lastModifiedMillis = latestMetadata.lastModifiedMillis,
                fingerprint = fingerprint,
            )

            val response = transferClient.offerTransfer(peer, offer)
            if (!response.accepted) {
                markTransferFailed(transferId, response.message ?: "The receiving device rejected the transfer.")
                return
            }

            val resumeBytes = response.resumeBytes.coerceIn(0L, current.totalBytes)
            mutateTransfer(transferId, persist = true) { transfer ->
                transfer.copy(
                    peerHost = peer.host,
                    peerPort = peer.port,
                    transferredBytes = resumeBytes,
                    status = if (resumeBytes >= transfer.totalBytes) TransferStatus.COMPLETED else TransferStatus.IN_PROGRESS,
                    localFilePath = transfer.sourceLocator,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }

            if (resumeBytes >= current.totalBytes) return

            transferClient.uploadTransfer(
                peer = peer,
                offer = offer,
                offset = resumeBytes,
                openStream = { platformBridge.openInputStream(sourceLocator) },
                onCallReady = { call -> activeCalls[transferId] = call },
                onProgress = { uploadedBytes ->
                    updateTransferProgress(transferId, uploadedBytes)
                },
            )

            mutateTransfer(transferId) { transfer ->
                transfer.copy(
                    transferredBytes = transfer.totalBytes,
                    status = TransferStatus.COMPLETED,
                    failureMessage = null,
                    localFilePath = transfer.sourceLocator,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
        } catch (cancelled: CancellationException) {
            if (pauseRequests.remove(transferId)) {
                mutateTransfer(transferId) { transfer ->
                    transfer.copy(
                        status = TransferStatus.PAUSED,
                        failureMessage = null,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                }
            }
        } catch (error: Throwable) {
            if (pauseRequests.remove(transferId)) {
                mutateTransfer(transferId) { transfer ->
                    transfer.copy(
                        status = TransferStatus.PAUSED,
                        failureMessage = null,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                }
            } else {
                markTransferFailed(transferId, error.message ?: "Transfer failed.")
            }
        }
    }

    private suspend fun handleIncomingOffer(offer: TransferOffer): TransferOfferResponse {
        inboundOffers[offer.fingerprint] = offer
        val partialFile = NetworkUtils.partialFile(platformBridge.appDataDir, offer.fingerprint)
        if (partialFile.exists() && partialFile.length() > offer.sizeBytes) {
            partialFile.delete()
        }
        val resumeBytes = partialFile.takeIf(File::exists)?.length() ?: 0L
        val now = System.currentTimeMillis()
        val existing = findTransferByFingerprint(offer.fingerprint, TransferDirection.INBOUND)
        val record = (existing ?: TransferRecord(
            id = offer.transferId,
            direction = TransferDirection.INBOUND,
            peerId = offer.sourceDeviceId,
            peerName = offer.sourceDeviceName,
            peerHost = null,
            peerPort = null,
            fileName = offer.fileName,
            totalBytes = offer.sizeBytes,
            transferredBytes = resumeBytes,
            mimeType = offer.mimeType,
            fingerprint = offer.fingerprint,
            sourceLocator = null,
            localFilePath = partialFile.absolutePath,
            status = if (resumeBytes > 0) TransferStatus.PAUSED else TransferStatus.WAITING_FOR_PEER,
            failureMessage = null,
            createdAtMillis = now,
            updatedAtMillis = now,
        )).copy(
            peerId = offer.sourceDeviceId,
            peerName = offer.sourceDeviceName,
            fileName = offer.fileName,
            totalBytes = offer.sizeBytes,
            transferredBytes = resumeBytes,
            mimeType = offer.mimeType,
            fingerprint = offer.fingerprint,
            localFilePath = partialFile.absolutePath,
            status = if (resumeBytes > 0) TransferStatus.PAUSED else TransferStatus.WAITING_FOR_PEER,
            updatedAtMillis = now,
        )
        upsertTransfer(record)

        return TransferOfferResponse(
            accepted = true,
            resumeBytes = resumeBytes,
            message = "Ready to receive.",
        )
    }

    private suspend fun handleIncomingUpload(
        fingerprint: String,
        offset: Long,
        channel: ByteReadChannel,
    ): UploadResult {
        val offer = inboundOffers[fingerprint] ?: return UploadResult(false, "No transfer offer exists for this file.")
        val partialFile = NetworkUtils.partialFile(platformBridge.appDataDir, fingerprint)
        partialFile.parentFile?.mkdirs()

        if (partialFile.exists() && partialFile.length() > offset) {
            RandomAccessFile(partialFile, "rw").use { file ->
                file.setLength(offset)
            }
        }

        if (partialFile.exists() && partialFile.length() < offset) {
            return UploadResult(false, "Resume point does not match the receiver state.")
        }

        val transfer = ensureInboundTransferRecord(offer, partialFile, offset)
        var receivedBytes = offset

        return try {
            mutateTransfer(transfer.id, persist = true) { record ->
                record.copy(
                    status = TransferStatus.IN_PROGRESS,
                    transferredBytes = offset,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }

            withContext(Dispatchers.IO) {
                FileOutputStream(partialFile, true).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read == -1) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        receivedBytes += read
                        updateTransferProgress(transfer.id, receivedBytes)
                    }
                }
            }

            if (receivedBytes >= offer.sizeBytes) {
                val finalFile = NetworkUtils.uniqueOutputFile(platformBridge.downloadsDir, offer.fileName)
                if (!partialFile.renameTo(finalFile)) {
                    partialFile.copyTo(finalFile, overwrite = true)
                    partialFile.delete()
                }

                val completedAt = System.currentTimeMillis()
                mutateTransfer(transfer.id) { record ->
                    record.copy(
                        transferredBytes = offer.sizeBytes,
                        localFilePath = finalFile.absolutePath,
                        status = TransferStatus.COMPLETED,
                        failureMessage = null,
                        updatedAtMillis = completedAt,
                    )
                }
                addDownload(
                    DownloadedItem(
                        id = UUID.randomUUID().toString(),
                        fileName = finalFile.name,
                        absolutePath = finalFile.absolutePath,
                        sizeBytes = finalFile.length(),
                        mimeType = offer.mimeType,
                        peerName = offer.sourceDeviceName,
                        completedAtMillis = completedAt,
                    ),
                )
                UploadResult(true, "Transfer complete.")
            } else {
                mutateTransfer(transfer.id) { record ->
                    record.copy(
                        transferredBytes = receivedBytes,
                        status = TransferStatus.PAUSED,
                        failureMessage = null,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                }
                UploadResult(true, "Transfer paused.")
            }
        } catch (error: Throwable) {
            mutateTransfer(transfer.id) { record ->
                record.copy(
                    transferredBytes = receivedBytes,
                    status = TransferStatus.PAUSED,
                    failureMessage = null,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            UploadResult(false, error.message ?: "Transfer interrupted.")
        }
    }

    private fun ensureInboundTransferRecord(
        offer: TransferOffer,
        partialFile: File,
        offset: Long,
    ): TransferRecord {
        val existing = findTransferByFingerprint(offer.fingerprint, TransferDirection.INBOUND)
        if (existing != null) return existing

        val now = System.currentTimeMillis()
        val record = TransferRecord(
            id = offer.transferId,
            direction = TransferDirection.INBOUND,
            peerId = offer.sourceDeviceId,
            peerName = offer.sourceDeviceName,
            peerHost = null,
            peerPort = null,
            fileName = offer.fileName,
            totalBytes = offer.sizeBytes,
            transferredBytes = offset,
            mimeType = offer.mimeType,
            fingerprint = offer.fingerprint,
            sourceLocator = null,
            localFilePath = partialFile.absolutePath,
            status = TransferStatus.IN_PROGRESS,
            failureMessage = null,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        upsertTransfer(record)
        return record
    }

    private fun markTransferFailed(transferId: String, message: String) {
        mutateTransfer(transferId) { transfer ->
            transfer.copy(
                status = TransferStatus.FAILED,
                failureMessage = message,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        mutateState { it.copy(errorMessage = message) }
    }

    private fun updateTransferProgress(transferId: String, transferredBytes: Long) {
        mutateTransfer(transferId, persist = shouldPersistProgress(transferId)) { transfer ->
            transfer.copy(
                transferredBytes = transferredBytes.coerceIn(0L, transfer.totalBytes),
                status = TransferStatus.IN_PROGRESS,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun shouldPersistProgress(transferId: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = progressPersistTimestamps[transferId] ?: 0L
        if (now - previous >= 1_000L) {
            progressPersistTimestamps[transferId] = now
            return true
        }
        return false
    }

    private fun resolvePeer(transfer: TransferRecord): PeerDevice? {
        return _uiState.value.peers.firstOrNull { peer -> peer.id == transfer.peerId }
            ?: transfer.peerHost?.let { host ->
                val port = transfer.peerPort ?: return@let null
                PeerDevice(
                    id = transfer.peerId,
                    name = transfer.peerName,
                    host = host,
                    port = port,
                    platform = "Unknown",
                    lastSeenMillis = System.currentTimeMillis(),
                )
            }
    }

    private fun findTransfer(transferId: String): TransferRecord? {
        return _uiState.value.transfers.firstOrNull { transfer -> transfer.id == transferId }
    }

    private fun findTransferByFingerprint(
        fingerprint: String,
        direction: TransferDirection,
    ): TransferRecord? {
        return _uiState.value.transfers.firstOrNull { transfer ->
            transfer.direction == direction && transfer.fingerprint == fingerprint
        }
    }

    private fun upsertPeer(peer: PeerDevice) {
        mutateState(persist = false) { state ->
            val remaining = state.peers.filterNot { existing -> existing.id == peer.id }
            state.copy(
                peers = (remaining + peer.copy(lastSeenMillis = System.currentTimeMillis()))
                    .sortedBy { item -> item.name.lowercase() },
            )
        }
    }

    private fun removePeer(peerId: String) {
        mutateState(persist = false) { state ->
            state.copy(peers = state.peers.filterNot { peer -> peer.id == peerId })
        }
    }

    private fun addDownload(item: DownloadedItem) {
        mutateState { state ->
            state.copy(downloads = listOf(item) + state.downloads)
        }
    }

    private fun upsertTransfer(record: TransferRecord) {
        mutateState { state ->
            val remaining = state.transfers.filterNot { transfer -> transfer.id == record.id }
            state.copy(
                transfers = (remaining + record).sortedByDescending { transfer -> transfer.updatedAtMillis },
            )
        }
    }

    private fun mutateTransfer(
        transferId: String,
        persist: Boolean = true,
        transform: (TransferRecord) -> TransferRecord,
    ) {
        mutateState(persist = persist) { state ->
            state.copy(
                transfers = state.transfers.map { transfer ->
                    if (transfer.id == transferId) transform(transfer) else transfer
                }.sortedByDescending { transfer -> transfer.updatedAtMillis },
            )
        }
    }

    private fun mutateState(
        persist: Boolean = true,
        transform: (AppUiState) -> AppUiState,
    ) {
        _uiState.update(transform)
        if (persist) persistSnapshot()
    }

    private fun persistSnapshot() {
        val snapshot = _uiState.value
        scope.launch(Dispatchers.IO) {
            stateStorage.save(
                PersistedState(
                    deviceId = deviceId,
                    transfers = snapshot.transfers,
                    downloads = snapshot.downloads,
                ),
            )
        }
    }
}
