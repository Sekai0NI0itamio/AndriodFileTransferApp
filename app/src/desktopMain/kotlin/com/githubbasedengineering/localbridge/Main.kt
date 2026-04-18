package com.githubbasedengineering.localbridge

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import java.awt.Desktop
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import javax.swing.JFileChooser
import javax.swing.UIManager

fun main() = application {
    runCatching {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    }

    val platformBridge = remember { DesktopPlatformBridge() }

    Window(
        title = "LocalBridge",
        state = WindowState(width = 1080.dp, height = 860.dp),
        onCloseRequest = ::exitApplication,
    ) {
        LocalBridgeApp(platformBridge)
    }
}

private class DesktopPlatformBridge : PlatformBridge {
    override val platformLabel: String = "macOS"
    override val preferRouterTunnel: Boolean = System.getProperty("os.name").contains("Mac", ignoreCase = true) &&
        System.getProperty("localbridge.routerTunnel", "true").lowercase() != "false"
    override val deviceName: String = runCatching {
        InetAddress.getLocalHost().hostName
    }.getOrDefault("Mac")
    override val appDataDir: File = File(System.getProperty("user.home"), "Library/Application Support/LocalBridge")
    override val downloadsDir: File = File(System.getProperty("user.home"), "Downloads/LocalBridge")

    override fun requestPickFiles(onResult: (List<SelectedLocalFile>) -> Unit) {
        val chooser = JFileChooser().apply {
            isMultiSelectionEnabled = true
            fileSelectionMode = JFileChooser.FILES_ONLY
            dialogTitle = "Choose Files to Send"
        }

        val result = chooser.showOpenDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) {
            onResult(emptyList())
            return
        }

        val files = chooser.selectedFiles.mapNotNull { file ->
            if (!file.exists() || !file.isFile) {
                null
            } else {
                SelectedLocalFile(
                    locator = file.absolutePath,
                    displayName = file.name,
                    sizeBytes = file.length(),
                    mimeType = null,
                    lastModifiedMillis = file.lastModified(),
                )
            }
        }
        onResult(files)
    }

    override fun openInputStream(locator: String): InputStream? {
        val file = File(locator)
        return if (file.exists()) file.inputStream() else null
    }

    override fun refreshMetadata(locator: String): SelectedLocalFile? {
        val file = File(locator)
        if (!file.exists() || !file.isFile) return null
        return SelectedLocalFile(
            locator = file.absolutePath,
            displayName = file.name,
            sizeBytes = file.length(),
            mimeType = null,
            lastModifiedMillis = file.lastModified(),
        )
    }

    override fun openDownloadedFile(path: String, mimeType: String?) {
        val file = File(path)
        if (!file.exists()) return
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file)
        }
    }
}
