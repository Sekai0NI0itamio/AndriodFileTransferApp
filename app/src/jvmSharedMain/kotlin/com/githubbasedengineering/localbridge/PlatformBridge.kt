package com.githubbasedengineering.localbridge

import java.io.File
import java.io.InputStream

interface PlatformBridge {
    val platformLabel: String
    val deviceName: String
    val appDataDir: File
    val downloadsDir: File

    fun requestPickFiles(onResult: (List<SelectedLocalFile>) -> Unit)

    fun openInputStream(locator: String): InputStream?

    fun refreshMetadata(locator: String): SelectedLocalFile?

    fun openDownloadedFile(path: String, mimeType: String?)

    fun onNetworkingWillStart() {}

    fun onNetworkingDidStop() {}
}
