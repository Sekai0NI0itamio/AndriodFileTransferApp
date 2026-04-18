package com.githubbasedengineering.localbridge

import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val ROUTER_TUNNEL_CONTROL_PORT = 25560
const val ROUTER_TUNNEL_HTTP_PROXY_PORT = 25561

data class RouterTunnelStatus(
    val interfaceName: String,
    val ipAddress: String,
    val source: RouterTunnelSource,
)

data class RouterTunnelCheckResult(
    val status: RouterTunnelStatus? = null,
    val launchedBundledBinary: Boolean = false,
)

enum class RouterTunnelSource {
    EXTERNAL,
    BUNDLED,
}

@Serializable
private data class RouterTunnelHealthResponse(
    @SerialName("interface") val interfaceName: String,
    val ip: String,
)

class RouterTunnelManager(
    private val appDataDir: File,
) {
    private val json = Json {
        ignoreUnknownKeys = true
    }
    private val mutex = Mutex()
    private var launchedProcess: Process? = null

    suspend fun ensureTunnel(
        preferredInterfaceName: String? = null,
        timeoutMillis: Long = 5_000L,
    ): RouterTunnelCheckResult {
        return mutex.withLock {
            probeRunningTunnel()?.let { status ->
                return@withLock RouterTunnelCheckResult(status = status, launchedBundledBinary = false)
            }

            val launchedNow = if (launchedProcess?.isAlive == true) {
                false
            } else {
                launchBundledTunnel(preferredInterfaceName)
            }

            if (timeoutMillis <= 0L) {
                return@withLock RouterTunnelCheckResult(
                    status = null,
                    launchedBundledBinary = launchedNow,
                )
            }

            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            while (System.nanoTime() < deadline) {
                probeRunningTunnel()?.let { status ->
                    return@withLock RouterTunnelCheckResult(
                        status = status,
                        launchedBundledBinary = launchedNow,
                    )
                }
                delay(150)
            }

            RouterTunnelCheckResult(
                status = null,
                launchedBundledBinary = launchedNow,
            )
        }
    }

    fun stop() {
        val process = launchedProcess ?: return
        if (process.isAlive) {
            process.destroy()
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
        launchedProcess = null
    }

    private suspend fun probeRunningTunnel(): RouterTunnelStatus? = withContext(Dispatchers.IO) {
        val health = probeHealthPort() ?: return@withContext null
        if (!probeProxyPort()) return@withContext null
        val source = if (launchedProcess?.isAlive == true) {
            RouterTunnelSource.BUNDLED
        } else {
            RouterTunnelSource.EXTERNAL
        }
        RouterTunnelStatus(
            interfaceName = health.interfaceName,
            ipAddress = health.ip,
            source = source,
        )
    }

    private fun launchBundledTunnel(preferredInterfaceName: String?): Boolean {
        val sourceBinary = locateBundledBinary() ?: return false
        val executable = stageExecutable(sourceBinary)
        val logFile = File(appDataDir, "router-tunnel.log")
        logFile.parentFile?.mkdirs()

        val command = buildList {
            add(executable.absolutePath)
            add("--port")
            add(ROUTER_TUNNEL_CONTROL_PORT.toString())
            add("--http-proxy")
            add(ROUTER_TUNNEL_HTTP_PROXY_PORT.toString())
            add("--skip-test")
            if (!preferredInterfaceName.isNullOrBlank()) {
                add("--interface")
                add(preferredInterfaceName)
            }
        }

        return runCatching {
            launchedProcess = ProcessBuilder(command)
                .directory(executable.parentFile)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
            true
        }.getOrDefault(false)
    }

    private fun stageExecutable(sourceBinary: File): File {
        val destinationDirectory = File(appDataDir, "router-tunnel")
        destinationDirectory.mkdirs()
        val destination = File(destinationDirectory, "router_tunnel")

        if (sourceBinary.absolutePath != destination.absolutePath) {
            sourceBinary.copyTo(destination, overwrite = true)
        }

        destination.setExecutable(true, false)
        destination.setReadable(true, false)
        destination.setWritable(true, true)
        return destination
    }

    private fun locateBundledBinary(): File? {
        val candidates = mutableListOf<File>()

        System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { resourcesDir ->
                val root = File(resourcesDir)
                val architecture = System.getProperty("os.arch").lowercase()
                val architectureFolder = when {
                    architecture.contains("arm") || architecture.contains("aarch") -> "macos-arm64"
                    architecture.contains("x86_64") || architecture.contains("amd64") || architecture.contains("x64") -> "macos-x64"
                    else -> "macos"
                }
                candidates += File(root, "$architectureFolder/router_tunnel")
                candidates += File(root, "macos/router_tunnel")
                candidates += File(root, "common/router_tunnel")
                candidates += File(root, "router_tunnel")
            }

        candidates += File("router_tunnel for intel/router_tunnel")
        candidates += File("router_tunnel")

        return candidates.firstOrNull { candidate -> candidate.exists() && candidate.isFile }
    }

    private suspend fun probeHealthPort(): RouterTunnelHealthResponse? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.soTimeout = 750
                socket.connect(InetSocketAddress("127.0.0.1", ROUTER_TUNNEL_CONTROL_PORT), 250)
                socket.getOutputStream().write(byteArrayOf(0x00))
                socket.getOutputStream().flush()

                val payload = readFramedJson(socket.getInputStream()) ?: return@withContext null
                json.decodeFromString(RouterTunnelHealthResponse.serializer(), payload)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun probeProxyPort(): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", ROUTER_TUNNEL_HTTP_PROXY_PORT), 250)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun readFramedJson(inputStream: InputStream): String? {
        val firstByte = inputStream.read()
        if (firstByte < 0) return null

        val length = if (firstByte == 0xFF) {
            val high = inputStream.read()
            val low = inputStream.read()
            if (high < 0 || low < 0) return null
            (high shl 8) or low
        } else {
            firstByte
        }

        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = inputStream.read(bytes, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return String(bytes, Charsets.UTF_8)
    }
}