package com.githubbasedengineering.localbridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidLocalBridgeApp()
        }
    }
}

@Composable
private fun AndroidLocalBridgeApp() {
    val context = LocalContext.current.applicationContext
    val platformBridge = remember(context) { AndroidPlatformBridge(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        platformBridge.onUrisPicked(uris)
    }

    DisposableEffect(platformBridge, launcher) {
        platformBridge.bindPickerLauncher { mimeTypes ->
            launcher.launch(mimeTypes)
        }
        onDispose {
            platformBridge.unbindPickerLauncher()
        }
    }

    LocalBridgeApp(platformBridge)
}

private class AndroidPlatformBridge(
    private val appContext: Context,
) : PlatformBridge {
    override val platformLabel: String = "Android"
    override val deviceName: String = buildDeviceName()
    override val appDataDir: File = File(appContext.filesDir, "localbridge")
    override val downloadsDir: File = File(
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir,
        "LocalBridge",
    )

    private var launcher: ((Array<String>) -> Unit)? = null
    private var pendingPickerResult: ((List<SelectedLocalFile>) -> Unit)? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun bindPickerLauncher(launcher: (Array<String>) -> Unit) {
        this.launcher = launcher
    }

    fun unbindPickerLauncher() {
        launcher = null
        pendingPickerResult = null
    }

    fun onUrisPicked(uris: List<Uri>) {
        val files = uris.mapNotNull { uri ->
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            querySelectedFile(uri)
        }
        pendingPickerResult?.invoke(files)
        pendingPickerResult = null
    }

    override fun requestPickFiles(onResult: (List<SelectedLocalFile>) -> Unit) {
        pendingPickerResult = onResult
        launcher?.invoke(arrayOf("*/*")) ?: onResult(emptyList())
    }

    override fun openInputStream(locator: String): InputStream? {
        return runCatching {
            appContext.contentResolver.openInputStream(Uri.parse(locator))
        }.getOrNull()
    }

    override fun refreshMetadata(locator: String): SelectedLocalFile? {
        return querySelectedFile(Uri.parse(locator))
    }

    override fun openDownloadedFile(path: String, mimeType: String?) {
        val file = File(path)
        if (!file.exists()) return

        val contentUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            appContext.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    data = contentUri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    override fun onNetworkingWillStart() {
        val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifiManager.createMulticastLock("localbridge-mdns").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    override fun onNetworkingDidStop() {
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        multicastLock = null
    }

    private fun querySelectedFile(uri: Uri): SelectedLocalFile? {
        val resolver = appContext.contentResolver
        val mimeType = resolver.getType(uri)

        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null

            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else uri.lastPathSegment ?: "Selected File"
            val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
            val modified = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) else null

            return SelectedLocalFile(
                locator = uri.toString(),
                displayName = name ?: "Selected File",
                sizeBytes = size.coerceAtLeast(0L),
                mimeType = mimeType,
                lastModifiedMillis = modified,
            )
        }

        return null
    }

    private fun buildDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        if (manufacturer.isBlank()) return model.ifBlank { "Android Device" }
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }
    }
}
