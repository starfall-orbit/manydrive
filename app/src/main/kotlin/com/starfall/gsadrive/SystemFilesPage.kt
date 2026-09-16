package com.starfall.gsadrive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.starfall.gsadrive.data.DriveFile
import com.starfall.gsadrive.data.LocalFileAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Suppress("DEPRECATION")
@Composable
internal fun SystemFilesPage(
    padding: PaddingValues,
    rootPath: String,
    directory: String,
    query: String,
    revision: Int,
    showHiddenFiles: Boolean,
    onDirectoryChange: (String) -> Unit,
    onRootChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onExit: () -> Unit,
    openFile: (DriveFile, List<DriveFile>) -> Unit,
    handleBack: Boolean,
    cloudDestination: String? = null,
    uploadLocal: (DriveFile, (Result<Unit>) -> Unit) -> Unit = { _, done -> done(Result.failure(IllegalStateException("No cloud account"))) }
) {
    val context = LocalContext.current
    val access = remember(rootPath) { LocalFileAccess(File(rootPath)) }
    val storageLocations = remember(context, revision) { systemStorageLocations(context) }
    var storageMenu by remember { mutableStateOf(false) }
    var actionFile by remember { mutableStateOf<DriveFile?>(null) }
    var legacyRequested by rememberSaveable { mutableStateOf(false) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf<String?>(null) }

    fun canRead(): Boolean {
        val appRoot = runCatching { (context.getExternalFilesDir(null) ?: context.filesDir).canonicalPath }
            .getOrElse { (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath }
        if (rootPath == appRoot) return true
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    var allowed by remember { mutableStateOf(canRead()) }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allowed = canRead()
        onRefresh()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = canRead()
        onRefresh()
    }

    fun requestAccess() {
        permissionError = null
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                settingsLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            } catch (_: android.content.ActivityNotFoundException) {
                try {
                    settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: android.content.ActivityNotFoundException) {
                    permissionError = tr("Không thể mở cài đặt quyền truy cập bộ nhớ.")
                }
            }
        } else {
            val activity = context as? android.app.Activity
            if (
                legacyRequested && activity != null &&
                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            ) {
                settingsLauncher.launch(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            } else {
                legacyRequested = true
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = canRead()
                onRefresh()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision) { allowed = canRead() }

    LaunchedEffect(Unit) {
        if (!allowed && !requested) {
            requested = true
            requestAccess()
        }
    }

    var model by remember { mutableStateOf(Model(loading = true)) }
    LaunchedEffect(directory, revision, allowed, showHiddenFiles, rootPath) {
        if (!allowed) {
            model = Model()
            return@LaunchedEffect
        }
        model = Model(loading = true)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                access.list(directory)
                    .filter { showHiddenFiles || !it.name.startsWith(".") }
                    .map { file ->
                    DriveFile(
                        id = file.path,
                        name = file.name,
                        mimeType = if (file.isDirectory) {
                            "application/vnd.google-apps.folder"
                        } else {
                            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                                ?: if (file.extension.lowercase() in setOf("md", "log", "kt", "json", "yaml", "toml")) {
                                    "text/plain"
                                } else {
                                    "application/octet-stream"
                                }
                        },
                        modifiedTime = dateFormat.format(Date(file.lastModified())),
                        size = if (file.isFile) file.length() else null
                    )
                }
            }
        }
        model = result.fold(
            onSuccess = { Model(files = it) },
            onFailure = { Model(message = tr("Không thể đọc thư mục này. Kiểm tra quyền truy cập.")) }
        )
    }

    fun goBack() {
        if (directory == access.root.path) {
            onExit()
        } else {
            onDirectoryChange(File(directory).parent ?: access.root.path)
        }
    }

    BackHandler(enabled = handleBack && actionFile == null, onBack = ::goBack)

    Column(Modifier.fillMaxSize().padding(padding)) {
        if (!allowed) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
            ) {
                Icon(Icons.Outlined.Storage, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text(permissionError ?: tr("Cho phép truy cập bộ nhớ để duyệt tệp trên thiết bị."))
                FilledTonalButton(onClick = ::requestAccess) { Text(tr("Cấp quyền truy cập")) }
            }
        } else {
            if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            FileBrowserPage(
                model,
                PaddingValues(0.dp),
                account = null,
                shared = false,
                query = query,
                authorize = {},
                openFolder = { onDirectoryChange(it.id) },
                openFile = openFile,
                browserKey = "local:$rootPath:$directory",
                showEmptyMessage = true,
                localMenu = { actionFile = it },
                toolbarAction = {
                    Box {
                        IconButton(onClick = { storageMenu = true }) {
                            Icon(Icons.Outlined.Storage, tr("Chọn ổ đĩa"))
                        }
                        DropdownMenu(
                            expanded = storageMenu,
                            onDismissRequest = { storageMenu = false }
                        ) {
                            storageLocations.forEach { location ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(location.label)
                                            Text(
                                                location.displayPath,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        if (location.path == rootPath) Icon(Icons.Outlined.Check, null)
                                        else Spacer(Modifier.size(24.dp))
                                    },
                                    onClick = {
                                        storageMenu = false
                                        if (location.path != rootPath) onRootChange(location.path)
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    }

    actionFile?.let { selected ->
        LocalFileActions(
            selected,
            access,
            cloudDestination,
            uploadLocal,
            onChanged = onRefresh,
            onDismiss = { actionFile = null }
        )
    }
}


private data class SystemStorageLocation(
    val label: String,
    val path: String,
    val displayPath: String = path
)

@Suppress("DEPRECATION")
private fun systemStorageLocations(context: android.content.Context): List<SystemStorageLocation> {
    fun canonical(file: File): String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    val primary = canonical(Environment.getExternalStorageDirectory())
    val result = linkedMapOf<String, SystemStorageLocation>()
    result[primary] = SystemStorageLocation("sdcard/", primary)

    val appFiles = canonical(context.getExternalFilesDir(null) ?: context.filesDir)
    result[appFiles] = SystemStorageLocation(tr("Thư mục ứng dụng"), appFiles)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val manager = context.getSystemService(StorageManager::class.java)
        manager?.storageVolumes?.forEach { volume ->
            if (volume.state != Environment.MEDIA_MOUNTED && volume.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@forEach
            val directory = volume.directory ?: return@forEach
            val path = canonical(directory)
            if (path == primary || path == appFiles) return@forEach
            val description = runCatching { volume.getDescription(context) }.getOrNull().orEmpty()
            val label = description.ifBlank {
                if (volume.isRemovable) tr("Bộ nhớ ngoài") else tr("Ổ lưu trữ")
            }
            result[path] = SystemStorageLocation(label, path)
        }
    } else {
        ContextCompat.getExternalFilesDirs(context, null).drop(1).forEachIndexed { index, appDirectory ->
            if (appDirectory == null) return@forEachIndexed
            var root: File? = appDirectory
            repeat(4) { root = root?.parentFile }
            val volumeRoot = root ?: return@forEachIndexed
            val path = canonical(volumeRoot)
            if (path != primary && path != appFiles) {
                result[path] = SystemStorageLocation(tr("Bộ nhớ ngoài") + " ${index + 1}", path)
            }
        }
    }

    return result.values.toList()
}
