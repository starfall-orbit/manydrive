package com.starfall.gsadrive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
    directory: String,
    revision: Int,
    onDirectoryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onExit: () -> Unit,
    openFile: (DriveFile, List<DriveFile>) -> Unit,
    handleBack: Boolean,
    cloudDestination: String? = null,
    uploadLocal: (DriveFile, (Result<Unit>) -> Unit) -> Unit = { _, done -> done(Result.failure(IllegalStateException("No cloud account"))) }
) {
    val context = LocalContext.current
    val access = remember { LocalFileAccess(Environment.getExternalStorageDirectory()) }
    var actionFile by remember { mutableStateOf<DriveFile?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var legacyRequested by rememberSaveable { mutableStateOf(false) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf<String?>(null) }

    fun canRead(): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
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
    LaunchedEffect(directory) { query = "" }

    var model by remember { mutableStateOf(Model(loading = true)) }
    LaunchedEffect(directory, revision, allowed) {
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
                access.list(directory).map { file ->
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
            OutlinedTextField(
                query,
                { query = it },
                singleLine = true,
                placeholder = { Text(tr("Tìm trong thư mục")) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
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
                browserKey = "local:$directory",
                showEmptyMessage = true,
                localMenu = { actionFile = it }
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
