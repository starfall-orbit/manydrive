package com.starfall.gsadrive

import androidx.activity.compose.BackHandler
import com.starfall.gsadrive.ui.CopyableError

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starfall.gsadrive.data.DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone


@Composable
internal fun DriveNavigationDrawer(
    account: AccountEntry?,
    trashSelected: Boolean,
    settingsSelected: Boolean,
    onAccounts: () -> Unit,
    onTrash: () -> Unit,
    onSettings: () -> Unit,
    systemFilesSelected: Boolean = false,
    onSystemFiles: () -> Unit = {}
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val drawerWidth = (screenWidth - 56.dp).coerceAtLeast(240.dp).coerceAtMost(360.dp)
    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight().width(drawerWidth),
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "ManyDrive",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            DriveDrawerItem(
                icon = Icons.Outlined.AccountCircle,
                label = tr("Tài khoản"),
                onClick = onAccounts
            )
            DriveDrawerItem(
                icon = Icons.Outlined.Storage,
                label = tr("Tệp Hệ Thống"),
                selected = systemFilesSelected,
                onClick = onSystemFiles
            )
            DriveDrawerItem(
                icon = Icons.Outlined.Delete,
                label = tr("Thùng rác"),
                selected = trashSelected,
                enabled = isTabEnabled(account?.type, 3),
                onClick = onTrash
            )
            DriveDrawerItem(
                icon = Icons.Outlined.Settings,
                label = tr("Cài đặt"),
                selected = settingsSelected,
                onClick = onSettings
            )

            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (account != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AccountAvatar(account, onClick = onAccounts, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(account.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(account.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun DriveDrawerItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        color = background,
        shape = RoundedCornerShape(28.dp),
        enabled = enabled,
        onClick = onClick
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(26.dp), tint = contentColor)
            Spacer(Modifier.width(24.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = contentColor)
        }
    }
}

private enum class BrowserSort(val label: String) {
    NAME(tr("Tên")), MODIFIED(tr("Ngày sửa đổi")), SHARED(tr("Ngày chia sẻ"))
}

@Composable
internal fun DriveRootTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onMenu: () -> Unit,
    onAccounts: () -> Unit,
    account: AccountEntry?
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, tr("Mở menu")) }
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(tr("Tìm trong Drive")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(32.dp),
                    leadingIcon = if (query.isNotBlank()) ({ Icon(Icons.Outlined.Search, null) }) else null,
                    trailingIcon = if (query.isNotBlank()) ({
                        IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Outlined.Close, tr("Xóa tìm kiếm")) }
                    }) else null,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
                AccountAvatar(account = account, onClick = onAccounts)
            }
        }
    }
}

@Composable
internal fun FolderBrowserTopBar(
    title: String,
    query: String,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onSearchingChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAccounts: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = 64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("Quay lại")) }
            if (searching) {
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(tr("Tìm trong Drive")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
                IconButton(onClick = { onQueryChange(""); onSearchingChange(false) }) {
                    Icon(Icons.Outlined.Close, tr("Đóng tìm kiếm"))
                }
            } else {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onSearchingChange(true) }) { Icon(Icons.Outlined.Search, tr("Tìm kiếm")) }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, tr("Tùy chọn")) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(tr("Làm mới")) }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                            onClick = { menuOpen = false; onRefresh() })
                        DropdownMenuItem(text = { Text(tr("Tài khoản")) }, leadingIcon = { Icon(Icons.Outlined.AccountCircle, null) },
                            onClick = { menuOpen = false; onAccounts() })
                    }
                }
            }
        }
    }
}

@Composable
internal fun AccountAvatar(account: AccountEntry?, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val avatar = rememberRemoteAvatar(account?.avatarUrl)
    Surface(
        modifier = Modifier.size(size).clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        if (avatar != null) {
            Image(bitmap = avatar, contentDescription = tr("Tài khoản"), modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop)
        } else {
            Box(contentAlignment = Alignment.Center) {
                val letter = account?.title?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                Text(letter, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun rememberRemoteAvatar(url: String?): androidx.compose.ui.graphics.ImageBitmap? {
    val state = produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, url) {
        value = if (url.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openConnection().apply { connectTimeout = 5_000; readTimeout = 5_000 }
                    .getInputStream().use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    return state.value
}

@Composable
internal fun FileBrowserPage(
    model: Model,
    padding: PaddingValues,
    account: AccountEntry?,
    shared: Boolean,
    query: String,
    searchResults: List<DriveFile>? = null,
    searchLoading: Boolean = false,
    searchError: String? = null,
    authorize: () -> Unit,
    openFolder: (DriveFile) -> Unit,
    openFile: (DriveFile, List<DriveFile>) -> Unit,
    actions: FileActionCallbacks = FileActionCallbacks(),
    browserKey: String = account?.key ?: "empty",
    showEmptyMessage: Boolean = account != null,
    localMenu: ((DriveFile) -> Unit)? = null
) {
    val scopeKey = "${browserKey}:${if (shared) 1 else 0}:${model.path.lastOrNull()?.id.orEmpty()}"
    val defaultSort = when {
        shared -> BrowserSort.SHARED
        model.path.isNotEmpty() -> BrowserSort.MODIFIED
        else -> BrowserSort.NAME
    }
    val context = LocalContext.current
    val sortPreferences = remember(context) { context.getSharedPreferences("browser_sort", Context.MODE_PRIVATE) }
    val sortPreferenceKey = "sort:$scopeKey"
    val ascendingPreferenceKey = "ascending:$scopeKey"
    var sortName by remember(scopeKey) {
        mutableStateOf(sortPreferences.getString(sortPreferenceKey, defaultSort.name) ?: defaultSort.name)
    }
    var ascending by remember(scopeKey) {
        mutableStateOf(
            if (sortPreferences.contains(ascendingPreferenceKey))
                sortPreferences.getBoolean(ascendingPreferenceKey, defaultSort == BrowserSort.NAME)
            else defaultSort == BrowserSort.NAME
        )
    }
    var grid by rememberSaveable(browserKey) { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var actionFile by remember { mutableStateOf<DriveFile?>(null) }
    var selectedIds by remember(scopeKey) { mutableStateOf<List<String>>(emptyList()) }
    var multiActionFiles by remember { mutableStateOf<List<DriveFile>?>(null) }
    val sort = runCatching { BrowserSort.valueOf(sortName) }.getOrDefault(defaultSort)
    val globalSearch = query.isNotBlank() && searchResults != null
    val visible = remember(model.files, searchResults, query, sort, ascending) {
        val source = searchResults ?: model.files
        val filtered = if (query.isBlank() || searchResults != null) source
            else source.filter { it.name.contains(query, ignoreCase = true) }
        val sorted = when (sort) {
            BrowserSort.NAME -> filtered.sortedWith(compareByDescending<DriveFile> { it.isFolder }.thenBy { it.name.lowercase() })
            BrowserSort.MODIFIED -> filtered.sortedBy { it.modifiedTime.orEmpty() }
            BrowserSort.SHARED -> filtered.sortedBy { it.sharedWithMeTime.orEmpty() }
        }
        if (ascending) sorted else sorted.reversed()
    }
    val selectedSet = selectedIds.toSet()
    val selectedFiles = visible.filter { it.id in selectedSet }
    val selectionMode = selectedIds.isNotEmpty()

    fun toggleSelection(file: DriveFile) {
        selectedIds = if (file.id in selectedSet) selectedIds.filterNot { it == file.id }
            else selectedIds + file.id
    }
    fun enterSelection(file: DriveFile) {
        if (account == null) return
        if (file.id !in selectedSet) selectedIds = selectedIds + file.id
    }
    fun showMenu(file: DriveFile) {
        if (localMenu != null) { localMenu(file); return }
        if (account == null) return
        if (file.id in selectedSet) multiActionFiles = selectedFiles
        else actionFile = file
    }

    BackHandler(enabled = selectionMode) {
        multiActionFiles = null
        selectedIds = emptyList()
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    IconButton(onClick = { selectedIds = emptyList() }) {
                        Icon(Icons.Outlined.Close, tr("Bỏ chọn tất cả"))
                    }
                    Text(
                        tr("${selectedIds.size} đã chọn"),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        enabled = selectedSet.size < visible.size,
                        onClick = { selectedIds = visible.map { it.id } }
                    ) {
                        Icon(Icons.Outlined.SelectAll, tr("Chọn tất cả"))
                    }
                } else {
                    Text(
                        sort.label,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp, end = 2.dp)
                    )
                    Box {
                        val directionDescription = when (sort) {
                            BrowserSort.NAME -> if (ascending) tr("A đến Z") else tr("Z đến A")
                            BrowserSort.MODIFIED, BrowserSort.SHARED ->
                                if (ascending) tr("Từ cũ đến mới") else tr("Từ mới đến cũ")
                        }
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(
                                if (ascending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                                tr("Sắp xếp: $directionDescription")
                            )
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            Text(
                                tr("Sắp xếp theo"),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                            BrowserSort.entries.filter { it != BrowserSort.SHARED || shared }.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    leadingIcon = {
                                        if (sort == option) Icon(Icons.Outlined.Check, null)
                                        else Spacer(Modifier.size(24.dp))
                                    },
                                    onClick = {
                                        sortName = option.name
                                        sortPreferences.edit().putString(sortPreferenceKey, option.name).apply()
                                        sortMenu = false
                                    }
                                )
                            }
                            HorizontalDivider()
                            val directionOptions = if (sort == BrowserSort.NAME) {
                                listOf(false to tr("Z đến A"), true to tr("A đến Z"))
                            } else {
                                listOf(false to tr("Từ mới đến cũ"), true to tr("Từ cũ đến mới"))
                            }
                            directionOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = {
                                        if (ascending == value) Icon(Icons.Outlined.Check, null)
                                        else Spacer(Modifier.size(24.dp))
                                    },
                                    onClick = {
                                        ascending = value
                                        sortPreferences.edit().putBoolean(ascendingPreferenceKey, value).apply()
                                        sortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                if (!selectionMode) Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row {
                        IconButton(onClick = { grid = false }, colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (!grid) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
                            Icon(Icons.Outlined.ViewList, tr("Danh sách"))
                        }
                        IconButton(onClick = { grid = true }, colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (grid) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
                            Icon(Icons.Outlined.GridView, tr("Lưới"))
                        }
                    }
                }
            }
        }

        model.message?.let {
            CopyableError(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        searchError?.let {
            CopyableError(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        if (searchLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        }
        if (account?.type == AccountType.GOOGLE && model.token == null && model.files.isEmpty()) {
            FilledTonalButton(onClick = authorize, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(tr("Cho phép Drive"))
            }
        }

        if (showEmptyMessage && !model.loading && !searchLoading && model.message == null && searchError == null && visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (query.isBlank()) tr("Không có tệp trong vị trí này.") else tr("Không tìm thấy tệp phù hợp."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (grid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visible, key = { it.id }) { file ->
                    FileGridCard(
                        file,
                        shared && !globalSearch,
                        accessToken = model.token,
                        onOpen = openFolder,
                        onPreview = { selected -> openFile(selected, visible.filter(::isSwipePreview)) },
                        selectionMode = selectionMode,
                        selected = file.id in selectedSet,
                        onToggleSelection = ::toggleSelection,
                        onLongSelect = ::enterSelection,
                        onMenu = if (account != null || localMenu != null) ::showMenu else null
                    )
                }
            }
        } else if (shared && !globalSearch) {
            val sections = remember(visible) { visible.groupBy(::sharedSection) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sections.forEach { (section, files) ->
                    item("section:$section") {
                        Text(section, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
                    }
                    items(files, key = { it.id }) { file ->
                        FileListRow(
                            file,
                            shared = true,
                            onOpen = openFolder,
                            onPreview = { selected -> openFile(selected, visible.filter(::isSwipePreview)) },
                            selectionMode = selectionMode,
                            selected = file.id in selectedSet,
                            onToggleSelection = ::toggleSelection,
                            onLongSelect = ::enterSelection,
                            onMenu = if (account != null || localMenu != null) ::showMenu else null
                        )
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(visible, key = { it.id }) { file ->
                    FileListRow(
                        file,
                        shared = false,
                        onOpen = openFolder,
                        onPreview = { selected -> openFile(selected, visible.filter(::isSwipePreview)) },
                        selectionMode = selectionMode,
                        selected = file.id in selectedSet,
                        onToggleSelection = ::toggleSelection,
                        onLongSelect = ::enterSelection,
                        onMenu = if (account != null || localMenu != null) ::showMenu else null
                    )
                }
            }
        }
    }
    actionFile?.takeIf { account != null }?.let { selected ->
        FileActionsSheet(file = selected, account = requireNotNull(account), actions = actions, onDismiss = { actionFile = null })
    }
    multiActionFiles?.takeIf { it.isNotEmpty() && account != null }?.let { selected ->
        MultiFileActionsSheet(
            files = selected,
            account = requireNotNull(account),
            actions = actions,
            onDismiss = { multiActionFiles = null },
            onActionDone = {
                multiActionFiles = null
                selectedIds = emptyList()
            }
        )
    }
}

@Composable
private fun FileListRow(
    file: DriveFile,
    shared: Boolean,
    onOpen: (DriveFile) -> Unit,
    onPreview: (DriveFile) -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: (DriveFile) -> Unit,
    onLongSelect: (DriveFile) -> Unit,
    onMenu: ((DriveFile) -> Unit)?
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().combinedClickable(
                onClick = {
                    if (selectionMode) onToggleSelection(file)
                    else if (file.isFolder) onOpen(file) else onPreview(file)
                },
                onLongClick = { onLongSelect(file) }
            ).padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Icon(
                    if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    if (selected) tr("Đã chọn") else tr("Chưa chọn"),
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.width(10.dp))
            }
            if (shared) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(34.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Person, null, modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    FileTypeIcon(file, Modifier.size(38.dp))
                }
            } else {
                FileTypeIcon(file, Modifier.size(42.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (shared) formatDriveDate(file.sharedWithMeTime ?: file.modifiedTime, tr("Được chia sẻ"))
                        else formatDriveDate(file.modifiedTime, tr("Đã chỉnh sửa")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (onMenu != null) IconButton(onClick = { onMenu(file) }) { Icon(Icons.Outlined.MoreVert, tr("Tùy chọn")) }
        }
    }
}

@Composable
private fun rememberFileThumbnail(file: DriveFile, accessToken: String?): androidx.compose.ui.graphics.ImageBitmap? {
    val url = file.thumbnailUrl ?: return null
    val state = produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = ThumbnailRepository.cached(url)?.asImageBitmap(),
        key1 = url,
        key2 = accessToken
    ) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { ThumbnailRepository.load(url, accessToken).asImageBitmap() }.getOrNull()
            }
        }
    }
    return state.value
}

@Composable
private fun FileGridCard(
    file: DriveFile,
    shared: Boolean,
    accessToken: String?,
    onOpen: (DriveFile) -> Unit,
    onPreview: (DriveFile) -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: (DriveFile) -> Unit,
    onLongSelect: (DriveFile) -> Unit,
    onMenu: ((DriveFile) -> Unit)?
) {
    val thumbnail = rememberFileThumbnail(file, accessToken)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = {
                if (selectionMode) onToggleSelection(file)
                else if (file.isFolder) onOpen(file) else onPreview(file)
            },
            onLongClick = { onLongSelect(file) }
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Icon(
                        if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        if (selected) tr("Đã chọn") else tr("Chưa chọn"),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                FileTypeIcon(file, Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    file.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (onMenu != null) IconButton(onClick = { onMenu(file) }) { Icon(Icons.Outlined.MoreVert, tr("Tùy chọn")) }
            }
            if (thumbnail != null && !file.isFolder) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    if (shared) {
                        Surface(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
                        ) {
                            Icon(
                                Icons.Outlined.Group,
                                contentDescription = tr("Tệp được chia sẻ"),
                                modifier = Modifier.padding(6.dp).size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            } else {
                Text(
                    if (shared) formatDriveDate(file.sharedWithMeTime ?: file.modifiedTime, tr("Được chia sẻ"))
                    else formatDriveDate(file.modifiedTime, tr("Đã chỉnh sửa")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FileTypeIcon(file: DriveFile, modifier: Modifier = Modifier) {
    val icon = when {
        file.isFolder -> Icons.Outlined.Folder
        file.mimeType.startsWith("video/") -> Icons.Outlined.Movie
        file.mimeType.startsWith("audio/") -> Icons.Outlined.AudioFile
        file.mimeType.startsWith("image/") -> Icons.Outlined.Image
        else -> Icons.Outlined.Description
    }
    if (file.isFolder) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(icon, null, modifier = Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(26.dp),
                    tint = if (file.mimeType.startsWith("video/")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatDriveDate(value: String?, prefix: String): String {
    if (value.isNullOrBlank()) return prefix
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)
            ?: SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)
    }.getOrNull() ?: return "$prefix ${value.substringBefore('T')}"
    val now = Calendar.getInstance()
    val date = Calendar.getInstance().apply { time = parsed }
    val pattern = if (now.get(Calendar.YEAR) == date.get(Calendar.YEAR)) "d 'thg' M" else "d 'thg' M, yyyy"
    return "$prefix ${SimpleDateFormat(pattern, Locale("vi", "VN")).format(parsed)}"
}

private fun sharedSection(value: DriveFile): String {
    val raw = value.sharedWithMeTime ?: value.modifiedTime ?: return tr("Cũ hơn")
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(raw)
    }.getOrNull() ?: return tr("Cũ hơn")
    val now = Calendar.getInstance()
    val date = Calendar.getInstance().apply { time = parsed }
    if (now.get(Calendar.YEAR) == date.get(Calendar.YEAR) && now.get(Calendar.MONTH) == date.get(Calendar.MONTH)) return tr("Tháng này")
    val previousMonth = (now.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
    if (previousMonth.get(Calendar.YEAR) == date.get(Calendar.YEAR) && previousMonth.get(Calendar.MONTH) == date.get(Calendar.MONTH)) return tr("Tháng trước")
    if (now.get(Calendar.YEAR) == date.get(Calendar.YEAR)) return tr("Đầu năm nay")
    return tr("Cũ hơn")
}
