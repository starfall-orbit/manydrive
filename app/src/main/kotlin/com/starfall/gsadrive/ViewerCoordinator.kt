package com.starfall.gsadrive

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.starfall.gsadrive.data.DriveApi
import com.starfall.gsadrive.data.DriveFile
import com.starfall.gsadrive.data.FileListCache
import com.starfall.gsadrive.data.S3Api
import com.starfall.gsadrive.data.S3Config
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns viewer state and file-preview/media-playback orchestration.
 * MainActivity only supplies platform/account state and a MediaController connection.
 */
internal class ViewerCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listCache: FileListCache,
    private val activeAccount: () -> AccountEntry?,
    private val accessToken: () -> String?,
    private val s3Config: (AccountEntry) -> S3Config?,
    private val currentFiles: () -> List<DriveFile>,
    private val player: () -> Player?,
    private val awaitPlayer: suspend () -> Player,
    private val requestNotificationPermission: () -> Unit,
    private val requestAccessToken: () -> Unit
) {
    var state by mutableStateOf<ViewerState?>(null)
        private set

    private var localPreview = false
    private var generation = 0
    private var awaitingTokenFor: String? = null
    private var mediaPrefetchJob: Job? = null
    private var requestedPrefetchAfterMediaId: String? = null

    fun onAccessTokenAvailable() {
        val expectedAccount = awaitingTokenFor ?: return
        if (activeAccount()?.key != expectedAccount || accessToken() == null) return
        awaitingTokenFor = null
        val pending = state ?: return
        open(pending.file, pending.minimized, pending.swipeQueue)
    }

    fun onAccessTokenError(message: String) {
        if (awaitingTokenFor == null) return
        awaitingTokenFor = null
        state = state?.copy(loading = false, error = message)
    }

    fun syncToMediaItem(mediaItem: MediaItem) {
        val source = PlaybackSourceRegistry.get(mediaItem.mediaId) ?: return
        localPreview = source.accountType == "LOCAL"
        val previous = state
        val queue = previous?.swipeQueue?.takeIf { items -> items.any { it.id == source.file.id } }
            ?: currentFiles().filter(::isSwipePreview)
        val index = queue.indexOfFirst { it.id == source.file.id }
        state = ViewerState(
            file = source.file,
            localPath = source.cacheFile.path,
            loading = false,
            minimized = previous?.minimized ?: true,
            swipeQueue = queue,
            swipeIndex = index,
            previewPaths = previous?.previewPaths.orEmpty()
        )
        if (!localPreview) activeAccount()?.let { prefetchAdjacentImages(it, queue, index) }
        prefetchNextMedia(source.mediaId)
    }

    fun open(file: DriveFile, minimized: Boolean = false, swipeQueue: List<DriveFile>? = null) {
        if (file.isFolder) return
        if (!isPreviewable(file)) {
            val type = file.mimeType.ifBlank { tr("này") }
            Toast.makeText(context, tr("Chưa hỗ trợ xem loại tệp $type"), Toast.LENGTH_SHORT).show()
            return
        }
        val account = activeAccount() ?: return
        localPreview = false
        awaitingTokenFor = null
        val request = ++generation
        val browsingQueue = if (isSwipePreview(file)) {
            (swipeQueue ?: currentFiles().filter(::isSwipePreview)).ifEmpty { listOf(file) }
        } else emptyList()
        val browsingIndex = browsingQueue.indexOfFirst { it.id == file.id }

        if (account.type != AccountType.S3 && accessToken() == null) {
            awaitingTokenFor = account.key
            state = ViewerState(
                file = file, loading = true, minimized = minimized,
                swipeQueue = browsingQueue, swipeIndex = browsingIndex
            )
            requestAccessToken()
            return
        }

        if (isMediaPreview(file)) {
            requestNotificationPermission()
            openMedia(request, account, file, minimized, browsingQueue)
            return
        }

        if (file.mimeType.startsWith("image/") && browsingQueue.any { queued ->
                PlaybackSourceRegistry.all().any { it.file.id == queued.id }
            }) {
            // Images share the same pager. Keep its media pages and session queue alive, paused.
            player()?.pause()
        } else {
            player()?.stop()
            player()?.clearMediaItems()
            PlaybackSourceRegistry.clear()
        }
        state = ViewerState(
            file = file,
            loading = true,
            minimized = minimized,
            swipeQueue = browsingQueue,
            swipeIndex = browsingIndex,
            previewPaths = cachedImagePaths(account.key, browsingQueue)
        )
        prefetchAdjacentImages(account, browsingQueue, browsingIndex)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val target = previewCacheFile(account.key, file)
                    if (!target.isFile || target.length() == 0L) {
                        downloadPreview(account, file, target)
                        prunePreviewCache()
                    }
                    val text = if (isTextPreview(file)) {
                        require(target.length() <= MAX_TEXT_PREVIEW_BYTES) {
                            tr("Tệp text quá lớn để sửa trực tiếp (giới hạn 4 MB).")
                        }
                        target.readText(Charsets.UTF_8)
                    } else null
                    target.path to text
                }
            }.onSuccess { (path, text) ->
                if (request == generation && state?.file?.id == file.id) {
                    state = state?.copy(
                        localPath = path,
                        text = text,
                        loading = false,
                        error = null,
                        previewPaths = if (file.mimeType.startsWith("image/"))
                            state?.previewPaths.orEmpty() + (file.id to path) else state?.previewPaths.orEmpty()
                    )
                }
            }.onFailure {
                if (request == generation && state?.file?.id == file.id) {
                    state = state?.copy(loading = false, error = it.message ?: tr("Không thể mở tệp."))
                }
            }
        }
    }

    fun openLocal(file: DriveFile, queue: List<DriveFile> = listOf(file), minimized: Boolean = false) {
        if (file.isFolder) return
        if (!isPreviewable(file)) {
            Toast.makeText(context, tr("Không thể xem loại tệp này."), Toast.LENGTH_SHORT).show()
            return
        }
        localPreview = true
        awaitingTokenFor = null
        val request = ++generation
        mediaPrefetchJob?.cancel()
        mediaPrefetchJob = null
        requestedPrefetchAfterMediaId = null
        val browsingQueue = if (isSwipePreview(file)) queue.filter(::isSwipePreview).ifEmpty { listOf(file) } else emptyList()
        val images = browsingQueue.filter { it.mimeType.startsWith("image/") }.associate { it.id to it.id }
        state = ViewerState(file = file, localPath = file.id, loading = true, minimized = minimized,
            swipeQueue = browsingQueue, swipeIndex = browsingQueue.indexOfFirst { it.id == file.id }, previewPaths = images)
        if (isMediaPreview(file)) {
            requestNotificationPermission()
            val sources = browsingQueue.filter(::isMediaPreview).map {
                PlaybackSource("LOCAL:${it.id}", it, "LOCAL", null, null, File(it.id))
            }
            PlaybackSourceRegistry.replace(sources)
            scope.launch {
                val result = runCatching {
                    val controller = player() ?: awaitPlayer()
                    if (request != generation) return@launch
                    val index = sources.indexOfFirst { it.file.id == file.id }
                    val id = sources[index].mediaId
                    val position = if (controller.currentMediaItem?.mediaId == id) controller.currentPosition
                        else PlaybackProgress.read(context, id)
                    controller.setMediaItems(sources.map(PlaybackSource::toMediaItem), index, position)
                    controller.prepare()
                    controller.play()
                }
                if (request == generation) state = state?.copy(loading = false, error = result.exceptionOrNull()?.message)
            }
        } else {
            val sharesMediaQueue = file.mimeType.startsWith("image/") &&
                PlaybackSourceRegistry.all().any { source ->
                    source.accountType == "LOCAL" && browsingQueue.any { it.id == source.file.id }
                }
            if (sharesMediaQueue) player()?.pause()
            else {
                player()?.stop()
                player()?.clearMediaItems()
                PlaybackSourceRegistry.clear()
            }
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val local = File(file.id)
                        check(local.isFile && local.canRead()) { tr("Không thể đọc tệp này.") }
                        if (isTextPreview(file)) {
                            val bytes = local.inputStream().use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (output.size() <= MAX_TEXT_PREVIEW_BYTES) {
                                    val count = input.read(buffer, 0, minOf(buffer.size,
                                        MAX_TEXT_PREVIEW_BYTES.toInt() + 1 - output.size()))
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                            check(bytes.size <= MAX_TEXT_PREVIEW_BYTES) { tr("Nội dung vượt giới hạn 4 MB.") }
                            bytes.toString(Charsets.UTF_8)
                        } else null
                    }
                }
                if (request == generation) state = state?.copy(loading = false,
                    text = result.getOrNull(), error = result.exceptionOrNull()?.message)
            }
        }
    }

    private fun openMedia(
        request: Int,
        account: AccountEntry,
        file: DriveFile,
        minimized: Boolean,
        browsingQueue: List<DriveFile>
    ) {
        val mediaQueue = browsingQueue.filter(::isMediaPreview)
        val mediaIndex = mediaQueue.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
        val browsingIndex = browsingQueue.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
        val config = if (account.type == AccountType.S3) s3Config(account) else null
        val token = if (account.type == AccountType.S3) null else accessToken()
        if (account.type != AccountType.S3 && token == null) {
            state = ViewerState(file = file, error = tr("Cần cấp quyền truy cập trước khi phát media."))
            return
        }
        if (account.type == AccountType.S3 && config == null) {
            state = ViewerState(file = file, error = tr("Không tìm thấy cấu hình S3."))
            return
        }

        val sources = mediaQueue.map { item ->
            PlaybackSource(
                mediaId = "${account.key}:${item.id}",
                file = item,
                accountType = account.type.name,
                accessToken = token,
                s3Config = config,
                cacheFile = previewCacheFile(account.key, item)
            )
        }
        PlaybackSourceRegistry.replace(sources)
        val selectedSource = sources.getOrNull(mediaIndex) ?: return
        prefetchNextMedia(selectedSource.mediaId)
        state = ViewerState(
            file = file,
            localPath = selectedSource.cacheFile.path,
            loading = false,
            minimized = minimized,
            swipeQueue = browsingQueue,
            swipeIndex = browsingIndex,
            previewPaths = cachedImagePaths(account.key, browsingQueue)
        )
        prefetchAdjacentImages(account, browsingQueue, browsingIndex)

        scope.launch {
            runCatching {
                val controller = player() ?: awaitPlayer()
                val items = sources.map(PlaybackSource::toMediaItem)
                controller to items
            }.onSuccess { (controller, items) ->
                if (request == generation && state?.file?.id == file.id) {
                    val existingIndex = (0 until controller.mediaItemCount).firstOrNull {
                        controller.getMediaItemAt(it).mediaId == items[mediaIndex].mediaId
                    }
                    if (existingIndex != null && controller.mediaItemCount == items.size &&
                        items.indices.all { controller.getMediaItemAt(it).mediaId == items[it].mediaId })
                        controller.seekTo(existingIndex, if (controller.currentMediaItem?.mediaId == items[mediaIndex].mediaId)
                            controller.currentPosition else PlaybackProgress.read(context, items[mediaIndex].mediaId))
                    else controller.setMediaItems(items, mediaIndex, PlaybackProgress.read(context, items[mediaIndex].mediaId))
                    controller.prepare()
                    controller.play()
                }
            }.onFailure {
                if (request == generation && state?.file?.id == file.id) {
                    state = state?.copy(error = it.message ?: tr("Không thể mở media."))
                }
            }
        }
    }

    fun swipeTo(index: Int) {
        val current = state ?: return
        if (current.saving) return
        val queue = current.swipeQueue
        val target = queue.getOrNull(index) ?: return
        if (target.id == current.file.id) return
        val controller = player()
        val source = PlaybackSourceRegistry.all().firstOrNull { it.file.id == target.id }
        val mediaIndex = if (controller != null && source != null)
            (0 until controller.mediaItemCount).firstOrNull { controller.getMediaItemAt(it).mediaId == source.mediaId }
        else null
        if (isMediaPreview(target) && controller != null && source != null && mediaIndex != null) {
            // Select the existing playlist item, preserving its page and queue.
            PlaybackProgress.save(context, controller.currentMediaItem?.mediaId, controller.currentPosition)
            controller.seekTo(mediaIndex, PlaybackProgress.read(context, source.mediaId))
            controller.play()
            syncToMediaItem(controller.getMediaItemAt(mediaIndex))
        } else {
            if (localPreview) openLocal(target, queue, current.minimized)
            else open(target, minimized = current.minimized, swipeQueue = queue)
        }
    }

    fun close() {
        awaitingTokenFor = null
        ++generation
        player()?.stop()
        player()?.clearMediaItems()
        PlaybackProgress.clear(context)
        requestedPrefetchAfterMediaId = null
        mediaPrefetchJob?.cancel()
        mediaPrefetchJob = null
        PlaybackSourceRegistry.clear()
        state = null
    }

    fun minimize() {
        val current = state ?: return
        if (isMediaPreview(current.file)) state = current.copy(minimized = true) else close()
    }

    fun expand() {
        state = state?.copy(minimized = false)
    }

    fun updateText(text: String) {
        val current = state ?: return
        if (current.text != null && !current.saving) state = current.copy(text = text)
    }

    fun saveText() {
        val current = state ?: return
        val text = current.text ?: return
        if (current.saving) return
        val local = localPreview
        val account = activeAccount()
        if (!local && account == null) return
        val content = text.toByteArray(Charsets.UTF_8)
        if (content.size > MAX_TEXT_PREVIEW_BYTES) {
            Toast.makeText(context, tr("Nội dung vượt giới hạn 4 MB."), Toast.LENGTH_SHORT).show()
            return
        }
        val request = generation
        state = current.copy(saving = true, error = null)
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (local) {
                        File(current.file.id).writeBytes(content)
                        return@withContext
                    }
                    requireNotNull(account)
                    when (account.type) {
                        AccountType.S3 -> {
                            val config = s3Config(account) ?: error(tr("Không tìm thấy cấu hình S3."))
                            val temporary = File.createTempFile("manydrive-text-", ".tmp", context.cacheDir)
                            try {
                                temporary.writeBytes(content)
                                S3Api.upload(config, current.file.id,
                                    current.file.mimeType.ifBlank { "text/plain" }, temporary)
                            } finally {
                                temporary.delete()
                            }
                        }
                        AccountType.GOOGLE, AccountType.SERVICE -> {
                            val token = accessToken() ?: error(tr("Cần cấp quyền ghi trước khi lưu."))
                            DriveApi.updateContent(token, current.file.id,
                                current.file.mimeType.ifBlank { "text/plain; charset=UTF-8" }, content)
                        }
                    }
                    current.localPath?.let { File(it).writeBytes(content) }
                    listCache.clear(account.key)
                }
            }
            if (request == generation && state?.file?.id == current.file.id) {
                state = state?.copy(saving = false, error = result.exceptionOrNull()?.message)
                Toast.makeText(context,
                    if (result.isSuccess) tr("Đã lưu thay đổi.") else tr("Không thể lưu thay đổi."),
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun downloadPreview(account: AccountEntry, file: DriveFile, target: File) {
        val temporary = File(target.path + ".tmp")
        temporary.parentFile?.mkdirs()
        temporary.delete()
        when (account.type) {
            AccountType.S3 -> S3Api.downloadTo(
                s3Config(account) ?: error(tr("Không tìm thấy cấu hình S3.")), file.id, temporary)
            AccountType.GOOGLE, AccountType.SERVICE -> DriveApi.downloadTo(
                accessToken() ?: error(tr("Cần cấp quyền truy cập trước khi mở tệp.")), file.id, temporary)
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    /**
     * Materialize only the next media item into the shared viewer cache ahead of playback.
     * Page players share the same per-item download lock and cache with this IO prefetch.
     * Requests are serialized so fast swipes never start a fan-out of background downloads.
     */
    private fun prefetchNextMedia(currentMediaId: String) {
        requestedPrefetchAfterMediaId = currentMediaId
        if (mediaPrefetchJob?.isActive == true) return
        startNextMediaPrefetch()
    }

    private fun startNextMediaPrefetch() {
        val currentMediaId = requestedPrefetchAfterMediaId ?: return
        requestedPrefetchAfterMediaId = null
        val sources = PlaybackSourceRegistry.all()
        val currentIndex = sources.indexOfFirst { it.mediaId == currentMediaId }
        if (currentIndex < 0) return
        val current = sources[currentIndex]
        val next = sources.getOrNull(currentIndex + 1) ?: return

        // S3 page players buffer their own HTTP streams; never download whole objects for prefetch.
        if (current.accountType in setOf("S3", "LOCAL") || next.accountType in setOf("S3", "LOCAL")) return
        if (next.cacheFile.isFile && next.cacheFile.length() > 0L) return

        val job = scope.launch(Dispatchers.IO) {
            runCatching {
                // Do not compete with startup of the current item. resolve() shares the same per-item
                // lock as ExoPlayer, so this either waits for its download or performs it once.
                PlaybackSourceRegistry.resolve(current.mediaId)
                PlaybackSourceRegistry.resolve(next.mediaId)
            }.onSuccess { prunePreviewCache() }
        }
        mediaPrefetchJob = job
        job.invokeOnCompletion {
            scope.launch {
                if (mediaPrefetchJob === job) mediaPrefetchJob = null
                if (requestedPrefetchAfterMediaId != null) startNextMediaPrefetch()
            }
        }
    }

    private fun cachedImagePaths(accountKey: String, queue: List<DriveFile>): Map<String, String> =
        queue.asSequence().filter { it.mimeType.startsWith("image/") }.mapNotNull { item ->
            previewCacheFile(accountKey, item).takeIf { it.isFile && it.length() > 0L }
                ?.let { item.id to it.path }
        }.toMap()

    private fun prefetchAdjacentImages(account: AccountEntry, queue: List<DriveFile>, index: Int) {
        if (index !in queue.indices) return
        listOf(index - 1, index + 1).mapNotNull(queue::getOrNull)
            .filter { it.mimeType.startsWith("image/") }
            .forEach { item ->
                val target = previewCacheFile(account.key, item)
                if (target.isFile && target.length() > 0L) {
                    state = state?.copy(previewPaths = state?.previewPaths.orEmpty() + (item.id to target.path))
                    return@forEach
                }
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            if (!target.isFile || target.length() == 0L) {
                                downloadPreview(account, item, target)
                                prunePreviewCache()
                            }
                            target.path
                        }
                    }
                    result.getOrNull()?.let { path ->
                        if (state?.swipeQueue?.any { it.id == item.id } == true) {
                            state = state?.copy(previewPaths = state?.previewPaths.orEmpty() + (item.id to path))
                        }
                    }
                }
            }
    }

    private fun previewCacheFile(account: String, file: DriveFile): File {
        val seed = "$account|${file.id}|${file.modifiedTime.orEmpty()}|${file.size ?: -1L}"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }
        val extension = file.name.substringAfterLast('.', "").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }?.let { ".$it" }.orEmpty()
        return File(File(context.cacheDir, "viewer"), hash + extension)
    }

    private fun prunePreviewCache() {
        val directory = File(context.cacheDir, "viewer")
        var total = 0L
        directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedByDescending { it.lastModified() }?.forEach { file ->
                total += file.length()
                if (total > MAX_PREVIEW_CACHE_BYTES) file.delete()
            }
    }
}
