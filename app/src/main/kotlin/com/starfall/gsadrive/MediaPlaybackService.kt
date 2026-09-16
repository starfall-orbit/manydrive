package com.starfall.gsadrive

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaConstants
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.media3.session.MediaSessionService
import androidx.core.graphics.drawable.IconCompat
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.starfall.gsadrive.data.DriveApi
import com.starfall.gsadrive.data.DriveFile
import com.starfall.gsadrive.data.S3Api
import com.starfall.gsadrive.data.S3Config
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/** Information needed to open a cloud media item lazily. */
data class PlaybackSource(
    val mediaId: String,
    val file: DriveFile,
    val accountType: String,
    val accessToken: String?,
    val s3Config: S3Config?,
    val cacheFile: File
) {
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(Uri.Builder().scheme("manydrive").authority("media").appendQueryParameter("id", mediaId).build())
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(file.name).apply {
                if (!file.thumbnailUrl.isNullOrBlank()) {
                    // Keep auth/source details inside the app. MediaSession resolves this URI with
                    // its BitmapLoader and publishes the decoded artwork to platform media controls.
                    setArtworkUri(
                        Uri.Builder().scheme("manydrive-artwork").authority("thumbnail")
                            .appendQueryParameter("id", mediaId).build()
                    )
                }
            }.build()
        )
        .build()
}

/**
 * In-process registry used by the playback service. Media items are added to the ExoPlayer playlist
 * immediately; S3 opens through a fresh presigned URL, while Drive uses the local viewer cache.
 */
object PlaybackSourceRegistry {
    private val sources = ConcurrentHashMap<String, PlaybackSource>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val orderedIds = CopyOnWriteArrayList<String>()

    fun replace(items: List<PlaybackSource>) {
        sources.clear()
        orderedIds.clear()
        items.forEach {
            sources[it.mediaId] = it
            orderedIds += it.mediaId
        }
    }

    fun clear() {
        sources.clear()
        locks.clear()
        orderedIds.clear()
    }

    fun get(mediaId: String): PlaybackSource? = sources[mediaId]

    fun all(): List<PlaybackSource> = orderedIds.mapNotNull(sources::get)

    @Throws(IOException::class)
    fun resolve(mediaId: String): File {
        val source = sources[mediaId] ?: throw IOException(tr("Không tìm thấy nguồn media: $mediaId"))
        if (source.accountType == "LOCAL") return source.cacheFile
        if (source.accountType == "S3") throw IOException("S3 media must use presigned streaming")
        if (source.cacheFile.isFile && source.cacheFile.length() > 0L) return source.cacheFile

        val lock = locks.getOrPut(mediaId) { Any() }
        synchronized(lock) {
            if (source.cacheFile.isFile && source.cacheFile.length() > 0L) return source.cacheFile
            val target = source.cacheFile
            val temporary = File(target.path + ".tmp")
            target.parentFile?.mkdirs()
            temporary.delete()
            try {
                when (source.accountType) {
                    "GOOGLE", "SERVICE" -> DriveApi.downloadTo(
                        source.accessToken ?: throw IOException(tr("Thiếu quyền truy cập media.")),
                        source.file.id,
                        temporary
                    )
                    else -> throw IOException(tr("Loại tài khoản không hỗ trợ phát media."))
                }
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                return target
            } catch (t: Throwable) {
                temporary.delete()
                if (t is IOException) throw t
                throw IOException(t.message ?: tr("Không thể tải media."), t)
            }
        }
    }
}

/** Resolve on every open/reopen so seeking never reuses an expired S3 URL. */
@OptIn(UnstableApi::class)
internal fun resolvePlaybackDataSpec(dataSpec: DataSpec): DataSpec {
    if (dataSpec.uri.scheme != "manydrive") return dataSpec
    val mediaId = dataSpec.uri.getQueryParameter("id")
        ?: throw IOException(tr("Media URI không hợp lệ."))
    val source = PlaybackSourceRegistry.get(mediaId)
        ?: throw IOException(tr("Không tìm thấy nguồn media: $mediaId"))
    if (source.accountType == "S3") {
        val remote = try {
            runBlocking {
                S3Api.downloadSource(
                    source.s3Config ?: throw IOException(tr("Thiếu cấu hình S3 cho media.")),
                    source.file.id
                )
            }
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException(tr("Không thể mở luồng media S3."), error)
        }
        // Preserve position and length: Media3 turns them into HTTP Range requests.
        return dataSpec.withUri(Uri.parse(remote.url)).withAdditionalHeaders(remote.headers)
    }
    return dataSpec.withUri(Uri.fromFile(PlaybackSourceRegistry.resolve(mediaId)))
}

/** A page starts paused and muted; the session activates this same player when selected. */
@OptIn(UnstableApi::class)
internal fun createMediaPagePlayer(context: android.content.Context): ExoPlayer = ExoPlayer.Builder(context)
    .setMediaSourceFactory(DefaultMediaSourceFactory(ResolvingDataSource.Factory(DefaultDataSource.Factory(context), ::resolvePlaybackDataSpec)))
    .setSeekBackIncrementMs(10_000L)
    .setSeekForwardIncrementMs(10_000L)
    .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
        .setBufferDurationsMs(1000, 5000, 250, 500).build())
    .build().apply { volume = 0f; playWhenReady = false }

/** Only the selected media survives process death; other positions belong to this session. */
internal class PlaybackProgressStore(private val preferences: android.content.SharedPreferences) {
    private val positions = mutableMapOf<String, Long>()
    private var activeId: String? = preferences.getString("active_id", null)

    init {
        activeId?.let { positions[it] = preferences.getLong("active_position", 0L).coerceAtLeast(0L) }
        // Discard legacy per-media history, whose active item cannot be determined.
        persist()
    }

    fun read(id: String): Long = positions[id] ?: 0L

    fun save(id: String?, position: Long) {
        if (id.isNullOrBlank()) return
        positions[id] = position.coerceAtLeast(0L)
        if (id == activeId) persist()
    }

    fun activate(id: String?, position: Long = 0L) {
        activeId = id?.takeIf { it.isNotBlank() }
        activeId?.let { positions[it] = position.coerceAtLeast(0L) }
        persist()
    }

    fun retainActive() {
        positions.keys.retainAll(setOfNotNull(activeId))
    }

    fun clear() {
        activeId = null
        positions.clear()
        persist()
    }

    private fun persist() {
        preferences.edit().clear().apply {
            activeId?.let { putString("active_id", it); putLong("active_position", read(it)) }
        }.apply()
    }
}

internal object PlaybackProgress {
    private var store: PlaybackProgressStore? = null
    private fun store(context: Context): PlaybackProgressStore = store ?: PlaybackProgressStore(
        context.applicationContext.getSharedPreferences("playback_progress", Context.MODE_PRIVATE)
    ).also { store = it }

    fun read(context: Context, id: String): Long = store(context).read(id)
    fun save(context: Context, id: String?, position: Long) = store(context).save(id, position)
    fun activate(context: Context, id: String?, position: Long = 0L) = store(context).activate(id, position)
    fun retainActive(context: Context) = store(context).retainActive()
    fun clear(context: Context) = store(context).clear()
}

/** Shared with the in-process viewer; playback policy is enforced by the service. */
object PlaybackSettings {
    private val slideshow = MutableStateFlow(true)
    val slideshowEnabled = slideshow.asStateFlow()

    fun setSlideshowEnabled(enabled: Boolean) {
        slideshow.value = enabled
    }
}

@OptIn(UnstableApi::class)
private class ManyDriveArtworkBitmapLoader : BitmapLoader {
    private val executor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2))

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = executor.submit(Callable {
        BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: throw IOException(tr("Không thể giải mã artwork"))
    })

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = executor.submit(Callable {
        if (uri.scheme != "manydrive-artwork") throw IOException(tr("Artwork URI không được hỗ trợ: $uri"))
        val mediaId = uri.getQueryParameter("id") ?: throw IOException(tr("Artwork thiếu media id"))
        val source = PlaybackSourceRegistry.get(mediaId) ?: throw IOException(tr("Không tìm thấy nguồn artwork"))
        val thumbnailUrl = source.file.thumbnailUrl ?: throw IOException(tr("Media không có thumbnail"))
        ThumbnailRepository.load(thumbnailUrl, source.accessToken)
    })

    fun release() { executor.shutdownNow() }
}

@OptIn(UnstableApi::class)
private class FixedTransportNotificationProvider(context: Context) : MediaNotification.Provider {
    private val appContext = context.applicationContext
    private val delegate = DefaultMediaNotificationProvider(appContext).apply {
        setSmallIcon(R.drawable.ic_notification)
    }

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        val base = delegate.createNotification(
            mediaSession,
            mediaButtonPreferences,
            actionFactory,
            onNotificationChangedCallback
        )
        val player = mediaSession.player
        val previous = CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName("Previous")
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val playPause = CommandButton.Builder(
            if (player.playWhenReady && player.playbackState != Player.STATE_ENDED)
                CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY
        )
            .setDisplayName(if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) tr("Tạm dừng") else tr("Phát"))
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setSlots(CommandButton.SLOT_CENTRAL)
            .build()
        val next = CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName("Next")
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()

        fun action(button: CommandButton, enabled: Boolean): Notification.Action {
            val pendingIntent = if (enabled) {
                actionFactory.createMediaAction(
                    mediaSession,
                    IconCompat.createWithResource(appContext, button.iconResId),
                    button.displayName,
                    button.playerCommand
                ).actionIntent
            } else null
            return Notification.Action.Builder(
                Icon.createWithResource(appContext, button.iconResId),
                button.displayName,
                pendingIntent
            ).build()
        }

        val rebuilt = Notification.Builder.recoverBuilder(appContext, base.notification)
            .setActions(
                action(previous, player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)),
                action(playPause, player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)),
                action(next, player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
            )
            .build()
        return MediaNotification(base.notificationId, rebuilt)
    }

    override fun handleCustomCommand(mediaSession: MediaSession, action: String, extras: Bundle): Boolean =
        delegate.handleCustomCommand(mediaSession, action, extras)

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        delegate.notificationChannelInfo
}

@OptIn(UnstableApi::class)
class MediaPlaybackService : MediaSessionService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private lateinit var player: PagedPlaybackPlayer
    private lateinit var artworkBitmapLoader: ManyDriveArtworkBitmapLoader

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(FixedTransportNotificationProvider(this))
        player = PagedPlaybackPlayer(this)
        artworkBitmapLoader = ManyDriveArtworkBitmapLoader()
        MediaPagePlayback.attach(player)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) PlaybackProgress.save(this@MediaPlaybackService, player.currentMediaItem?.mediaId, 0L)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && player.playbackState != Player.STATE_ENDED)
                    PlaybackProgress.save(this@MediaPlaybackService, player.currentMediaItem?.mediaId, player.currentPosition)
            }
        })
        serviceScope.launch {
            while (true) {
                if (player.isPlaying) PlaybackProgress.save(this@MediaPlaybackService, player.currentMediaItem?.mediaId, player.currentPosition)
                kotlinx.coroutines.delay(1000)
            }
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_PLAYER
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, player)
            .setBitmapLoader(artworkBitmapLoader)
            // Let Media3 publish periodic position updates; SystemUI owns the native media progress UI.
            .setPeriodicPositionUpdateEnabled(true)
            .setMediaButtonPreferences(listOf(
                CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                    .setDisplayName("Previous")
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .setSlots(CommandButton.SLOT_BACK)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_NEXT)
                    .setDisplayName("Next")
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .setSlots(CommandButton.SLOT_FORWARD)
                    .build()
            ))
            // Use the platform's native previous/play/next transport actions. Reserve
            // both side slots so an unavailable previous action cannot move next left.
            .setSessionExtras(Bundle().apply {
                putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV, true)
                putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT, true)
            })
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        player.endAppSession()
        // Keep playing if playback is active. The default MediaSessionService behavior then keeps the
        // foreground service and notification alive even after the task is swiped away.
        if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (player.playbackState != Player.STATE_ENDED) PlaybackProgress.save(this, player.currentMediaItem?.mediaId, player.currentPosition)
        serviceScope.cancel()
        MediaPagePlayback.attach(null)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        if (::artworkBitmapLoader.isInitialized) artworkBitmapLoader.release()
        PlaybackSourceRegistry.clear()
        super.onDestroy()
    }
}
