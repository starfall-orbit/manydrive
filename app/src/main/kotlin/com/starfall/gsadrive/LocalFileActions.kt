package com.starfall.gsadrive

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.starfall.gsadrive.data.DriveFile
import com.starfall.gsadrive.data.LocalFileAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalFileActions(
    file: DriveFile, access: LocalFileAccess,
    cloudDestination: String?,
    upload: (DriveFile, (Result<Unit>) -> Unit) -> Unit,
    onChanged: () -> Unit, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember(file.id) { mutableStateOf("menu") }
    var name by remember(file.id) { mutableStateOf(file.name) }
    var destination by remember(file.id) { mutableStateOf(access.root.path) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<File>>(emptyList()) }
    var foldersLoading by remember { mutableStateOf(false) }
    var totalSize by remember { mutableStateOf<Long?>(file.size) }
    var destinationValid by remember { mutableStateOf(false) }
    LaunchedEffect(mode) {
        if (mode == "info" && file.isFolder) {
            val result = withContext(Dispatchers.IO) { runCatching { access.tree(file.id).filter { it.isFile }.sumOf { it.length() } } }
            totalSize = result.getOrNull()
            error = result.exceptionOrNull()?.message
        }
    }
    LaunchedEffect(destination, mode) {
        if (mode !in listOf("copy", "move")) return@LaunchedEffect
        foldersLoading = true; destinationValid = false; error = null
        val result = withContext(Dispatchers.IO) { runCatching { access.list(destination).filter { it.isDirectory } } }
        folders = result.getOrDefault(emptyList())
        error = result.exceptionOrNull()?.message
        destinationValid = result.isSuccess
        foldersLoading = false
    }
    fun perform(operation: () -> Unit) {
        busy = true; error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(operation) }
            busy = false
            onChanged()
            if (result.isSuccess) onDismiss() else error = result.exceptionOrNull()?.message
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleLarge)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (mode) {
                "menu" -> {
                    if (!file.isFolder) LocalAction(Icons.Outlined.OpenInNew, tr("Mở bằng ứng dụng khác"), !busy) {
                        runCatching { openLocalExternally(context, access.checked(file.id), file.mimeType, false) }
                            .onSuccess { onDismiss() }.onFailure { error = it.message }
                    }
                    LocalAction(Icons.Outlined.Edit, tr("Đổi tên"), !busy) { mode = "rename" }
                    LocalAction(Icons.Outlined.ContentCopy, tr("Sao chép"), !busy) { mode = "copy" }
                    LocalAction(Icons.Outlined.DriveFileMove, tr("Di chuyển"), !busy) { mode = "move" }
                    LocalAction(Icons.Outlined.Delete, tr("Xóa"), !busy) { mode = "delete" }
                    LocalAction(Icons.Outlined.Share, tr("Chia sẻ"), !busy) {
                        busy = true; error = null
                        scope.launch {
                            val result = runCatching {
                                val shared = withContext(Dispatchers.IO) {
                                    if (file.isFolder) zipLocalFolder(context, access, file.id) else access.checked(file.id)
                                }
                                openLocalExternally(context, shared, if (file.isFolder) "application/zip" else file.mimeType, true)
                            }
                            busy = false
                            if (result.isSuccess) onDismiss() else error = result.exceptionOrNull()?.message
                        }
                    }
                    LocalAction(Icons.Outlined.CloudUpload, tr("Tải lên tài khoản cloud"), !busy) { mode = "upload" }
                    LocalAction(Icons.Outlined.Info, tr("Thông tin"), !busy) { mode = "info" }
                }
                "rename" -> {
                    OutlinedTextField(name, { name = it }, label = { Text(tr("Tên mới")) }, enabled = !busy, singleLine = true)
                    FilledTonalButton(enabled = !busy && name.isNotBlank(), onClick = { perform { access.rename(file.id, name) } }) { Text(tr("Đổi tên")) }
                }
                "copy", "move" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(enabled = !busy && destination != access.root.path, onClick = { destination = File(destination).parent ?: access.root.path }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("Quay lại"))
                        }
                        Text(destination, Modifier.weight(1f))
                    }
                    if (foldersLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    folders.forEach { folder ->
                        LocalAction(Icons.Outlined.Folder, folder.name, !busy) { destination = folder.path }
                    }
                    FilledTonalButton(enabled = !busy && !foldersLoading && destinationValid,
                        onClick = { perform { access.transfer(file.id, destination, mode == "move") } }) {
                        Text(tr(if (mode == "move") "Di chuyển vào đây" else "Sao chép vào đây"))
                    }
                }
                "delete" -> {
                    Text(tr(if (file.isFolder) "Xóa thư mục và toàn bộ nội dung bên trong?" else "Xóa tệp này?"))
                    Button(enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = { perform { access.delete(file.id) } }) { Text(tr("Xóa")) }
                }
                "upload" -> {
                    Text(cloudDestination ?: tr("Hãy thêm hoặc chọn một tài khoản trước khi tải lên."))
                    FilledTonalButton(enabled = !busy && cloudDestination != null, onClick = {
                        busy = true; error = null
                        upload(file) { result ->
                            busy = false
                            if (result.isSuccess) onDismiss() else error = result.exceptionOrNull()?.message
                        }
                    }) { Text(tr("Tải lên")) }
                }
                "info" -> {
                    Text(tr("Đường dẫn") + ": " + file.id)
                    Text(tr("Loại") + ": " + if (file.isFolder) tr("Thư mục") else file.mimeType)
                    totalSize?.let { Text(tr("Kích thước") + ": " + android.text.format.Formatter.formatFileSize(context, it)) }
                    Text(tr("Sửa đổi") + ": " + file.modifiedTime.orEmpty())
                }
            }
            if (mode != "menu") TextButton(enabled = !busy, onClick = { mode = "menu"; error = null }) { Text(tr("Quay lại")) }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun LocalAction(icon: ImageVector, label: String, enabled: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun openLocalExternally(context: Context, file: File, mime: String, share: Boolean) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
        if (share) { type = mime; putExtra(Intent.EXTRA_STREAM, uri) } else setDataAndType(uri, mime)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

private fun zipLocalFolder(context: Context, access: LocalFileAccess, path: String): File {
    val source = access.checked(path)
    val files = access.tree(path)
    val directory = File(context.cacheDir, "shared-exports").apply { mkdirs() }
    // Retain recent exports while another app may still be reading their granted URIs.
    directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
    val zip = File.createTempFile("${source.name.take(40)}-".padEnd(3, '_'), ".zip", directory)
    try {
        ZipOutputStream(zip.outputStream().buffered()).use { output ->
            files.forEach { file ->
                val relative = file.relativeTo(source.parentFile).invariantSeparatorsPath
                output.putNextEntry(ZipEntry(relative + if (file.isDirectory) "/" else ""))
                if (file.isFile) file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
    } catch (error: Exception) { zip.delete(); throw error }
    return zip
}
