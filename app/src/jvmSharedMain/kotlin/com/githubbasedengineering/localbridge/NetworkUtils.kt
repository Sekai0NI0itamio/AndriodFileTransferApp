package com.githubbasedengineering.localbridge

import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Collections

object NetworkUtils {
    fun findLocalIpv4Address(): InetAddress? {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }

        for (networkInterface in interfaces) {
            val addresses = Collections.list(networkInterface.inetAddresses)
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
            val siteLocal = addresses.firstOrNull { it.isSiteLocalAddress }
            if (siteLocal != null) return siteLocal
            val first = addresses.firstOrNull()
            if (first != null) return first
        }
        return null
    }

    fun findOpenPort(): Int = ServerSocket(0).use { socket ->
        socket.localPort
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
