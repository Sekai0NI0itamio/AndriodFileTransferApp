package com.githubbasedengineering.localbridge

import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import javax.net.SocketFactory

data class LocalNetworkBinding(
    val interfaceName: String,
    val address: Inet4Address,
    val vpnBypassActive: Boolean,
)

private data class NetworkCandidate(
    val interfaceName: String,
    val address: Inet4Address,
    val vpnLike: Boolean,
    val priority: Int,
)

object NetworkUtils {
    fun findPreferredIpv4Binding(): LocalNetworkBinding? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val candidates = mutableListOf<NetworkCandidate>()
        var vpnInterfacePresent = false

        for (networkInterface in Collections.list(interfaces)) {
            if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isPointToPoint || networkInterface.isVirtual) {
                continue
            }

            val address = Collections.list(networkInterface.inetAddresses)
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .let { addresses ->
                    addresses.firstOrNull { it.isSiteLocalAddress } ?: addresses.firstOrNull()
                } ?: continue

            val vpnLike = networkInterface.isVpnLike()
            vpnInterfacePresent = vpnInterfacePresent || vpnLike
            candidates += NetworkCandidate(
                interfaceName = networkInterface.name,
                address = address,
                vpnLike = vpnLike,
                priority = interfacePriority(networkInterface.name),
            )
        }

        val selected = candidates
            .filterNot(NetworkCandidate::vpnLike)
            .minByOrNull(NetworkCandidate::priority)
            ?: candidates.minByOrNull(NetworkCandidate::priority)
            ?: return null

        return LocalNetworkBinding(
            interfaceName = selected.interfaceName,
            address = selected.address,
            vpnBypassActive = vpnInterfacePresent && !selected.vpnLike,
        )
    }

    fun findLocalIpv4Address(): InetAddress? = findPreferredIpv4Binding()?.address

    fun findOpenPort(bindAddress: InetAddress? = null): Int = (
        if (bindAddress == null) {
            ServerSocket(0)
        } else {
            ServerSocket(0, 50, bindAddress)
        }
    ).use { socket ->
        socket.localPort
    }

    fun createBoundSocketFactory(binding: LocalNetworkBinding?): SocketFactory? {
        return binding?.let { InterfaceBoundSocketFactory(it.address) }
    }

    fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "downloaded-file" }
    }

    fun uniqueOutputFile(directory: File, proposedName: String): File {
        directory.mkdirs()
        val safeName = sanitizeFileName(proposedName)
        val baseName = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "")
        val firstCandidate = File(directory, safeName)
        if (!firstCandidate.exists()) return firstCandidate

        var index = 2
        while (true) {
            val numberedName = if (extension.isBlank()) {
                "$baseName ($index)"
            } else {
                "$baseName ($index).$extension"
            }
            val candidate = File(directory, numberedName)
            if (!candidate.exists()) return candidate
            index += 1
        }
    }

    fun partialFile(directory: File, fingerprint: String): File {
        val partialsDir = File(directory, "partials")
        partialsDir.mkdirs()
        return File(partialsDir, "$fingerprint.part")
    }
}

private fun interfacePriority(name: String): Int = when {
    name == "en0" -> 0
    name == "en1" -> 1
    name.startsWith("en") -> 10
    name.startsWith("wlan") -> 15
    name.startsWith("wifi") -> 16
    name.startsWith("eth") -> 20
    name.startsWith("bridge") -> 50
    else -> 100
}

private fun NetworkInterface.isVpnLike(): Boolean {
    val normalized = name.lowercase()
    return normalized.startsWith("utun") ||
        normalized.startsWith("tun") ||
        normalized.startsWith("tap") ||
        normalized.startsWith("ppp") ||
        normalized.startsWith("ipsec") ||
        normalized.startsWith("wg") ||
        "vpn" in normalized ||
        "wireguard" in normalized ||
        "tailscale" in normalized ||
        "zerotier" in normalized
}

private class InterfaceBoundSocketFactory(
    private val localAddress: InetAddress,
) : SocketFactory() {
    override fun createSocket(): Socket = newBoundSocket(localAddress, 0)

    override fun createSocket(host: String, port: Int): Socket {
        return createSocket().apply {
            connect(InetSocketAddress(host, port))
        }
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        return newBoundSocket(localHost, localPort).apply {
            connect(InetSocketAddress(host, port))
        }
    }

    override fun createSocket(address: InetAddress, port: Int): Socket {
        return createSocket().apply {
            connect(InetSocketAddress(address, port))
        }
    }

    override fun createSocket(address: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket {
        return newBoundSocket(localHost, localPort).apply {
            connect(InetSocketAddress(address, port))
        }
    }

    private fun newBoundSocket(bindHost: InetAddress, bindPort: Int): Socket {
        return Socket().apply {
            bind(InetSocketAddress(bindHost, bindPort.coerceAtLeast(0)))
        }
    }
}

fun InputStream.skipFully(bytesToSkip: Long) {
    var remaining = bytesToSkip
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
            continue
        }

        val single = read()
        if (single == -1) break
        remaining -= 1
    }
}

fun sha256Hex(inputStreamProvider: () -> InputStream?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val input = requireNotNull(inputStreamProvider()) { "Unable to open the selected file." }
    input.use { stream ->
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
