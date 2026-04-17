package com.githubbasedengineering.localbridge

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class TransferServer(
    private val host: String,
    private val deviceId: String,
    private val deviceName: String,
    private val platformLabel: String,
    private val port: Int,
    private val onOffer: suspend (TransferOffer) -> TransferOfferResponse,
    private val onUpload: suspend (String, Long, ByteReadChannel) -> UploadResult,
) {
    private val engine: ApplicationEngine = embeddedServer(CIO, host = host, port = port) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                },
            )
        }

        routing {
            get("/api/device") {
                call.respond(
                    DeviceResponse(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        platform = platformLabel,
                    ),
                )
            }

            post("/api/transfers/offer") {
                val offer = call.receive<TransferOffer>()
                call.respond(onOffer(offer))
            }

            put("/api/transfers/{fingerprint}/content") {
                val fingerprint = call.parameters["fingerprint"]
                if (fingerprint.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, UploadResult(false, "Missing fingerprint."))
                    return@put
                }

                val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                val result = onUpload(fingerprint, offset, call.receiveChannel())
                val statusCode = if (result.success) HttpStatusCode.OK else HttpStatusCode.Conflict
                call.respond(statusCode, result)
            }
        }
    }

    fun start() {
        engine.start(wait = false)
    }

    fun stop() {
        engine.stop(1_000, 2_000)
    }

    @Serializable
    private data class DeviceResponse(
        val deviceId: String,
        val deviceName: String,
        val platform: String,
    )
}
