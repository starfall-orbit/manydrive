package com.starfall.gsadrive

import android.Manifest
import android.app.Activity
import android.content.Context
import android.accounts.Account
import android.accounts.AccountManager
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.io.File
import com.starfall.gsadrive.data.FileListCache
import com.starfall.gsadrive.data.DocumentUploads
import com.starfall.gsadrive.ui.FileViewerPage
import com.starfall.gsadrive.ui.MediaMiniPlayer
import com.starfall.gsadrive.ui.SettingsPage
import com.starfall.gsadrive.ui.theme.ThemeMode
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.starfall.gsadrive.data.driveLoadError
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.common.util.concurrent.ListenableFuture
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.api.Scope
import com.starfall.gsadrive.data.AccountStore
import com.starfall.gsadrive.data.DriveApi
import com.starfall.gsadrive.data.DriveFile
import com.starfall.gsadrive.data.DrivePermission
import com.starfall.gsadrive.data.PhotosApi
import com.starfall.gsadrive.data.S3Api
import com.starfall.gsadrive.data.S3Account
import com.starfall.gsadrive.data.S3Accounts
import com.starfall.gsadrive.data.S3AccountStore
import com.starfall.gsadrive.data.ServiceAccountApi
import com.starfall.gsadrive.data.ServiceAccountCredentials
import com.starfall.gsadrive.data.ServiceAccountStore
import com.starfall.gsadrive.data.ServiceAccessToken
import com.starfall.gsadrive.data.S3Config
import com.starfall.gsadrive.ui.theme.ManyDriveTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
private const val PHOTOS_SCOPE = "https://www.googleapis.com/auth/photoslibrary.appendonly"


private class AndroidDocumentsMultiPicker : ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val mimeTypes = input.filter { it.isNotBlank() }.ifEmpty { listOf("*/*") }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1) mimeTypes.first() else "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val androidPicker = ComponentName("com.android.documentsui", "com.android.documentsui.picker.PickActivity")
        val available = runCatching {
            context.packageManager.getActivityInfo(androidPicker, 0)
        }.isSuccess
        if (available) intent.component = androidPicker
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val result = LinkedHashSet<Uri>()
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) result += clip.getItemAt(index).uri
        }
        intent.data?.let(result::add)
        return result.toList()
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        internal const val ACTION_OPEN_PLAYER = "com.starfall.gsadrive.action.OPEN_PLAYER"
    }

    private var pendingLegacyDownload: (() -> Unit)? = null
    private val downloadPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val start = pendingLegacyDownload
        pendingLegacyDownload = null
        if (granted) start?.invoke()
        else Toast.makeText(this, tr("Cần quyền lưu trữ để tải vào Downloads."), Toast.LENGTH_LONG).show()
    }
    private var pendingOpenPlayer = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePlayerIntent()
    }

    private fun handlePlayerIntent() {
        if (intent?.action == ACTION_OPEN_PLAYER) {
            pendingOpenPlayer = true
            // Consume the request so a later recreation does not expand a minimized player again.
            intent.action = null
        }
        openPendingPlayer()
    }

    private fun openPendingPlayer() {
        if (!pendingOpenPlayer) return
        val controller = playback ?: return
        val item = controller.currentMediaItem ?: return
        viewerCoordinator.syncToMediaItem(item)
        viewerCoordinator.expand()
        pendingOpenPlayer = false
    }

    private data class BrowserRefreshTarget(
        val accountKey: String,
        val tab: Int,
        val path: List<DriveFile>,
        val requestId: Int
    )

    private lateinit var accountPicker: ActivityResultLauncher<Intent>
    private lateinit var servicePicker: ActivityResultLauncher<Array<String>>
    private lateinit var resolution: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var photosAuthorizationResolution: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var folderPicker: ActivityResultLauncher<Uri?>
    private lateinit var filePicker: ActivityResultLauncher<Array<String>>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private val authorization by lazy { Identity.getAuthorizationClient(this) }
    private val googleStore by lazy { AccountStore(this) }
    private val s3Store by lazy { S3AccountStore(this) }
    private val serviceStore by lazy { ServiceAccountStore(this) }
    private val selection by lazy { getSharedPreferences("manydrive_selection", MODE_PRIVATE) }
    private val listingCache by lazy { FileListCache(File(cacheDir, "file-lists")) }
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var superDark by mutableStateOf(false)
    private var pendingUploadParent: String? = null
    private var s3Accounts = S3Accounts()
    private var serviceAccounts = emptyList<ServiceAccountCredentials>()
    private val serviceTokens = mutableMapOf<String, ServiceAccessToken>()
    private var s3Available = true
    private var serviceAvailable = true
    private var accountUi by mutableStateOf(AccountUi())
    private var model by mutableStateOf(Model())
    /** Each root browser tab owns its own listing/path state instead of sharing one Model. */
    private val browserTabModels = mutableStateMapOf<Int, Model>()
    private val browserRefreshIds = mutableMapOf<Int, Int>()
    private var browserRefreshCounter = 0
    private var playback by mutableStateOf<MediaController?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val viewerCoordinator by lazy {
        ViewerCoordinator(
            context = this,
            scope = lifecycleScope,
            listCache = listingCache,
            activeAccount = { accountUi.active },
            accessToken = { model.token },
            s3Config = { entry -> s3Accounts.accounts.firstOrNull { it.id == entry.id }?.config },
            currentFiles = { model.files },
            player = { playback },
            awaitPlayer = {
                playback ?: withContext(Dispatchers.IO) {
                    controllerFuture?.get() ?: kotlin.error(tr("Dịch vụ phát media chưa sẵn sàng."))
                }
            },
            requestNotificationPermission = ::ensureNotificationPermission,
            requestAccessToken = {
                // A cache refresh or authorization may already be restoring this account.
                if (!model.loading && pendingAuthorization == null) authorize()
            }
        )
    }
    private val viewer: ViewerState? get() = viewerCoordinator.state
    private var tab by mutableIntStateOf(0)
    private var generation = 0
    private var pendingAuthorization: String? = null
    private var pendingAuthorizationTarget: BrowserRefreshTarget? = null
    private var pendingPhotosAuthorization: String? = null
    private var pendingPhotosFiles: List<DriveFile> = emptyList()
    private var pendingPhotosMode: PhotosFolderUploadMode = PhotosFolderUploadMode.RAW
    private var pendingUpload: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingOpenPlayer = savedInstanceState?.getBoolean("openPlayer") ?: false
        handlePlayerIntent()
        enableEdgeToEdge()
        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this, tr("Không có quyền thông báo: trình phát vẫn chạy nền nhưng điều khiển media có thể không hiện trên thanh thông báo."), Toast.LENGTH_LONG).show()
            }
        }
        // Notifications are the only runtime permission this app needs. Ask automatically only
        // on the first eligible launch; feature entry points ask again when the permission is needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !selection.getBoolean("notificationPermissionOnboardingAsked", false)
        ) {
            selection.edit().putBoolean("notificationPermissionOnboardingAsked", true).apply()
            ensureNotificationPermission()
        }
        val sessionToken = SessionToken(this, ComponentName(this, MediaPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync().also { future ->
            future.addListener({
                runCatching { future.get() }
                    .onSuccess { controller ->
                        controller.addListener(playbackListener)
                        playback = controller
                        controller.currentMediaItem?.let(viewerCoordinator::syncToMediaItem)
                        openPendingPlayer()
                    }
                    .onFailure {
                        Toast.makeText(this, tr("Không thể kết nối dịch vụ phát media."), Toast.LENGTH_LONG).show()
                    }
            }, ContextCompat.getMainExecutor(this))
        }
        themeMode = runCatching { ThemeMode.valueOf(selection.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
        superDark = selection.getBoolean("superDark", false)
        pendingUploadParent = savedInstanceState?.getString("uploadParent")
        pendingAuthorization = savedInstanceState?.getString("authorizationAccount")
        pendingPhotosAuthorization = savedInstanceState?.getString("photosAuthorizationAccount")
        pendingPhotosMode = runCatching {
            PhotosFolderUploadMode.valueOf(savedInstanceState?.getString("photosUploadMode") ?: PhotosFolderUploadMode.RAW.name)
        }.getOrDefault(PhotosFolderUploadMode.RAW)
        pendingUpload = savedInstanceState?.getString("uploadAccount")
        savedInstanceState?.getStringArrayList("photosFiles")?.let { encoded ->
            pendingPhotosFiles = encoded.chunked(3).mapNotNull { parts ->
                if (parts.size == 3) DriveFile(parts[0], parts[1], parts[2], null) else null
            }
        }
        photosAuthorizationResolution = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val expected = pendingPhotosAuthorization
            pendingPhotosAuthorization = null
            if (expected == null || expected != accountUi.active?.key) return@registerForActivityResult
            if (result.resultCode != RESULT_OK) {
                pendingPhotosFiles = emptyList()
                pendingPhotosMode = PhotosFolderUploadMode.RAW
                Toast.makeText(this, tr("Đã hủy cấp quyền Google Photos."), Toast.LENGTH_SHORT).show()
            } else {
                runCatching { authorization.getAuthorizationResultFromIntent(result.data) }
                    .onSuccess { auth ->
                        auth.accessToken?.let { token ->
                            pendingPhotosFiles.takeIf { files -> files.isNotEmpty() }?.let { files ->
                                startPhotosUpload(files, token, pendingPhotosMode)
                            }
                        } ?: Toast.makeText(this, tr("Google không trả về quyền Photos."), Toast.LENGTH_LONG).show()
                    }
                    .onFailure {
                        pendingPhotosFiles = emptyList()
                        pendingPhotosMode = PhotosFolderUploadMode.RAW
                        Toast.makeText(this, tr("Không thể cấp quyền Google Photos."), Toast.LENGTH_LONG).show()
                    }
            }
        }
        resolution = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val expected = pendingAuthorization
            val target = pendingAuthorizationTarget
            pendingAuthorization = null
            pendingAuthorizationTarget = null
            if (expected == null || expected != accountUi.active?.key) return@registerForActivityResult
            if (result.resultCode != RESULT_OK) {
                if (target != null) browserError(target, tr("Đã hủy cấp quyền Google."))
                else error(tr("Đã hủy cấp quyền Google."))
            } else {
                runCatching { authorization.getAuthorizationResultFromIntent(result.data) }
                    .onSuccess { auth ->
                        val token = auth.accessToken
                        if (token != null) loadDrive(token, target)
                        else if (target != null) browserError(target, tr("Google không trả về access token."))
                        else error(tr("Google không trả về access token."))
                    }
                    .onFailure {
                        if (target != null) browserError(target, tr("Không thể cấp quyền Google."))
                        else error(tr("Không thể cấp quyền Google."))
                    }
            }
        }
        filePicker = registerForActivityResult(AndroidDocumentsMultiPicker()) { uris ->
            if (pendingUpload == accountUi.active?.key) upload(uris, parent = pendingUploadParent)
            pendingUpload = null
        }
        folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null && pendingUpload == accountUi.active?.key) upload(emptyList(), tree = uri, parent = pendingUploadParent)
            pendingUpload = null
        }
        servicePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importService)
        }
        accountPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
                if (!email.isNullOrBlank() && type == "com.google") {
                    googleStore.select(email)
                    refreshAccounts()
                    accountUi = accountUi.copy(revision = accountUi.revision + 1, message = null)
                    activate(accountUi.entries.first { it.type == AccountType.GOOGLE && it.id == email })
                } else accountError(tr("Không lấy được tài khoản Google đã chọn."))
            }
        }
        runCatching { s3Store.load() }
            .onSuccess { s3Accounts = it }
            .onFailure { s3Available = false; accountError(tr("Không thể đọc tài khoản S3 đã lưu.")) }
        runCatching { serviceStore.load() }
            .onSuccess { serviceAccounts = it }
            .onFailure { serviceAvailable = false; accountError(tr("Không thể đọc Service Account đã lưu.")) }
        refreshAccounts()
        val restoredKey = if (selection.contains("active")) selection.getString("active", "")
            else googleStore.active()?.let { "GOOGLE:$it" }
        accountUi.entries.find { it.key == restoredKey }?.let { entry ->
            accountUi = accountUi.copy(active = entry)
            model = Model(user = entry.name)
        }
        setContent {
            ManyDriveTheme(themeMode, superDark) {
                App(model, tab, ::selectTab, { refresh(forceNetwork = true) }, ::signIn, ::authorize, ::connectS3, ::signOut,
                    { pickUpload(false) }, ::createFolder, ::moveToTrash,
                    FileActionCallbacks(
                        download = ::downloadFile,
                        uploadToPhotos = ::uploadToPhotos,
                        uploadManyToPhotos = ::uploadToPhotos,
                        share = ::shareFile,
                        rename = ::renameFile,
                        loadPermissions = ::loadPermissions,
                        removePermission = ::removePermission,
                        loadFolders = ::loadMoveFolders,
                        move = ::moveFile,
                        moveMany = ::moveFiles,
                        trash = ::moveToTrash,
                        trashMany = ::moveToTrash
                    ),
                    accountUi, ::activate, ::removeAccount,
                    { servicePicker.launch(arrayOf("application/json", "text/json", "text/plain", "application/octet-stream")) },
                    ::openFolder, ::goUp, ::searchDrive, ::openSearchFolder, { pickUpload(true) }, ::restoreFile,
                    themeMode, superDark,
                    { themeMode = it; selection.edit().putString("theme", it.name).apply() },
                    { superDark = it; selection.edit().putBoolean("superDark", it).apply() },
                    { lifecycleScope.launch {
                        withContext(Dispatchers.IO) { listingCache.clearAll() }
                        Toast.makeText(this@MainActivity, tr("Đã xóa cache danh sách tệp"), Toast.LENGTH_SHORT).show()
                    } },
                    viewer, { file, queue -> openPreview(file, swipeQueue = queue) }, ::closePreview, ::updatePreviewText, ::savePreviewText, ::swipePreview,
                    playback, ::minimizePreview, ::expandPreview, browserModels = browserTabModels,
                    openLocalFile = { file, queue -> viewerCoordinator.openLocal(file, queue) },
                    uploadLocalFile = ::uploadLocalFile
                )
            }
        }
        if (pendingAuthorization == null) accountUi.active?.let { refresh() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("openPlayer", pendingOpenPlayer)
        outState.putString("authorizationAccount", pendingAuthorization)
        outState.putString("photosAuthorizationAccount", pendingPhotosAuthorization)
        outState.putString("photosUploadMode", pendingPhotosMode.name)
        if (pendingPhotosFiles.isNotEmpty()) {
            outState.putStringArrayList(
                "photosFiles",
                ArrayList(pendingPhotosFiles.flatMap { listOf(it.id, it.name, it.mimeType) })
            )
        }
        outState.putString("uploadAccount", pendingUpload)
        outState.putString("uploadParent", pendingUploadParent)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) MediaPagePlayback.owner.value?.endAppSession()
        playback?.removeListener(playbackListener)
        controllerFuture?.let(MediaController::releaseFuture)
        playback = null
        super.onDestroy()
    }

    private val playbackListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.let(viewerCoordinator::syncToMediaItem)
            openPendingPlayer()
        }
    }

    private fun refreshAccounts() {
        accountUi = accountUi.copy(entries =
            googleStore.accounts().map { email ->
                AccountEntry(AccountType.GOOGLE, email, storedAccountName(AccountType.GOOGLE, email),
                    avatarUrl = selection.getString("avatar:GOOGLE:$email", null))
            } + s3Accounts.accounts.map { AccountEntry(AccountType.S3, it.id, it.name, it.config.bucket, endpointUrl = it.config.endpoint) } +
                serviceAccounts.map { AccountEntry(AccountType.SERVICE, it.id, storedAccountName(AccountType.SERVICE, it.email),
                    avatarUrl = selection.getString("avatar:SERVICE:${it.id}", null)) })
    }

    private fun storedAccountName(type: AccountType, email: String): String =
        selection.getString("name:${type.name}:$email", null)
            ?.takeIf { it.isNotBlank() && '@' !in it } ?: email.substringBefore('@')

    private fun refreshAccountName(token: String) {
        val entry = accountUi.active ?: return
        if (entry.type == AccountType.S3 || selection.getBoolean("profile:${entry.key}", false)) return
        lifecycleScope.launch {
            val profile = withContext(Dispatchers.IO) { runCatching { DriveApi.accountProfile(token) }.getOrNull() }
                ?: return@launch
            if (accountUi.entries.none { it.key == entry.key }) return@launch
            val name = profile.displayName?.takeIf { it.isNotBlank() && '@' !in it }
            selection.edit().apply {
                name?.let { putString("name:${entry.key}", it) }
                if (profile.photoLink != null) putString("avatar:${entry.key}", profile.photoLink) else remove("avatar:${entry.key}")
                putBoolean("profile:${entry.key}", true)
            }.apply()
            refreshAccounts()
            if (accountUi.active?.key == entry.key) {
                val refreshed = accountUi.entries.firstOrNull { it.key == entry.key } ?: entry
                accountUi = accountUi.copy(active = refreshed)
                if (name != null) model = model.copy(user = name)
            }
        }
    }

    private fun activate(entry: AccountEntry) {
        if (viewer != null) closePreview()
        ++generation
        pendingAuthorization = null
        pendingAuthorizationTarget = null
        pendingPhotosAuthorization = null
        pendingPhotosFiles = emptyList()
        pendingPhotosMode = PhotosFolderUploadMode.RAW
        pendingUpload = null
        browserTabModels.clear()
        browserRefreshIds.clear()
        tab = 0
        accountUi = accountUi.copy(active = entry, message = null)
        selection.edit().putString("active", entry.key).apply()
        model = Model(user = entry.name)
        refresh()
    }

    private fun selectTab(next: Int) {
        if (!isTabEnabled(accountUi.active?.type, next) || tab == next) return

        // Keep the complete state of the page we are leaving, including an in-progress refresh.
        // Switching tabs does not invalidate its request; completion is routed back to this tab.
        if (tab in 0..1) browserTabModels[tab] = model

        val previous = model
        tab = next
        if (next in 0..1) {
            val restored = browserTabModels[next]
            model = restored?.copy(
                user = restored.user ?: previous.user,
                token = restored.token ?: previous.token
            ) ?: Model(user = previous.user, token = previous.token)
            if (restored == null) refresh()
        } else {
            model = Model(user = previous.user, token = previous.token)
            refresh()
        }
    }

    private fun searchDrive(query: String, done: (Result<List<DriveFile>>) -> Unit) {
        val account = accountUi.active
        val token = model.token
        if (account == null || account.type == AccountType.S3) {
            done(Result.failure(UnsupportedOperationException(tr("Tìm kiếm toàn Drive chỉ áp dụng cho Google Drive."))))
            return
        }
        if (token == null) {
            done(Result.failure(IllegalStateException(tr("Cần cấp quyền Drive trước khi tìm kiếm."))))
            return
        }
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { DriveApi.searchFiles(token, query) } }
            done(result)
        }
    }

    private fun openSearchFolder(file: DriveFile) {
        if (!file.isFolder || model.loading || tab == 3) return
        model = model.copy(path = listOf(file), files = emptyList(), fromCache = false)
        refresh()
    }

    private fun openFolder(file: DriveFile) {
        if (!file.isFolder || model.loading || tab == 3) return
        model = model.copy(path = model.path + file, files = emptyList())
        refresh()
    }

    private fun goUp() {
        if (model.path.isEmpty()) return
        model = model.copy(path = model.path.dropLast(1), files = emptyList())
        refresh()
    }

    private fun cacheLocation(tabIndex: Int = tab, path: List<DriveFile> = model.path): String =
        "$tabIndex:${path.lastOrNull()?.id.orEmpty()}"

    private fun cacheFiles(files: List<DriveFile>) {
        val account = accountUi.active?.key ?: return
        val location = cacheLocation()
        lifecycleScope.launch(Dispatchers.IO) { listingCache.write(account, location, files) }
    }

    private fun cacheFiles(target: BrowserRefreshTarget, files: List<DriveFile>) {
        val location = cacheLocation(target.tab, target.path)
        lifecycleScope.launch(Dispatchers.IO) { listingCache.write(target.accountKey, location, files) }
    }

    private fun newBrowserRefreshTarget(active: AccountEntry): BrowserRefreshTarget {
        val requestId = ++browserRefreshCounter
        browserRefreshIds[tab] = requestId
        return BrowserRefreshTarget(active.key, tab, model.path, requestId)
    }

    private fun isBrowserRefreshCurrent(target: BrowserRefreshTarget): Boolean =
        accountUi.active?.key == target.accountKey && browserRefreshIds[target.tab] == target.requestId

    private fun updateBrowserTab(target: BrowserRefreshTarget, update: (Model) -> Model): Boolean {
        if (!isBrowserRefreshCurrent(target)) return false
        if (tab == target.tab) {
            model = update(model)
        } else {
            val base = browserTabModels[target.tab]
                ?: Model(user = accountUi.active?.name, token = model.token, path = target.path)
            browserTabModels[target.tab] = update(base)
        }
        return true
    }

    private fun browserError(target: BrowserRefreshTarget, message: String) {
        updateBrowserTab(target) { it.copy(loading = false, message = message) }
        viewerCoordinator.onAccessTokenError(message)
    }

    /** Access tokens belong to the account, not to one tab. Keep every tab snapshot in sync. */
    private fun propagateBrowserToken(token: String) {
        model = model.copy(token = token)
        browserTabModels.keys.toList().forEach { index ->
            browserTabModels[index] = browserTabModels.getValue(index).copy(token = token)
        }
    }

    private fun refresh(forceNetwork: Boolean = false) {
        val active = accountUi.active ?: return
        val target = newBrowserRefreshTarget(active)
        val location = cacheLocation(target.tab, target.path)
        val startingToken = model.token
        updateBrowserTab(target) { it.copy(loading = true, message = null) }
        lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) { listingCache.read(active.key, location) }
            if (!isBrowserRefreshCurrent(target)) return@launch
            if (cached != null) {
                val needsToken = active.type != AccountType.S3 && startingToken == null
                updateBrowserTab(target) {
                    it.copy(files = cached, loading = forceNetwork || needsToken, fromCache = true)
                }
                if (!forceNetwork && !needsToken) return@launch
            }
            when (active.type) {
                AccountType.GOOGLE -> if (startingToken != null) loadDrive(startingToken, target) else authorize(target)
                AccountType.S3 -> loadS3(target)
                AccountType.SERVICE -> loadService(target)
            }
        }
    }

    private fun signIn() {
        runCatching {
            accountPicker.launch(AccountPicker.newChooseAccountIntent(
                AccountPicker.AccountChooserOptions.Builder()
                    .setAllowableAccountsTypes(listOf("com.google"))
                    .setAlwaysShowAccountPicker(true).build()))
        }.onFailure { accountError(tr("Không thể mở danh sách tài khoản Google.")) }
    }

    private fun authorize(target: BrowserRefreshTarget? = null) {
        val entry = accountUi.active ?: return
        val refreshTarget = target ?: newBrowserRefreshTarget(entry)
        if (entry.type != AccountType.GOOGLE) return when (entry.type) {
            AccountType.S3 -> loadS3(refreshTarget)
            AccountType.SERVICE -> loadService(refreshTarget)
            AccountType.GOOGLE -> Unit
        }
        updateBrowserTab(refreshTarget) { it.copy(loading = true, message = null) }
        val scopes = listOf(Scope(DRIVE_SCOPE))
        authorization.authorize(AuthorizationRequest.builder().setAccount(Account(entry.id, "com.google"))
            .setRequestedScopes(scopes).build())
            .addOnSuccessListener { result ->
                if (!isBrowserRefreshCurrent(refreshTarget)) return@addOnSuccessListener
                when {
                    result.hasResolution() -> {
                        pendingAuthorization = entry.key
                        pendingAuthorizationTarget = refreshTarget
                        result.pendingIntent?.let {
                            resolution.launch(IntentSenderRequest.Builder(it.intentSender).build())
                        } ?: browserError(refreshTarget, tr("Không thể mở cấp quyền."))
                    }
                    result.accessToken != null -> loadDrive(result.accessToken!!, refreshTarget)
                    else -> browserError(refreshTarget, tr("Google không trả về quyền Drive."))
                }
            }.addOnFailureListener {
                if (isBrowserRefreshCurrent(refreshTarget)) browserError(refreshTarget, tr("Không thể cấp quyền Google."))
            }
    }

    private fun loadDrive(token: String, target: BrowserRefreshTarget? = null) {
        val active = accountUi.active ?: return
        val refreshTarget = target ?: newBrowserRefreshTarget(active)
        refreshAccountName(token)
        val parentId = refreshTarget.path.lastOrNull()?.id
        if (isBrowserRefreshCurrent(refreshTarget)) propagateBrowserToken(token)
        updateBrowserTab(refreshTarget) { it.copy(loading = true, message = null, token = token) }
        viewerCoordinator.onAccessTokenAvailable()
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    DriveApi.listFiles(
                        token,
                        sharedWithMe = refreshTarget.tab == 1 && parentId == null,
                        parentId = parentId,
                        trashed = refreshTarget.tab == 3
                    )
                }
            }.onSuccess { files ->
                if (updateBrowserTab(refreshTarget) { it.copy(files = files, loading = false, fromCache = false) }) {
                    cacheFiles(refreshTarget, files)
                }
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                if (isBrowserRefreshCurrent(refreshTarget)) {
                    val diagnostic = driveLoadError(failure)
                    android.util.Log.e("ManyDrive", diagnostic + "\n" + failure.stackTrace.take(12).joinToString("\n"))
                    browserError(refreshTarget, diagnostic)
                }
            }
        }
    }

    private fun uploadToPhotos(file: DriveFile, mode: PhotosFolderUploadMode) = uploadToPhotos(listOf(file), mode)

    private fun uploadToPhotos(files: List<DriveFile>, mode: PhotosFolderUploadMode) {
        if (model.loading || files.isEmpty()) return
        val eligible = files.distinctBy { it.id }.filter {
            it.isFolder || it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/")
        }
        if (eligible.isEmpty()) {
            Toast.makeText(this, tr("Không có ảnh, video hoặc thư mục phù hợp để tải lên Google Photos."), Toast.LENGTH_LONG).show()
            return
        }
        val source = accountUi.active ?: return
        val destinations = accountUi.entries.filter { it.type == AccountType.GOOGLE }
        if (destinations.isEmpty()) {
            Toast.makeText(this, tr("Hãy thêm tài khoản Google để tải lên Google Photos."), Toast.LENGTH_LONG).show()
            return
        }
        fun authorizeDestination(destination: AccountEntry) {
            pendingPhotosFiles = eligible
            pendingPhotosMode = mode
            authorization.authorize(AuthorizationRequest.builder()
                .setAccount(Account(destination.id, "com.google"))
                .setRequestedScopes(listOf(Scope(PHOTOS_SCOPE))).build())
                .addOnSuccessListener { result ->
                    if (accountUi.active?.key != source.key) return@addOnSuccessListener
                    when {
                        result.hasResolution() -> {
                            pendingPhotosAuthorization = source.key
                            result.pendingIntent?.let {
                                photosAuthorizationResolution.launch(IntentSenderRequest.Builder(it.intentSender).build())
                            } ?: error(tr("Không thể mở cấp quyền Google Photos."))
                        }
                        result.accessToken != null -> startPhotosUpload(eligible, result.accessToken!!, mode)
                        else -> error(tr("Google không trả về quyền Photos."))
                    }
                }.addOnFailureListener { error(tr("Không thể cấp quyền Google Photos.")) }
        }
        if (source.type == AccountType.GOOGLE) authorizeDestination(source)
        else android.app.AlertDialog.Builder(this).setTitle(tr("Tài khoản Google Photos"))
            .setItems(destinations.map { it.id }.toTypedArray()) { _, index -> authorizeDestination(destinations[index]) }
            .setNegativeButton(tr("Hủy"), null).show()
    }

    private fun startPhotosUpload(files: List<DriveFile>, photosToken: String, mode: PhotosFolderUploadMode) {
        if (files.isEmpty()) return
        ensureNotificationPermission()
        val source = accountUi.active ?: return
        val driveToken = model.token
        val s3 = s3Accounts.accounts.firstOrNull { it.id == source.id }?.config
        pendingPhotosFiles = emptyList()
        pendingPhotosMode = PhotosFolderUploadMode.RAW
        val uploadNotifications = UploadNotifications(this)
        val total = if (files.any { it.isFolder }) null else files.count {
            it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/")
        }
        uploadNotifications.running(UploadNotifications.Kind.PHOTOS, completed = 0, failed = 0, total = total)
        lifecycleScope.launch {
            var uploaded = 0
            var failed = 0
            var cancelled = false
            var fatal: Throwable? = null
            var albumsCreated = 0
            try {
                withContext(Dispatchers.IO) {
                    val visited = mutableSetOf<String>()
                    files.forEach { root ->
                        val album = if (mode == PhotosFolderUploadMode.ALBUM && root.isFolder) {
                            PhotosApi.createAlbum(photosToken, root.name).also { albumsCreated++ }
                        } else null
                        val queue = java.util.ArrayDeque<DriveFile>().apply { add(root) }
                        while (queue.isNotEmpty()) {
                            val item = queue.removeFirst()
                            if (!visited.add(item.id)) continue
                            if (item.isFolder) {
                                val children = if (source.type == AccountType.S3) S3Api.list(requireNotNull(s3), item.id)
                                    else DriveApi.listFiles(requireNotNull(driveToken), parentId = item.id)
                                queue.addAll(children)
                                continue
                            }
                            if (!item.mimeType.startsWith("image/") && !item.mimeType.startsWith("video/")) continue
                            uploadNotifications.running(
                                UploadNotifications.Kind.PHOTOS,
                                currentName = item.name,
                                completed = uploaded,
                                failed = failed,
                                total = total
                            )
                            val temporary = File.createTempFile("photos-upload-", ".tmp", cacheDir)
                            try {
                                if (source.type == AccountType.S3) S3Api.downloadTo(requireNotNull(s3), item.id, temporary)
                                else DriveApi.downloadTo(requireNotNull(driveToken), item.id, temporary)
                                PhotosApi.upload(photosToken, temporary, item.name, item.mimeType, album)
                                uploaded++
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                failed++
                            } finally {
                                temporary.delete()
                            }
                            uploadNotifications.running(
                                UploadNotifications.Kind.PHOTOS,
                                completed = uploaded,
                                failed = failed,
                                total = total
                            )
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            } catch (e: Exception) {
                fatal = e
            } finally {
                val albumSuffix = if (albumsCreated > 0) tr(". Đã tạo $albumsCreated album.") else "."
                when {
                    cancelled -> uploadNotifications.finished(
                        UploadNotifications.Kind.PHOTOS,
                        tr("Đã hủy tải lên Google Photos. Đã tải $uploaded tệp, lỗi $failed tệp."),
                        success = false
                    )
                    fatal != null -> uploadNotifications.finished(
                        UploadNotifications.Kind.PHOTOS,
                        tr("Không thể hoàn tất tải lên Google Photos. Đã tải $uploaded tệp, lỗi $failed tệp."),
                        success = false
                    )
                    else -> uploadNotifications.finished(
                        UploadNotifications.Kind.PHOTOS,
                        tr("Đã tải $uploaded tệp lên Google Photos") +
                            (if (failed > 0) tr(", lỗi $failed tệp") else "") + albumSuffix,
                        success = failed == 0
                    )
                }
            }
        }
    }

    private fun loadS3(target: BrowserRefreshTarget? = null, completionMessage: String? = null) {
        val entry = accountUi.active ?: return
        val refreshTarget = target ?: newBrowserRefreshTarget(entry)
        val account = s3Accounts.accounts.find { it.id == entry.id } ?: return
        val prefix = refreshTarget.path.lastOrNull()?.id.orEmpty()
        updateBrowserTab(refreshTarget) { it.copy(user = account.name, loading = true, message = null) }
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { S3Api.list(account.config, prefix) } }
                .onSuccess { files ->
                    if (updateBrowserTab(refreshTarget) { it.copy(files = files, loading = false, fromCache = false, message = completionMessage) }) {
                        cacheFiles(refreshTarget, files)
                    }
                }
                .onFailure {
                    if (isBrowserRefreshCurrent(refreshTarget)) {
                        browserError(refreshTarget, tr("Không thể kết nối S3. Kiểm tra quyền bucket và mạng rồi thử làm mới."))
                    }
                }
        }
    }

    private fun loadService(target: BrowserRefreshTarget? = null) {
        val entry = accountUi.active ?: return
        val refreshTarget = target ?: newBrowserRefreshTarget(entry)
        val account = serviceAccounts.find { it.id == entry.id } ?: return
        val parentId = refreshTarget.path.lastOrNull()?.id
        val shared = refreshTarget.tab == 1 && parentId == null
        val trashed = refreshTarget.tab == 3
        val cached = serviceTokens[account.id]?.takeIf { it.validAt(System.currentTimeMillis() / 1000) }
        updateBrowserTab(refreshTarget) {
            it.copy(user = storedAccountName(AccountType.SERVICE, account.email), loading = true, message = null)
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val token = cached ?: ServiceAccountApi.accessToken(account)
                    token to DriveApi.listFiles(token.value, sharedWithMe = shared, parentId = parentId, trashed = trashed)
                }
            }.onSuccess { (token, files) ->
                if (isBrowserRefreshCurrent(refreshTarget)) {
                    serviceTokens[account.id] = token
                    refreshAccountName(token.value)
                    propagateBrowserToken(token.value)
                    if (updateBrowserTab(refreshTarget) {
                            it.copy(token = token.value, files = files, loading = false, fromCache = false)
                        }) {
                        viewerCoordinator.onAccessTokenAvailable()
                        cacheFiles(refreshTarget, files)
                    }
                }
            }.onFailure {
                if (isBrowserRefreshCurrent(refreshTarget)) {
                    serviceTokens.remove(account.id)
                    browserError(refreshTarget,
                        tr("Không thể tải Drive của Service Account. Kiểm tra khóa, Drive API và quyền chia sẻ rồi thử làm mới."))
                }
            }
        }
    }

    private fun connectS3(name: String, config: S3Config) {
        if (!s3Available) return accountError(tr("Không thể mở kho tài khoản S3 trên thiết bị."))
        val account = S3Account(name = name.trim(), config = config)
        accountUi = accountUi.copy(busy = true, message = null)
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { S3Api.list(config) } }
                .onSuccess { files ->
                    val saved = s3Accounts.copy(accounts = s3Accounts.accounts + account, activeId = account.id)
                    runCatching { s3Store.save(saved) }
                        .onSuccess {
                            s3Accounts = saved
                            refreshAccounts()
                            finishAdding(AccountEntry(AccountType.S3, account.id, account.name, config.bucket, endpointUrl = config.endpoint), files)
                        }.onFailure { accountError(tr("Không thể lưu tài khoản S3.")) }
                }.onFailure { accountError(tr("Không thể kết nối S3. Kiểm tra thông tin, quyền bucket và mạng.")) }
        }
    }

    private fun importService(uri: Uri) {
        if (!serviceAvailable) return accountError(tr("Không thể mở kho Service Account trên thiết bị."))
        accountUi = accountUi.copy(busy = true, message = null)
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= ServiceAccountCredentials.MAX_JSON_BYTES) { tr("File JSON quá lớn.") }
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    } ?: throw IllegalArgumentException(tr("Không thể đọc file JSON đã chọn."))
                    val credentials = ServiceAccountCredentials.parse(text)
                    val token = ServiceAccountApi.accessToken(credentials)
                    Triple(credentials, token, DriveApi.listFiles(token.value))
                }
            }.onSuccess { (credentials, token, files) ->
                val saved = serviceAccounts.filterNot { it.id == credentials.id } + credentials
                runCatching { serviceStore.save(saved) }
                    .onSuccess {
                        serviceAccounts = saved
                        serviceTokens[credentials.id] = token
                        refreshAccounts()
                        finishAdding(accountUi.entries.first { it.type == AccountType.SERVICE && it.id == credentials.id }, files, token.value)
                    }.onFailure { accountError(tr("Không thể lưu Service Account trên thiết bị.")) }
            }.onFailure {
                accountError(if (it is IllegalArgumentException) it.message ?: tr("File JSON không hợp lệ.")
                    else tr("Không thể kết nối Service Account. Kiểm tra khóa JSON, Drive API và mạng, rồi nhập lại file."))
            }
        }
    }

    private fun finishAdding(entry: AccountEntry, files: List<DriveFile>, token: String? = null) {
        ++generation
        pendingAuthorization = null
        pendingAuthorizationTarget = null
        pendingPhotosAuthorization = null
        pendingPhotosFiles = emptyList()
        pendingPhotosMode = PhotosFolderUploadMode.RAW
        pendingUpload = null
        browserTabModels.clear()
        browserRefreshIds.clear()
        tab = 0
        selection.edit().putString("active", entry.key).apply()
        accountUi = accountUi.copy(active = entry, busy = false, message = null, revision = accountUi.revision + 1)
        model = Model(user = entry.name, files = files, token = token)
        token?.let(::refreshAccountName)
        cacheFiles(files)
    }

    private fun removeAccount(entry: AccountEntry) {
        runCatching {
            when (entry.type) {
                AccountType.GOOGLE -> googleStore.remove(entry.id)
                AccountType.S3 -> {
                    val saved = s3Accounts.copy(accounts = s3Accounts.accounts.filterNot { it.id == entry.id },
                        activeId = s3Accounts.activeId?.takeUnless { it == entry.id })
                    s3Store.save(saved)
                    s3Accounts = saved
                }
                AccountType.SERVICE -> {
                    val saved = serviceAccounts.filterNot { it.id == entry.id }
                    serviceStore.save(saved)
                    serviceAccounts = saved
                    serviceTokens.remove(entry.id)
                }
            }
        }.onSuccess {
            lifecycleScope.launch(Dispatchers.IO) { listingCache.clear(entry.key) }
            selection.edit().remove("name:${entry.key}").remove("avatar:${entry.key}").remove("profile:${entry.key}").apply()
            if (accountUi.active?.key == entry.key) signOut()
            refreshAccounts()
        }.onFailure { accountError(tr("Không thể xóa tài khoản đã lưu.")) }
    }

    private fun downloadFile(file: DriveFile) {
        val account = accountUi.active ?: return
        val token = model.token
        val service = serviceAccounts.find { it.id == account.id }
        val config = s3Accounts.accounts.find { it.id == account.id }?.config
        val start: () -> Unit = {
            Toast.makeText(this, tr("Đang thêm vào danh sách tải xuống…"), Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val access = if (account.type == AccountType.SERVICE)
                            ServiceAccountApi.accessToken(requireNotNull(service)).value else token
                        com.starfall.gsadrive.data.DocumentDownloads(
                            this@MainActivity,
                            list = { folder ->
                                if (account.type == AccountType.S3) S3Api.list(requireNotNull(config), folder.id)
                                else DriveApi.listFiles(requireNotNull(access), parentId = folder.id)
                            },
                            source = { item ->
                                if (account.type == AccountType.S3) S3Api.downloadSource(requireNotNull(config), item.id)
                                else com.starfall.gsadrive.data.DownloadSource(
                                    "https://www.googleapis.com/drive/v3/files/${Uri.encode(item.id)}?alt=media&supportsAllDrives=true",
                                    mapOf("Authorization" to "Bearer ${requireNotNull(access)}")
                                )
                            }
                        ).download(file)
                    }
                }
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                Toast.makeText(this@MainActivity,
                    if (result.isSuccess) tr("Đã giao ${result.getOrNull()} tệp cho trình tải xuống Android.")
                    else tr("Không thể thêm hết tệp vào danh sách tải. Các tệp đã thêm vẫn tiếp tục tải trên Android."),
                    Toast.LENGTH_LONG).show()
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            if (pendingLegacyDownload == null) {
                pendingLegacyDownload = start
                downloadPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else start()
    }

    private fun uploadLocalFile(file: DriveFile, done: (Result<Unit>) -> Unit) {
        val account = accountUi.active
        if (account == null) {
            done(Result.failure(IllegalStateException(tr("Hãy thêm hoặc chọn một tài khoản trước khi tải lên."))))
            return
        }
        val config = s3Accounts.accounts.firstOrNull { it.id == account.id }?.config
        val service = serviceAccounts.firstOrNull { it.id == account.id }
        val existingToken = model.token
        val parent = model.path.lastOrNull()?.id
        if (account.type == AccountType.GOOGLE && existingToken == null) {
            authorize()
            done(Result.failure(IllegalStateException(tr("Hãy cấp quyền Drive trước khi tải lên."))))
            return
        }
        ensureNotificationPermission()
        val notifications = UploadNotifications(this)
        notifications.running(UploadNotifications.Kind.DRIVE, completed = 0, total = null)
        lifecycleScope.launch {
            var completed = 0
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val access = com.starfall.gsadrive.data.LocalFileAccess(android.os.Environment.getExternalStorageDirectory())
                    val source = access.checked(file.id)
                    val files = access.tree(file.id)
                    val token = if (account.type == AccountType.SERVICE)
                        ServiceAccountApi.accessToken(requireNotNull(service)).value else existingToken
                    val folders = mutableMapOf<String, String>()
                    for (local in files) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val destination = if (local == source) parent else folders.getValue(local.parentFile.path)
                        if (local.isDirectory) {
                            folders[local.path] = if (account.type == AccountType.S3) {
                                val key = destination.orEmpty() + local.name + "/"
                                S3Api.createFolder(requireNotNull(config), key)
                                key
                            } else DriveApi.createFolder(requireNotNull(token), local.name, destination)
                        } else {
                            notifications.running(UploadNotifications.Kind.DRIVE, currentName = local.name,
                                completed = completed, total = null)
                            val mime = android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(local.extension.lowercase()) ?: "application/octet-stream"
                            if (account.type == AccountType.S3)
                                S3Api.upload(requireNotNull(config), destination.orEmpty() + local.name, mime, local)
                            else local.inputStream().use { DriveApi.upload(requireNotNull(token), local.name, mime, it, destination) }
                            completed++
                        }
                    }
                }
            }
            withContext(kotlinx.coroutines.NonCancellable) {
                withContext(Dispatchers.IO) { listingCache.clear(account.key) }
                notifications.finished(UploadNotifications.Kind.DRIVE,
                    if (result.isSuccess) tr("Đã tải lên $completed tệp.") else tr("Không thể hoàn tất tải lên."),
                    success = result.isSuccess)
                done(result)
                if (accountUi.active?.key == account.key) refresh(forceNetwork = true)
            }
        }
    }

    private fun pickUpload(folder: Boolean) {
        val account = accountUi.active
        if (account == null) {
            Toast.makeText(this, tr("Hãy thêm hoặc chọn một tài khoản trước khi tải lên."), Toast.LENGTH_SHORT).show()
            return
        }
        pendingUpload = account.key
        pendingUploadParent = model.path.lastOrNull()?.id
        if (folder) folderPicker.launch(null) else filePicker.launch(arrayOf("*/*"))
    }

    private fun upload(uris: List<Uri>, tree: Uri? = null, parent: String? = null) {
        if (uris.isEmpty() && tree == null) return
        ensureNotificationPermission()
        val account = accountUi.active ?: return
        val s3 = s3Accounts.accounts.find { it.id == account.id }?.config
        val service = serviceAccounts.find { it.id == account.id }
        val existingToken = model.token
        val uploadTab = tab
        val uploadPath = model.path.toList()
        val uploadNotifications = UploadNotifications(this)
        val total = if (tree == null) uris.size else null
        uploadNotifications.running(UploadNotifications.Kind.DRIVE, completed = 0, total = total)
        lifecycleScope.launch {
            var completed = 0
            var currentName: String? = null
            var cancelled = false
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val token = if (account.type == AccountType.SERVICE) ServiceAccountApi.accessToken(requireNotNull(service)).value
                        else existingToken
                    val uploader = DocumentUploads(
                        contentResolver,
                        createFolder = { name, destination ->
                            if (account.type == AccountType.S3) {
                                val key = destination.orEmpty() + name + "/"
                                S3Api.createFolder(requireNotNull(s3), key)
                                key
                            } else DriveApi.createFolder(
                                requireNotNull(token) { tr("Hãy cấp quyền Drive trước khi tải lên.") },
                                name,
                                destination
                            )
                        },
                        uploadFile = { uri, name, mime, destination ->
                            currentName = name
                            uploadNotifications.running(
                                UploadNotifications.Kind.DRIVE,
                                currentName = name,
                                completed = completed,
                                total = total
                            )
                            contentResolver.openInputStream(uri)?.use { input ->
                                if (account.type == AccountType.S3) {
                                    val temporary = File.createTempFile("upload-", ".tmp", cacheDir)
                                    try {
                                        temporary.outputStream().use { input.copyTo(it, 64 * 1024) }
                                        S3Api.upload(requireNotNull(s3), destination.orEmpty() + name, mime, temporary)
                                    } finally {
                                        temporary.delete()
                                    }
                                } else DriveApi.upload(requireNotNull(token), name, mime, input, destination)
                            } ?: kotlin.error(tr("Không đọc được tệp $name."))
                            completed++
                            uploadNotifications.running(
                                UploadNotifications.Kind.DRIVE,
                                completed = completed,
                                total = total
                            )
                        }
                    )
                    if (tree != null) uploader.tree(tree, parent) else uploader.files(uris, parent)
                }
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) cancelled = true }
            when {
                cancelled -> uploadNotifications.finished(
                    UploadNotifications.Kind.DRIVE,
                    tr("Đã hủy tải lên. Đã hoàn tất $completed tệp."),
                    success = false
                )
                result.isSuccess -> uploadNotifications.finished(
                    UploadNotifications.Kind.DRIVE,
                    tr("Đã tải lên $completed tệp."),
                    success = true
                )
                else -> uploadNotifications.finished(
                    UploadNotifications.Kind.DRIVE,
                    tr("Tải lên bị dừng tại ${currentName ?: tr("một tệp")}. Đã hoàn tất $completed tệp."),
                    success = false
                )
            }
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { listingCache.clear(account.key) }
            if (cancelled) throw result.exceptionOrNull() as CancellationException
            // Completion must not refresh another account or interrupt navigation/mutations.
            if (accountUi.active?.key == account.key) {
                if (tab == uploadTab && model.path == uploadPath && !model.loading) {
                    refresh(forceNetwork = true)
                }
            }
        }
    }

    private fun restoreFile(file: DriveFile) {
        val token = model.token ?: return
        val account = accountUi.active ?: return
        val request = ++generation
        model = model.copy(loading = true, message = null)
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                DriveApi.restore(token, file.id)
                listingCache.clear(account.key)
            } }.onSuccess { if (request == generation) refresh(forceNetwork = true) }
                .onFailure { if (request == generation) error(tr("Không thể khôi phục tệp. Kiểm tra quyền và kết nối.")) }
        }
    }

    private fun createFolder(name: String) {
        if (model.loading || name.isBlank() || accountUi.active?.type != AccountType.GOOGLE) return
        val token = model.token ?: return
        val request = ++generation
        val parentId = model.path.lastOrNull()?.id
        model = model.copy(loading = true, message = null)
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { DriveApi.createFolder(token, name.trim(), parentId); accountUi.active?.key?.let { listingCache.clear(it) } } }
                .onSuccess { if (request == generation) refresh(forceNetwork = true) }
                .onFailure { if (request == generation) error(tr("Không thể tạo thư mục.")) }
        }
    }

    private fun shareFile(file: DriveFile, email: String, role: String, done: (Result<Unit>) -> Unit) {
        val token = model.token
        if (token == null) return done(Result.failure(IllegalStateException(tr("Cần cấp quyền Drive trước."))))
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { DriveApi.share(token, file.id, email.trim(), role) } }
            if (result.isSuccess) Toast.makeText(this@MainActivity, tr("Đã chia sẻ ${file.name}."), Toast.LENGTH_SHORT).show()
            done(result)
        }
    }

    private fun renameFile(file: DriveFile, newName: String, done: (Result<Unit>) -> Unit) {
        val token = model.token
        val accountKey = accountUi.active?.key
        if (token == null || accountKey == null) return done(Result.failure(IllegalStateException(tr("Cần cấp quyền Drive trước."))))
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                DriveApi.rename(token, file.id, newName.trim())
                listingCache.clear(accountKey)
            } }
            done(result)
            if (result.isSuccess && accountUi.active?.key == accountKey) refresh(forceNetwork = true)
        }
    }

    private fun loadPermissions(file: DriveFile, done: (Result<List<DrivePermission>>) -> Unit) {
        val token = model.token
        if (token == null) return done(Result.failure(IllegalStateException(tr("Cần cấp quyền Drive trước."))))
        lifecycleScope.launch {
            done(runCatching { withContext(Dispatchers.IO) { DriveApi.listPermissions(token, file.id) } })
        }
    }

    private fun removePermission(file: DriveFile, permission: DrivePermission, done: (Result<Unit>) -> Unit) {
        val token = model.token
        if (token == null) return done(Result.failure(IllegalStateException(tr("Cần cấp quyền Drive trước."))))
        lifecycleScope.launch {
            done(runCatching { withContext(Dispatchers.IO) { DriveApi.deletePermission(token, file.id, permission.id) } })
        }
    }

    private fun loadMoveFolders(parentId: String?, done: (Result<List<DriveFile>>) -> Unit) {
        val token = model.token
        if (token == null) return done(Result.failure(IllegalStateException(tr("Cần cấp quyền Drive trước."))))
        lifecycleScope.launch {
            done(runCatching { withContext(Dispatchers.IO) {
                DriveApi.listFiles(token, parentId = parentId).filter { it.isFolder }
            } })
        }
    }

    private fun moveFile(file: DriveFile, destinationId: String, done: (Result<Unit>) -> Unit) =
        moveFiles(listOf(file), destinationId, done)

    private fun moveFiles(files: List<DriveFile>, destinationId: String, done: (Result<Unit>) -> Unit) {
        val token = model.token
        val accountKey = accountUi.active?.key
        if (token == null || accountKey == null) return done(Result.failure(IllegalStateException(tr("Cần cấp quyền Drive trước."))))
        val unique = files.distinctBy { it.id }
        if (unique.isEmpty()) return done(Result.success(Unit))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var failed = 0
                var firstFailure: Throwable? = null
                unique.forEach { file ->
                    runCatching { DriveApi.move(token, file, destinationId) }
                        .onFailure { failure ->
                            failed++
                            if (firstFailure == null) firstFailure = failure
                        }
                }
                listingCache.clear(accountKey)
                if (failed == 0) Result.success(Unit)
                else Result.failure(IllegalStateException(
                    tr("Không thể di chuyển $failed/${unique.size} mục."),
                    firstFailure
                ))
            }
            done(result)
            if (accountUi.active?.key == accountKey) refresh(forceNetwork = true)
        }
    }

    private fun moveToTrash(file: DriveFile) = moveToTrash(listOf(file))

    private fun moveToTrash(files: List<DriveFile>) {
        if (accountUi.active?.type == AccountType.S3) {
            deleteS3Files(files)
            return
        }
        if (model.loading || accountUi.active?.type !in setOf(AccountType.GOOGLE, AccountType.SERVICE)) return
        val token = model.token ?: return
        val unique = files.distinctBy { it.id }
        if (unique.isEmpty()) return
        val request = ++generation
        model = model.copy(loading = true, message = null)
        lifecycleScope.launch {
            val failures = withContext(Dispatchers.IO) {
                var failed = 0
                unique.forEach { file ->
                    if (runCatching { DriveApi.moveToTrash(token, file.id) }.isFailure) failed++
                }
                accountUi.active?.key?.let { listingCache.clear(it) }
                failed
            }
            if (request == generation) {
                if (failures > 0) {
                    model = model.copy(loading = false, message = tr("Không thể chuyển $failures/${unique.size} mục vào thùng rác."))
                } else {
                    refresh(forceNetwork = true)
                }
            }
        }
    }

    private fun deleteS3Files(files: List<DriveFile>) {
        if (model.loading) return
        val account = accountUi.active?.takeIf { it.type == AccountType.S3 } ?: return
        val config = s3Accounts.accounts.firstOrNull { it.id == account.id }?.config ?: return
        val unique = files.distinctBy { it.id }
        if (unique.isEmpty()) return
        val request = ++generation
        model = model.copy(loading = true, message = null)
        lifecycleScope.launch {
            val failures = withContext(Dispatchers.IO) {
                var failed = 0
                for (file in unique) {
                    try {
                        S3Api.delete(config, file)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed++
                    }
                }
                // Capture the source account: switching accounts must never invalidate another cache.
                listingCache.clear(account.key)
                failed
            }
            if (request == generation && accountUi.active?.key == account.key) {
                // Refresh even after partial failure, since some objects may already be gone.
                loadS3(completionMessage = if (failures == 0) null
                    else tr("Không thể xóa một số mục S3. Kiểm tra quyền xóa và thử lại.") + " ($failures/${unique.size})")
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openPreview(file: DriveFile, minimized: Boolean = false, swipeQueue: List<DriveFile>? = null) =
        viewerCoordinator.open(file, minimized, swipeQueue)

    private fun swipePreview(index: Int) = viewerCoordinator.swipeTo(index)

    private fun closePreview() = viewerCoordinator.close()

    private fun minimizePreview() = viewerCoordinator.minimize()

    private fun expandPreview() = viewerCoordinator.expand()

    private fun updatePreviewText(text: String) = viewerCoordinator.updateText(text)

    private fun savePreviewText() = viewerCoordinator.saveText()

    private fun signOut() {
        if (viewer != null) closePreview()
        accountUi.active?.key?.let { key -> lifecycleScope.launch(Dispatchers.IO) { listingCache.clear(key) } }
        ++generation
        pendingAuthorization = null
        pendingAuthorizationTarget = null
        pendingPhotosAuthorization = null
        pendingPhotosFiles = emptyList()
        pendingPhotosMode = PhotosFolderUploadMode.RAW
        pendingUpload = null
        serviceTokens.clear()
        googleStore.clearActive()
        selection.edit().putString("active", "").apply()
        accountUi = accountUi.copy(active = null)
        browserTabModels.clear()
        browserRefreshIds.clear()
        tab = 0
        model = Model(message = tr("Đã đăng xuất."))
    }

    private fun accountError(message: String) { accountUi = accountUi.copy(busy = false, message = message) }
    private fun error(message: String) {
        model = model.copy(loading = false, message = message)
        viewerCoordinator.onAccessTokenError(message)
    }
}
