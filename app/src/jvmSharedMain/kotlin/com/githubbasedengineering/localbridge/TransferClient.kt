package com.githubbasedengineering.localbridge

import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class TransferClient(
    private val clientProvider: () -> OkHttpClient,
    private val json: Json,
) {
    suspend fun offerTransfer(
        peer: PeerDevice,
        offer: TransferOffer,
    ): TransferOfferResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("http://${peer.host}:${peer.port}/api/transfers/offer")
            .post(json.encodeToString(TransferOffer.serializer(), offer).toRequestBody("application/json".toMediaType()))
            .build()

        clientProvider().newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Offer request failed with HTTP ${response.code}.")
            }
            val body = response.body?.string().orEmpty()
            json.decodeFromString(TransferOfferResponse.serializer(), body)
        }
    }

    suspend fun uploadTransfer(
        peer: PeerDevice,
        offer: TransferOffer,
        offset: Long,
        openStream: () -> InputStream?,
        onCallReady: (Call) -> Unit,
        onProgress: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var currentCall: Call? = null
        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()

            override fun contentLength(): Long = (offer.sizeBytes - offset).coerceAtLeast(0L)

            override fun writeTo(sink: okio.BufferedSink) {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                requireNotNull(openStream()) { "Unable to open the selected file." }.use { input ->
                    input.skipFully(offset)
                    var writtenBytes = offset
                    while (true) {
                        if (currentCall?.isCanceled() == true) {
                            throw IllegalStateException("Upload cancelled.")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        writtenBytes += read
                        onProgress(writtenBytes)
                    }
                }
            }
        }

        val request = Request.Builder()
            .url("http://${peer.host}:${peer.port}/api/transfers/${offer.fingerprint}/content?offset=$offset")
            .header("X-Transfer-Id", offer.transferId)
            .put(requestBody)
            .build()

        val call = clientProvider().newCall(request)
        currentCall = call
        onCallReady(call)

        call.execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                throw IllegalStateException(
                    "Upload failed with HTTP ${response.code}: ${errorBody.ifBlank { "no response body" }}",
                )
            }
        }
    }
}
