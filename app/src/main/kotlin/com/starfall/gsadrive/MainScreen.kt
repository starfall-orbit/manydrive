package com.starfall.gsadrive

import com.starfall.gsadrive.ui.CopyableError

import android.os.Environment
import java.io.File
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.starfall.gsadrive.data.*
import com.starfall.gsadrive.ui.FileViewerPage
import com.starfall.gsadrive.ui.ExpandableMediaPlayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.starfall.gsadrive.ui.NavigationSwipe
import com.starfall.gsadrive.ui.navigationSwipes
import com.starfall.gsadrive.ui.SettingsPage
import com.starfall.gsadrive.ui.theme.ManyDriveTheme
import com.starfall.gsadrive.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged


private data class BrowserTabSearchState(
    val query: String = "",
    val folderSearching: Boolean = false,
    val results: List<DriveFile>? = null,
    val loading: Boolean = false,
    val error: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun App(
    model: Model,
    selected: Int,
    select: (Int) -> Unit,
    reload: () -> Unit,
    signIn: () -> Unit,
    authorize: () -> Unit,
    connectS3: (String, S3Config) -> Unit,
    signOut: () -> Unit,
    upload: () -> Unit,
    createFolder: (String) -> Unit,
    trash: (DriveFile) -> Unit,
    fileActions: FileActionCallbacks = FileActionCallbacks(),
    accounts: AccountUi = AccountUi(),
    selectAccount: (AccountEntry) -> Unit = {},
    removeAccount: (AccountEntry) -> Unit = {},
    importService: () -> Unit = {},
    openFolder: (DriveFile) -> Unit = {},
    goUp: () -> Unit = {},
    searchDrive: (String, (Result<List<DriveFile>>) -> Unit) -> Unit = { _, done -> done(Result.success(emptyList())) },
    openSearchFolder: (DriveFile) -> Unit = {},
    uploadFolder: () -> Unit = {},
    restoreFile: (DriveFile) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    superDark: Boolean = false,
    setThemeMode: (ThemeMode) -> Unit = {},
    setSuperDark: (Boolean) -> Unit = {},
    clearCache: () -> Unit = {},
    viewer: ViewerState? = null,
    openFile: (DriveFile, List<DriveFile>) -> Unit = { _, _ -> },
    closeViewer: () -> Unit = {},
    updateViewerText: (String) -> Unit = {},
    saveViewerText: () -> Unit = {},
    swipeViewer: (Int) -> Unit = {},
    playback: Player? = null,
    minimizeViewer: () -> Unit = {},
    expandViewer: () -> Unit = {},
    browserModels: Map<Int, Model> = emptyMap(),
    openLocalFile: (DriveFile, List<DriveFile>) -> Unit = { _, _ -> },
    uploadLocalFile: (DriveFile, (Result<Unit>) -> Unit) -> Unit = { _, done -> done(Result.failure(IllegalStateException("No cloud account"))) }
) {
    var showAccounts by remember { mutableStateOf(false) }
    var showTypes by remember { mutableStateOf(false) }
    var addingS3 by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<AccountEntry?>(null) }
    val systemFilesState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var showSystemFiles by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val systemFilesRoot = remember { Environment.getExternalStorageDirectory().path }
    var systemFilesDirectory by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(systemFilesRoot) }
    var systemFilesRevision by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showFabMenu by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    val active = accounts.active
    val tabSearchStates = remember(active?.key) { mutableStateMapOf<Int, BrowserTabSearchState>() }
    fun tabSearchState(index: Int): BrowserTabSearchState = tabSearchStates[index] ?: BrowserTabSearchState()
    fun updateTabSearch(index: Int, update: (BrowserTabSearchState) -> BrowserTabSearchState) {
        tabSearchStates[index] = update(tabSearchState(index))
    }
    val currentSearch = if (selected in 0..1) tabSearchState(selected) else BrowserTabSearchState()
    val searchQuery = currentSearch.query
    val folderSearching = currentSearch.folderSearching
    val globalSearchResults = currentSearch.results
    val globalSearchLoading = currentSearch.loading
    val globalSearchError = currentSearch.error

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    LaunchedEffect(accounts.revision) {
        showAccounts = false
        showTypes = false
        addingS3 = false
    }
    LaunchedEffect(searchQuery, active?.key, selected) {
        if (selected !in 0..1) return@LaunchedEffect
        val tabIndex = selected
        updateTabSearch(tabIndex) { it.copy(error = null) }
        val query = searchQuery.trim()
        if (query.isEmpty() || active == null || active.type == AccountType.S3) {
            updateTabSearch(tabIndex) { it.copy(results = null, loading = false) }
            return@LaunchedEffect
        }
        val accountKey = active.key
        updateTabSearch(tabIndex) { it.copy(results = emptyList(), loading = true) }
        delay(350)
        searchDrive(query) { result ->
            if (tabSearchState(tabIndex).query.trim() != query || accounts.active?.key != accountKey) return@searchDrive
            result.onSuccess { files ->
                updateTabSearch(tabIndex) { it.copy(results = files, loading = false, error = null) }
            }.onFailure { failure ->
                updateTabSearch(tabIndex) {
                    it.copy(results = emptyList(), loading = false,
                        error = failure.message ?: tr("Không thể tìm kiếm trên Drive."))
                }
            }
        }
    }
    val tabs = listOf(
        Tab(tr("Tệp"), Icons.Outlined.Folder), Tab(tr("Chia sẻ"), Icons.Outlined.People)
    )
    val tabPagerState = rememberPagerState(
        initialPage = selected.coerceIn(0, tabs.lastIndex),
        pageCount = { tabs.size }
    )
    LaunchedEffect(selected) {
        if (selected in tabs.indices && !tabPagerState.isScrollInProgress && tabPagerState.currentPage != selected) {
            tabPagerState.scrollToPage(selected)
        }
    }
    LaunchedEffect(tabPagerState, active?.type, selected) {
        // Sidebar destinations are outside the pager; its retained page must not replace them.
        if (selected !in tabs.indices) return@LaunchedEffect
        snapshotFlow { tabPagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (page in tabs.indices && page != selected && isTabEnabled(active?.type, page)) select(page)
            }
    }
    fun requestBrowserTab(index: Int) {
        if (index !in tabs.indices || !isTabEnabled(active?.type, index)) return
        showSettings = false
        showSystemFiles = false
        if (selected in tabs.indices) {
            drawerScope.launch { tabPagerState.animateScrollToPage(index) }
        } else {
            select(index)
        }
    }
    val openAccounts = { showAccounts = true }
    val viewerExpanded = viewer != null && !viewer.minimized
    val mediaViewer = viewer?.takeIf { isSwipePreview(it.file) && it.swipeQueue.isNotEmpty() }
    val mediaFull = viewerExpanded && mediaViewer != null && playback != null
    // Non-media previews still use the app viewer scaffold. Expanded media does not.
    val appViewerExpanded = viewerExpanded && mediaViewer == null
    val viewerBlack = viewerExpanded && viewer?.let { isSwipePreview(it.file) } == true
    var miniBounds by remember { mutableStateOf<Rect?>(null) }
    var playerTopPadding by remember { mutableStateOf(0.dp) }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val normalDarkBars = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect {
        val activity = view.context as? android.app.Activity ?: return@SideEffect
        activity.window.statusBarColor = if (viewerBlack) android.graphics.Color.BLACK else android.graphics.Color.TRANSPARENT
        activity.window.navigationBarColor = if (viewerBlack) android.graphics.Color.BLACK else android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
        }
        androidx.core.view.WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !viewerBlack && !normalDarkBars
            isAppearanceLightNavigationBars = !viewerBlack && !normalDarkBars
        }
    }
    BackHandler(enabled = !viewerExpanded && showFabMenu) { showFabMenu = false }
    BackHandler(enabled = !viewerExpanded && drawerState.isOpen && !showFabMenu) { drawerScope.launch { drawerState.close() } }
    BackHandler(enabled = !appViewerExpanded && showSettings && !drawerState.isOpen) { showSettings = false }
    BackHandler(
        enabled = !viewerExpanded && selected == 3 && !showFabMenu && !drawerState.isOpen && !showSettings && !showSystemFiles &&
            !showAccounts && !showTypes && !addingS3
    ) { select(0) }
    BackHandler(enabled = !viewerExpanded && model.path.isNotEmpty() && !showSettings && !showSystemFiles && !showAccounts && !showTypes && !addingS3 && !drawerState.isOpen) {
        if (selected in tabs.indices) tabSearchStates[selected] = BrowserTabSearchState()
        goUp()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Native dragging remains available to close an open drawer. Opening is edge-only.
        gesturesEnabled = !viewerExpanded && drawerState.isOpen,
        drawerContent = {
            DriveNavigationDrawer(
                account = active,
                trashSelected = !showSettings && !showSystemFiles && selected == 3,
                settingsSelected = showSettings,
                systemFilesSelected = showSystemFiles && !showSettings,
                onSystemFiles = {
                    showSystemFiles = true
                    showSettings = false
                    showFabMenu = false
                    drawerScope.launch { drawerState.close() }
                },
                onAccounts = {
                    drawerScope.launch { drawerState.close() }
                    showAccounts = true
                },
                onTrash = {
                    if (isTabEnabled(active?.type, 3)) {
                        showSettings = false
                        showSystemFiles = false
                        drawerScope.launch { drawerState.close() }
                        select(3)
                    }
                },
                onSettings = {
                    showSettings = true
                    drawerScope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            // Keep the browser mounted and geometrically stable behind the independent media
            // screen. This avoids rebuilding the whole browser during mini/full transitions.
            Scaffold(
                containerColor = if (appViewerExpanded && viewerBlack) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.background,
                topBar = {
                    when {
                        !appViewerExpanded && !showSettings && !showSystemFiles && selected in 0..1 && (active == null || model.path.isEmpty()) ->
                            DriveRootTopBar(
                                query = searchQuery,
                                onQueryChange = { value ->
                                    if (selected in tabs.indices) updateTabSearch(selected) { it.copy(query = value) }
                                },
                                onMenu = { drawerScope.launch { drawerState.open() } },
                                onAccounts = openAccounts,
                                account = active
                            )
                        !appViewerExpanded && !showSettings && !showSystemFiles && selected in 0..1 && active != null && model.path.isNotEmpty() ->
                            FolderBrowserTopBar(
                                title = model.path.last().name,
                                query = searchQuery,
                                searching = folderSearching,
                                onQueryChange = { value ->
                                    if (selected in tabs.indices) updateTabSearch(selected) { it.copy(query = value) }
                                },
                                onSearchingChange = { searching ->
                                    if (selected in tabs.indices) updateTabSearch(selected) { it.copy(folderSearching = searching) }
                                },
                                onBack = {
                                    if (selected in tabs.indices) tabSearchStates[selected] = BrowserTabSearchState()
                                    goUp()
                                },
                                onRefresh = reload,
                                onAccounts = openAccounts
                            )
                        !appViewerExpanded && !showSettings && showSystemFiles -> TopAppBar(
                            title = {
                                Text(
                                    if (systemFilesDirectory == systemFilesRoot) tr("Tệp Hệ Thống")
                                    else File(systemFilesDirectory).name.ifBlank { tr("Tệp Hệ Thống") },
                                    style = MaterialTheme.typography.headlineSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            navigationIcon = {
                                if (systemFilesDirectory == systemFilesRoot) {
                                    IconButton(onClick = { drawerScope.launch { drawerState.open() } }) {
                                        Icon(Icons.Outlined.Menu, tr("Mở menu"))
                                    }
                                } else {
                                    IconButton(onClick = {
                                        systemFilesDirectory = File(systemFilesDirectory).parent ?: systemFilesRoot
                                    }) {
                                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("Quay lại"))
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = { systemFilesRevision++ }) {
                                    Icon(Icons.Outlined.Refresh, tr("Làm mới"))
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                        )
                        !appViewerExpanded && showSettings -> TopAppBar(
                            title = {
                                Text(tr("Cài đặt"), style = MaterialTheme.typography.headlineSmall)
                            },
                            navigationIcon = {
                                IconButton(onClick = { showSettings = false }) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("Quay lại"))
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                        )
                        else -> CenterAlignedTopAppBar(
                            title = {
                                Text(when {
                                    appViewerExpanded -> viewer?.file?.name.orEmpty()
                                    selected == 3 -> tr("Thùng rác")
                                    else -> "ManyDrive"
                                }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            navigationIcon = {
                                when {
                                    appViewerExpanded -> IconButton(onClick = {
                                        if (viewer?.let { isMediaPreview(it.file) } == true) minimizeViewer() else closeViewer()
                                    }) {
                                        Icon(Icons.AutoMirrored.Outlined.ArrowBack,
                                            if (viewer?.let { isMediaPreview(it.file) } == true) tr("Thu nhỏ trình phát") else tr("Đóng trình xem"))
                                    }
                                    else -> IconButton(onClick = { drawerScope.launch { drawerState.open() } }) {
                                        Icon(Icons.Outlined.Menu, tr("Mở menu"))
                                    }
                                }
                            },
                            actions = {
                                if (!appViewerExpanded && !showSettings && !showSystemFiles && active != null)
                                    AccountAvatar(active, openAccounts)
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = if (viewerBlack) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surface,
                                titleContentColor = if (viewerBlack) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface,
                                navigationIconContentColor = if (viewerBlack) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface,
                                actionIconContentColor = if (viewerBlack) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                },
                bottomBar = {
                    // This browser bar is retained only under the mini/full transition.
                    if (!appViewerExpanded) Column(
                        modifier = if (showSettings) Modifier.navigationBarsPadding() else Modifier
                    ) {
                        if (mediaViewer != null && playback != null) {
                            // Reserve and measure the compact slot; the video itself remains in the overlay.
                            Spacer(Modifier.fillMaxWidth().height(72.dp).onGloballyPositioned {
                                miniBounds = it.boundsInRoot()
                            })
                        }
                        if (!showSettings) NavigationBar {
                            tabs.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    selected = !showSystemFiles && selected in tabs.indices && index == tabPagerState.currentPage,
                                    onClick = { requestBrowserTab(index) },
                                    enabled = isTabEnabled(active?.type, index),
                                    icon = { Icon(item.icon, item.label) }, label = { Text(item.label) })
                            }
                        }
                    }
                },
                floatingActionButton = {
                    if (!appViewerExpanded && !showSettings && !showSystemFiles) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (showFabMenu) {
                                ExtendedFloatingActionButton(
                                    onClick = {
                                        showFabMenu = false
                                        upload()
                                    },
                                    icon = { Icon(Icons.Outlined.UploadFile, null) },
                                    text = { Text(tr("Tải lên")) },
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                ExtendedFloatingActionButton(
                                    onClick = {
                                        showFabMenu = false
                                        uploadFolder()
                                    },
                                    icon = { Icon(Icons.Outlined.DriveFolderUpload, null) },
                                    text = { Text(tr("Tải thư mục")) },
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                ExtendedFloatingActionButton(
                                    onClick = {
                                        showFabMenu = false
                                        newFolderName = ""
                                        showCreateFolderDialog = true
                                    },
                                    icon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                                    text = { Text(tr("Thư mục")) },
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            FloatingActionButton(
                                onClick = { if (active == null) showTypes = true else showFabMenu = !showFabMenu },
                                containerColor = if (showFabMenu) MaterialTheme.colorScheme.inverseSurface
                                    else MaterialTheme.colorScheme.primaryContainer,
                                contentColor = if (showFabMenu) MaterialTheme.colorScheme.inverseOnSurface
                                    else MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Icon(
                                    if (showFabMenu) Icons.Outlined.Close else Icons.Outlined.Add,
                                    if (showFabMenu) tr("Đóng menu tạo mới") else tr("Tạo mới")
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                SideEffect { playerTopPadding = padding.calculateTopPadding() }
                Box(Modifier.fillMaxSize()) {
                    when {
                        appViewerExpanded && viewer != null && playback != null -> FileViewerPage(
                            padding = padding,
                            file = viewer.file,
                            localPath = viewer.localPath,
                            text = viewer.text,
                            loading = viewer.loading,
                            error = viewer.error,
                            saving = viewer.saving,
                            player = playback,
                            swipeQueue = viewer.swipeQueue,
                            swipeIndex = viewer.swipeIndex,
                            previewPaths = viewer.previewPaths,
                            onSwipeTo = swipeViewer,
                            onBack = { if (isMediaPreview(viewer.file)) minimizeViewer() else closeViewer() },
                            onTextChange = updateViewerText,
                            onSaveText = saveViewerText
                        )
                        showSettings -> SettingsPage(padding, themeMode, superDark, setThemeMode, setSuperDark, clearCache)
                        showSystemFiles -> systemFilesState.SaveableStateProvider("system-files") {
                            SystemFilesPage(
                                padding = padding,
                                directory = systemFilesDirectory,
                                revision = systemFilesRevision,
                                onDirectoryChange = { systemFilesDirectory = it },
                                onRefresh = { systemFilesRevision++ },
                                onExit = { showSystemFiles = false },
                                openFile = openLocalFile,
                                uploadLocal = uploadLocalFile,
                                cloudDestination = active?.let { it.title + " / " + model.path.joinToString(" / ") { folder -> folder.name } },
                                handleBack = !viewerExpanded && !drawerState.isOpen && !showAccounts && !showTypes && !addingS3
                            )
                        }
                        active == null -> FileBrowserPage(
                            model = Model(), padding = padding, account = null, shared = false,
                            query = searchQuery, authorize = {}, openFolder = {}, openFile = { _, _ -> }
                        )
                        selected == 3 -> PullToRefreshBox(
                            isRefreshing = model.loading,
                            onRefresh = { if (!model.loading && !accounts.busy) reload() },
                            modifier = Modifier.fillMaxSize().padding(padding)
                        ) {
                            TrashPage(model, PaddingValues(0.dp), restoreFile)
                        }
                        else -> HorizontalPager(
                            state = tabPagerState,
                            modifier = Modifier.fillMaxSize().padding(padding),
                            userScrollEnabled = !appViewerExpanded && !showSettings && !showSystemFiles && !accounts.busy &&
                                !showFabMenu && !showAccounts && !showTypes && !addingS3 && !showCreateFolderDialog &&
                                isTabEnabled(active.type, 1),
                            beyondViewportPageCount = 1,
                            key = { page -> "browser-tab-$page" }
                        ) { page ->
                            val pageModel = if (page == selected) model
                                else browserModels[page] ?: Model(user = model.user, token = model.token)
                            val pageSearch = tabSearchState(page)
                            val initialized = page == selected || browserModels.containsKey(page)

                            PullToRefreshBox(
                                isRefreshing = pageModel.loading,
                                onRefresh = {
                                    if (page == selected && !pageModel.loading && !accounts.busy) reload()
                                },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (!initialized) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                } else {
                                    val globalSearch = pageSearch.results != null && pageSearch.query.isNotBlank()
                                    FileBrowserPage(
                                        model = pageModel,
                                        padding = PaddingValues(0.dp),
                                        account = active,
                                        shared = page == 1,
                                        query = pageSearch.query,
                                        searchResults = pageSearch.results,
                                        searchLoading = pageSearch.loading,
                                        searchError = pageSearch.error,
                                        authorize = if (page == selected) authorize else ({}),
                                        openFolder = { file ->
                                            if (page == selected) {
                                                tabSearchStates[page] = BrowserTabSearchState()
                                                if (globalSearch) openSearchFolder(file) else openFolder(file)
                                            }
                                        },
                                        openFile = { file, queue -> if (page == selected) openFile(file, queue) },
                                        actions = fileActions.copy(
                                            trash = if (page == 0) fileActions.trash else null,
                                            trashMany = if (page == 0) fileActions.trashMany else null
                                        )
                                    )
                                }
                            }
                        }
                    }
                    if (showFabMenu) {
                        Box(
                            Modifier.fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f))
                                .clickable { showFabMenu = false }
                        )
                    }
                }
            }
            // Capture only the narrow left-edge strip above the pager. Normal horizontal
            // drags elsewhere remain owned by HorizontalPager and therefore switch tabs.
            if (!viewerExpanded && drawerState.isClosed && !drawerState.isAnimationRunning &&
                !showFabMenu && !showAccounts && !showTypes && !addingS3 &&
                !showCreateFolderDialog
            ) {
                Box(
                    Modifier.align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(24.dp)
                        .navigationSwipes(enabled = true, allowTabSwipes = false) { gesture ->
                            if (gesture == NavigationSwipe.OPEN_DRAWER) {
                                drawerScope.launch { drawerState.open() }
                            }
                        }
                )
            }

            if (mediaViewer != null && playback != null) {
                // Independent media overlay with its own header; browser chrome is never reused.
                ExpandableMediaPlayer(
                    player = playback,
                    minimized = mediaViewer.minimized,
                    topPadding = playerTopPadding,
                    miniBounds = miniBounds,
                    onMinimize = minimizeViewer,
                    onExpand = expandViewer,
                    onClose = closeViewer,
                    queue = mediaViewer.swipeQueue,
                    index = mediaViewer.swipeIndex,
                    previewPaths = mediaViewer.previewPaths,
                    error = mediaViewer.error,
                    onSwipeTo = swipeViewer
                )
            }
        }
    }

    if (showCreateFolderDialog) AlertDialog(
        onDismissRequest = { showCreateFolderDialog = false },
        title = { Text(tr("Thư mục mới")) },
        text = {
            OutlinedTextField(
                value = newFolderName,
                onValueChange = { newFolderName = it },
                label = { Text(tr("Tên thư mục")) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                enabled = newFolderName.isNotBlank(),
                onClick = {
                    val name = newFolderName.trim()
                    showCreateFolderDialog = false
                    newFolderName = ""
                    createFolder(name)
                }
            ) { Text(tr("Tạo")) }
        },
        dismissButton = {
            TextButton(onClick = { showCreateFolderDialog = false }) { Text(tr("Hủy")) }
        }
    )

    if (showAccounts) AccountSwitcherDialog(
        accounts = accounts,
        active = active,
        onDismiss = { showAccounts = false },
        onSelect = { entry ->
            showAccounts = false
            showSystemFiles = false
            selectAccount(entry)
        },
        onRemove = { entry -> removing = entry },
        onAdd = {
            showAccounts = false
            showTypes = true
        }
    )
    if (showTypes) AlertDialog(
        onDismissRequest = { showTypes = false },
        title = { Text(tr("Thêm tài khoản")) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountTypeButton(Icons.Outlined.AccountCircle, "Google", tr("Tài khoản trên thiết bị")) {
                    showTypes = false; showAccounts = true; signIn()
                }
                AccountTypeButton(Icons.Outlined.Cloud, "S3", tr("Nhập thông tin kết nối")) {
                    showTypes = false; addingS3 = true
                }
                AccountTypeButton(Icons.Outlined.Key, "Service Account", tr("Nhập file JSON")) {
                    showTypes = false; showAccounts = true; importService()
                }
            }
        },
        confirmButton = { TextButton(onClick = { showTypes = false }) { Text(tr("Hủy")) } }
    )
    if (addingS3) S3AccountDialog(onDismiss = { addingS3 = false }, loading = accounts.busy,
        message = accounts.message, connect = connectS3)
    removing?.let { entry ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(tr("Đăng xuất khỏi ${entry.title}?")) },
            text = { Text(tr("Tài khoản sẽ được gỡ khỏi danh sách đã lưu trong ứng dụng. Tệp trên đám mây vẫn được giữ nguyên.")) },
            confirmButton = { TextButton(onClick = { removeAccount(entry); removing = null }) { Text(tr("Đăng xuất")) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(tr("Hủy")) } })
    }
}

@Composable
private fun AccountTypeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(icon, null, modifier = Modifier.padding(12.dp).size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StoragePage(
    model: Model,
    padding: PaddingValues,
    account: AccountEntry?,
    add: () -> Unit,
    accounts: () -> Unit,
    signOut: () -> Unit,
    shared: Boolean = false,
    openFolder: (DriveFile) -> Unit = {},
    goUp: () -> Unit = {},
    openFile: (DriveFile) -> Unit = {}
) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (account == null) item {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Tệp của bạn"), style = MaterialTheme.typography.titleLarge)
                    Text(tr("Kết nối Google, S3 hoặc Service Account để xem tệp."))
                    FilledTonalButton(onClick = add) { Text(tr("Thêm tài khoản")) }
                    TextButton(onClick = accounts) { Text(tr("Tài khoản đã lưu")) }
                }
            }
        }
        if (shared) item { Text(tr("Chia sẻ với tôi"), style = MaterialTheme.typography.titleMedium) }
        model.message?.let { item { CopyableError(it, color = MaterialTheme.colorScheme.error) } }
        if (account != null && !model.loading && model.message == null && model.files.isEmpty()) item { Text(tr("Chưa có tệp để hiển thị.")) }
        items(model.files, key = { it.id }) { FileRow(it, onOpen = openFolder, onPreview = openFile, enabled = !model.loading) }
    }
}

@Composable
private fun DrivePage(
    model: Model,
    padding: PaddingValues,
    signIn: () -> Unit,
    authorize: () -> Unit,
    signOut: () -> Unit,
    upload: () -> Unit,
    createFolder: (String) -> Unit,
    trash: (DriveFile) -> Unit,
    openFolder: (DriveFile) -> Unit = {},
    goUp: () -> Unit = {},
    openFile: (DriveFile) -> Unit = {}
) {
    var folderName by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(model.user?.let { "$it’s Drive" } ?: tr("Lưu trữ cùng Google Drive"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(if (model.user == null) tr("Thêm tài khoản để quản lý tệp.") else tr("Bấm biểu tượng tài khoản để chuyển hoặc thêm tài khoản."))
                    Spacer(Modifier.height(12.dp))
                    if (model.user == null) FilledTonalButton(onClick = signIn) { Text(tr("Thêm tài khoản")) }
                    else if (model.token == null) FilledTonalButton(onClick = authorize) { Text(tr("Cho phép Drive")) }
                    else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = upload) {
                                Icon(
                                    Icons.Outlined.UploadFile,
                                    null
                                ); Spacer(Modifier.width(6.dp)); Text(tr("Tải tệp lên"))
                            }
                            FilledTonalButton(onClick = signOut) { Text(tr("Đăng xuất")) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                            OutlinedTextField(
                                folderName,
                                { folderName = it },
                                label = { Text(tr("Thư mục mới")) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            FilledTonalButton(
                                onClick = { createFolder(folderName); folderName = "" },
                                enabled = folderName.isNotBlank(),
                                modifier = Modifier.padding(start = 8.dp)
                            ) { Text(tr("Tạo")) }
                        }
                    }
                }
            }
        }
        model.message?.let { item { CopyableError(it, color = MaterialTheme.colorScheme.error) } }
        if (!model.loading && model.token != null && model.files.isEmpty()) item { Text(tr("Không có tệp trong vị trí này.")) }
        items(model.files, key = { it.id }) { FileRow(it, trash, openFolder, !model.loading, onPreview = openFile) }
    }
}

@Composable
private fun TrashPage(
    model: Model,
    padding: PaddingValues,
    restore: (DriveFile) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        model.message?.let { item { CopyableError(it, color = MaterialTheme.colorScheme.error) } }
        if (!model.loading && model.message == null && model.files.isEmpty()) item { Text(tr("Thùng rác đang trống.")) }
        items(model.files, key = { it.id }) { file ->
            FileRow(file = file, enabled = false, onRestore = restore)
        }
    }
}

@Composable
private fun S3AccountDialog(
    onDismiss: () -> Unit,
    loading: Boolean = false,
    message: String? = null,
    connect: (String, S3Config) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("us-east-1") }
    val validEndpoint = runCatching {
        val uri = java.net.URI(endpoint.trim())
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
    }.getOrDefault(false)
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(tr("Thêm tài khoản S3")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message?.let { CopyableError(it, color = MaterialTheme.colorScheme.error) }
                if (loading) Loading()
                OutlinedTextField(name, { name = it }, label = { Text(tr("Tên tài khoản")) }, enabled = !loading, singleLine = true)
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Endpoint HTTPS") }, enabled = !loading, singleLine = true)
                OutlinedTextField(bucket, { bucket = it }, label = { Text("Bucket") }, enabled = !loading, singleLine = true)
                OutlinedTextField(region, { region = it }, label = { Text("Region") }, enabled = !loading, singleLine = true)
                OutlinedTextField(key, { key = it }, label = { Text("Access key") }, enabled = !loading, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(secret, { secret = it }, label = { Text("Secret key") }, enabled = !loading, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && name.isNotBlank() && validEndpoint && key.isNotBlank() && secret.isNotBlank() && bucket.isNotBlank(),
                onClick = { connect(name.trim(), S3Config(endpoint.trim(), key.trim(), secret, bucket.trim(), region.trim().ifBlank { "us-east-1" })) }
            ) { Text(tr("Kết nối và lưu")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text(tr("Hủy")) } }
    )
}

@Composable
private fun FileRow(file: DriveFile, onDelete: ((DriveFile) -> Unit)? = null,
    onOpen: (DriveFile) -> Unit = {}, enabled: Boolean = true,
    onRestore: ((DriveFile) -> Unit)? = null, onPreview: (DriveFile) -> Unit = {}) =
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) {
        if (file.isFolder) onOpen(file) else onPreview(file)
    }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (file.isFolder) Icons.Outlined.Folder else Icons.Outlined.Description,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        ); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name); Text(
            file.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        }
        onDelete?.let { IconButton(onClick = { it(file) }) { Icon(Icons.Outlined.Delete, tr("Chuyển vào thùng rác")) } }
        onRestore?.let { IconButton(onClick = { it(file) }) { Icon(Icons.Outlined.Restore, tr("Khôi phục")) } }
    }

@Composable
private fun Loading() =
    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

// Keep previews alongside the private UI components so they need no Activity or services.
@Preview(name = "Sáng", group = "ManyDrive", showBackground = true, widthDp = 400, heightDp = 850)
@Preview(name = "Tối", group = "ManyDrive", showBackground = true, widthDp = 400, heightDp = 850,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
private annotation class ScreenPreviews

@Preview(name = "Sáng", group = "Components", showBackground = true, widthDp = 400)
@Preview(name = "Tối", group = "Components", showBackground = true, widthDp = 400,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
private annotation class ComponentPreviews

private val previewFiles = listOf(
    DriveFile("folder", tr("Tài liệu công việc"), "application/vnd.google-apps.folder", null),
    DriveFile("pdf", tr("Kế hoạch dự án.pdf"), "application/pdf", "2026-09-08T09:30:00.000Z"),
    DriveFile("image", tr("Ảnh chuyến đi cuối tuần.jpg"), "image/jpeg", "2026-09-07T14:15:00.000Z")
)

private val previewModel = Model(
    user = "Minh Anh",
    token = "preview-token",
    files = previewFiles,

)

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    ManyDriveTheme {
        Surface { content() }
    }
}

@ScreenPreviews
@Composable
private fun AppPreview() {
    var selected by remember { mutableIntStateOf(0) }
    PreviewSurface {
        App(
            model = previewModel, selected = selected, select = { selected = it }, reload = {},
            signIn = {}, authorize = {}, connectS3 = { _, _ -> }, signOut = {}, upload = {},
            createFolder = {}, trash = {},
            accounts = AccountUi(
                entries = listOf(AccountEntry(AccountType.GOOGLE, "minhanh@example.com", "Minh Anh"),
                    AccountEntry(AccountType.S3, "preview-s3", tr("Kho công việc"), "documents"),
                    AccountEntry(AccountType.SERVICE, "drive@example.iam.gserviceaccount.com", "drive@example.iam.gserviceaccount.com")),
                active = AccountEntry(AccountType.GOOGLE, "minhanh@example.com", "Minh Anh")
            )
        )
    }
}

@Composable
private fun DrivePagePreviewContent(model: Model) {
    PreviewSurface {
        DrivePage(
            model = model, padding = PaddingValues(0.dp), signIn = {}, authorize = {},
            signOut = {}, upload = {}, createFolder = {}, trash = {}
        )
    }
}

@ScreenPreviews
@Composable
private fun DrivePagePreview() = DrivePagePreviewContent(previewModel)

@ScreenPreviews
@Composable
private fun DriveSignedOutPreview() = DrivePagePreviewContent(Model())

@ScreenPreviews
@Composable
private fun DriveAuthorizationPreview() = DrivePagePreviewContent(previewModel.copy(token = null, files = emptyList()))

@ScreenPreviews
@Composable
private fun DriveEmptyPreview() = DrivePagePreviewContent(previewModel.copy(files = emptyList()))

@ScreenPreviews
@Composable
private fun DriveLoadingPreview() = DrivePagePreviewContent(previewModel.copy(loading = true, files = emptyList()))

@ScreenPreviews
@Composable
private fun DriveErrorPreview() = DrivePagePreviewContent(
    previewModel.copy(message = tr("Không thể tải danh sách tệp. Vui lòng thử lại."), files = emptyList())
)

@ScreenPreviews
@Composable
private fun S3PagePreview() {
    PreviewSurface {
        StoragePage(Model(files = previewFiles), PaddingValues(0.dp),
            AccountEntry(AccountType.S3, "s3-preview", tr("Kho công việc"), "documents"), {}, {}, {})
    }
}

@ScreenPreviews
@Composable
private fun ServiceAccountPagePreview() {
    PreviewSurface {
        StoragePage(Model(files = previewFiles), PaddingValues(0.dp),
            AccountEntry(AccountType.SERVICE, "drive@example.iam.gserviceaccount.com", "drive@example.iam.gserviceaccount.com"), {}, {}, {})
    }
}

@ScreenPreviews
@Composable
private fun AccountsEmptyPreview() {
    PreviewSurface {
        Column {
            DriveRootTopBar("", {}, {}, {}, null)
            FileBrowserPage(Model(), PaddingValues(0.dp), null, false, "", authorize = {}, openFolder = {}, openFile = { _, _ -> })
        }
    }
}

@ComponentPreviews
@Composable
private fun FileRowPreview() {
    PreviewSurface { FileRow(previewFiles[1], onDelete = {}) }
}

@ComponentPreviews
@Composable
private fun FolderRowPreview() {
    PreviewSurface { FileRow(previewFiles[0]) }
}

@ComponentPreviews
@Composable
private fun LoadingPreview() {
    PreviewSurface { Loading() }
}

@ScreenPreviews
@Composable
private fun S3AddAccountPreview() {
    PreviewSurface { S3AccountDialog(onDismiss = {}, connect = { _, _ -> }) }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun AccountTypeButtonsPreview() {
    PreviewSurface {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AccountTypeButton(Icons.Outlined.AccountCircle, "Google", tr("Tài khoản trên thiết bị")) {}
            AccountTypeButton(Icons.Outlined.Cloud, "S3", tr("Nhập thông tin kết nối")) {}
            AccountTypeButton(Icons.Outlined.Key, "Service Account", tr("Nhập file JSON")) {}
        }
    }
}
