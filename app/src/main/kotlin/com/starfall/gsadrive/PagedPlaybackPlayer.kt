package com.starfall.gsadrive

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The service owns playback; pager pages borrow the actual players, never preview substitutes. */
@OptIn(UnstableApi::class)
internal object MediaPagePlayback {
    private val current = MutableStateFlow<PagedPlaybackPlayer?>(null)
    val owner = current.asStateFlow()
    fun attach(player: PagedPlaybackPlayer?) { current.value = player }
}

/** No decoder or ExoPlayer is allocated while the session has no selected media page. */
@OptIn(UnstableApi::class)
private class EmptyPagePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS
        ).build()).build()
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleStop(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()
}

/**
 * A session playlist backed by one single-item player per visible page. Selecting a track changes
 * the session's delegate, not the MediaItem or video surface belonging to either pager page.
 * Inactive players live only while borrowed by the pager; the active one survives UI disposal.
 */
@OptIn(UnstableApi::class)
internal class PagedPlaybackPlayer(
    private val context: Context,
    private val idlePlayer: Player = EmptyPagePlayer()
) : ForwardingSimpleBasePlayer(idlePlayer) {
    private data class Page(val player: ExoPlayer, var borrowers: Int = 0)
    private val pages = mutableMapOf<String, Page>()
    private var items = emptyList<MediaItem>()
    private var activeId: String? = null
    private val handler = Handler(idlePlayer.applicationLooper)
    private var released = false
    private val revision = MutableStateFlow(0)
    val playlistRevision = revision.asStateFlow()
    private var visibleIds: Set<String>? = null
    internal val residentPlayerCount: Int get() = pages.size

    fun setVisiblePages(ids: Set<String>?) {
        visibleIds = ids
        if (ids != null && activeId != null && activeId !in ids) {
            PlaybackProgress.save(context, activeId, player.currentPosition)
            player.pause()
            activeId = null
            setPlayer(idlePlayer)
            PlaybackProgress.activate(context, null)
        }
        releaseUnused()
    }

    fun acquire(mediaId: String): Player? {
        val index = items.indexOfFirst { it.mediaId == mediaId }
        val item = items.getOrNull(index) ?: return null
        val activeIndex = items.indexOfFirst { it.mediaId == activeId }
        if (visibleIds?.contains(mediaId) == false ||
            (visibleIds == null && kotlin.math.abs(index - activeIndex) > 1)) return null
        return page(item).also { it.borrowers++ }.player
    }

    fun relinquish(mediaId: String, borrowed: Player) {
        val page = pages[mediaId]?.takeIf { it.player === borrowed } ?: return
        page.borrowers--
        releaseUnused()
    }

    private fun page(item: MediaItem): Page {
        pages[item.mediaId]?.let { return it }
        // Lazy composition can retain an outgoing page briefly. Enforce the cap here as well.
        if (pages.size >= 3) {
            val targetIndex = items.indexOfFirst { it.mediaId == item.mediaId }
            val evicted = pages.keys.filter { it != activeId }
                .maxBy { id -> kotlin.math.abs(items.indexOfFirst { it.mediaId == id } - targetIndex) }
            pages.remove(evicted)?.player?.release()
        }
        val delegate = createMediaPagePlayer(context)
        delegate.setMediaItem(item, PlaybackProgress.read(context, item.mediaId))
        delegate.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // Page controls can seek directly, including while playback is paused.
                if (activeId == item.mediaId) {
                    PlaybackProgress.save(context, item.mediaId, newPosition.positionMs)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED || activeId != item.mediaId) return
                PlaybackProgress.save(context, item.mediaId, 0L)
                // Do not change delegates in the middle of ExoPlayer's listener dispatch.
                handler.post {
                    if (!released && player === delegate && delegate.playWhenReady &&
                        delegate.playbackState == Player.STATE_ENDED && PlaybackSettings.slideshowEnabled.value) {
                        val next = items.indexOfFirst { it.mediaId == item.mediaId } + 1
                        if (next in items.indices) select(next, C.TIME_UNSET, play = true)
                    }
                }
            }
        })
        delegate.prepare()
        return Page(delegate).also { pages[item.mediaId] = it }
    }

    private fun select(index: Int, positionMs: Long, play: Boolean) {
        val item = items.getOrNull(index) ?: return
        if (visibleIds?.contains(item.mediaId) == false) visibleIds = null
        val next = page(item).player
        val previous = player
        if (previous !== next) {
            if (previous.playbackState != Player.STATE_ENDED) {
                PlaybackProgress.save(context, activeId, previous.currentPosition)
            }
            val volume = previous.volume.takeIf { activeId != null } ?: 1f
            previous.pause()
            if (previous !== idlePlayer) previous.volume = 0f
            next.volume = volume
            if (activeId != null) {
                next.playbackParameters = previous.playbackParameters
                next.trackSelectionParameters = previous.trackSelectionParameters
            }
            activeId = item.mediaId
            // The page retains this same ExoPlayer and PlayerView when it becomes selected.
            setPlayer(next)
        }
        if (positionMs != C.TIME_UNSET && next.currentPosition != positionMs) next.seekTo(positionMs)
        else if (positionMs == C.TIME_UNSET && next.playbackState == Player.STATE_ENDED) next.seekTo(0L)
        if (next.playbackState == Player.STATE_IDLE) next.prepare()
        PlaybackProgress.activate(context, item.mediaId, next.currentPosition)
        next.playWhenReady = play
        invalidateState()
        releaseUnused()
    }

    override fun getState(): State {
        val state = super.getState()
        val current = items.indexOfFirst { it.mediaId == activeId }
        val commands = state.availableCommands.buildUpon()
            .add(Player.COMMAND_SET_MEDIA_ITEM)
            .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_NEXT)
            .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        if (current > 0) commands.add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        if (current >= 0 && current < items.lastIndex) commands.add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        if (items.isNotEmpty()) commands.add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        val playlist = items.mapIndexed { index, item ->
            MediaItemData.Builder(item.mediaId).setMediaItem(item).apply {
                if (index == current) {
                    val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it >= 0L }
                    setMediaMetadata(
                        player.mediaMetadata.buildUpon().apply {
                            // Android's native media seekbar requires duration metadata in addition
                            // to PlaybackState position/ACTION_SEEK_TO.
                            durationMs?.let(::setDurationMs)
                        }.build()
                    )
                    setTracks(player.currentTracks)
                    setIsSeekable(player.isCurrentMediaItemSeekable)
                    setDurationUs(durationMs?.times(1000L) ?: C.TIME_UNSET)
                }
            }.build()
        }
        return state.buildUpon()
            .setAvailableCommands(commands.build())
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (current < 0) C.INDEX_UNSET else current)
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)
            .build()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val play = player.playWhenReady
        val changed = items != mediaItems
        items = mediaItems.toList()
        if (items.isEmpty()) {
            clearPages()
            PlaybackProgress.clear(context)
        }
        else select(if (startIndex == C.INDEX_UNSET) 0 else startIndex, startPositionMs, play)
        if (changed) revision.value++
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (items.getOrNull(mediaItemIndex)?.mediaId == activeId) {
            player.seekTo(if (positionMs == C.TIME_UNSET) 0L else positionMs)
            PlaybackProgress.save(context, activeId, player.currentPosition)
        } else {
            // Previous/next and swipes all activate the same page-owned player.
            val target = items.getOrNull(mediaItemIndex)
            val resume = if (positionMs == C.TIME_UNSET && target != null)
                pages[target.mediaId]?.player?.currentPosition ?: PlaybackProgress.read(context, target.mediaId)
            else positionMs
            select(mediaItemIndex, resume, player.playWhenReady)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val remaining = items.toMutableList().apply { subList(fromIndex, toIndex).clear() }
        val newIndex = remaining.indexOfFirst { it.mediaId == activeId }.takeIf { it >= 0 }
            ?: fromIndex.coerceAtMost(remaining.lastIndex)
        return handleSetMediaItems(remaining, newIndex, C.TIME_UNSET)
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> =
        updatePlaylist(items.toMutableList().apply { addAll(index, mediaItems) })

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val updated = items.toMutableList()
        val moved = updated.subList(fromIndex, toIndex).toList()
        updated.subList(fromIndex, toIndex).clear()
        updated.addAll(newIndex, moved)
        return updatePlaylist(updated)
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val updated = items.toMutableList()
        updated.subList(fromIndex, toIndex).clear()
        updated.addAll(fromIndex, mediaItems)
        return updatePlaylist(updated)
    }

    private fun updatePlaylist(updated: List<MediaItem>): ListenableFuture<*> =
        handleSetMediaItems(updated, updated.indexOfFirst { it.mediaId == activeId }.coerceAtLeast(0), C.TIME_UNSET)

    private fun releaseUnused() {
        val iterator = pages.iterator()
        while (iterator.hasNext()) {
            val (id, page) = iterator.next()
            val activeIndex = items.indexOfFirst { it.mediaId == activeId }
            val outsideWindow = visibleIds?.let { id !in it }
                ?: (kotlin.math.abs(items.indexOfFirst { it.mediaId == id } - activeIndex) > 1)
            if (id != activeId && (page.borrowers == 0 || outsideWindow)) {
                page.player.release()
                iterator.remove()
            }
        }
    }

    /** A task can exit while its foreground playback service stays alive. */
    fun endAppSession() {
        PlaybackProgress.activate(context, activeId,
            if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition)
        PlaybackProgress.retainActive(context)
        // Resident neighbor players must not resurrect positions from the previous app session.
        pages.forEach { (id, page) -> if (id != activeId) page.player.seekTo(0L) }
    }

    private fun clearPages() {
        player.pause()
        activeId = null
        setPlayer(idlePlayer)
        pages.values.forEach { it.player.release() }
        pages.clear()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        handler.removeCallbacksAndMessages(null)
        clearPages()
        PlaybackProgress.retainActive(context)
        return super.handleRelease()
    }
}
