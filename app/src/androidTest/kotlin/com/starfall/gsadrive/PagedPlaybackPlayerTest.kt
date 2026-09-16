package com.starfall.gsadrive

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PagedPlaybackPlayerTest {
    private fun withPlayer(test: (PagedPlaybackPlayer) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            PlaybackProgress.clear(instrumentation.targetContext)
            val player = PagedPlaybackPlayer(instrumentation.targetContext)
            try {
                // No remote data or credentials are required to verify player ownership.
                player.setMediaItems((0..4).map {
                    MediaItem.Builder().setMediaId("pager-test-$it").setUri("file:///missing-$it.mp4").build()
                })
                test(player)
            } finally {
                player.release()
                PlaybackProgress.clear(instrumentation.targetContext)
            }
        }
    }

    @Test fun taskExitResetsNeighborsAndClosingResetsActiveMedia() = withPlayer { session ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = session.acquire("pager-test-0")!!
        session.seekTo(0, 60_000L)
        session.seekTo(1, 120_000L)
        assertEquals(60_000L, PlaybackProgress.read(context, "pager-test-0"))
        session.endAppSession()
        assertEquals(0L, first.currentPosition)
        assertEquals(0L, PlaybackProgress.read(context, "pager-test-0"))
        assertEquals(120_000L, PlaybackProgress.read(context, "pager-test-1"))
        session.stop()
        session.clearMediaItems()
        assertEquals(0L, PlaybackProgress.read(context, "pager-test-1"))
        val prefs = context.getSharedPreferences("playback_progress", android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun selectingPageKeepsBothPlayersAndTheirItems() = withPlayer { session ->
        val first = session.acquire("pager-test-0")!!
        val second = session.acquire("pager-test-1")!!
        first.seekTo(1234)
        session.play()
        session.seekTo(1, 2345)
        assertEquals("pager-test-1", session.currentMediaItem!!.mediaId)
        assertEquals("pager-test-0", first.currentMediaItem!!.mediaId)
        assertEquals("pager-test-1", second.currentMediaItem!!.mediaId)
        assertFalse(first.playWhenReady)
        assertTrue(second.playWhenReady)
        assertEquals(1234L, first.currentPosition)
        assertEquals(2345L, second.currentPosition)
        val sameSecond = session.acquire("pager-test-1")!!
        assertSame(second, sameSecond)
        session.relinquish("pager-test-1", sameSecond)
        session.seekTo(0, 1234)
        assertTrue(first.playWhenReady)
        assertFalse(second.playWhenReady)
        assertEquals("pager-test-1", second.currentMediaItem!!.mediaId)
        session.relinquish("pager-test-0", first)
        session.relinquish("pager-test-1", second)
    }

    @Test fun borrowingNeighborDoesNotChangeSelectionOrStartPlayback() = withPlayer { session ->
        session.play()
        val adjacent = session.acquire("pager-test-1")!!
        assertEquals(0, session.currentMediaItemIndex)
        assertFalse(adjacent.playWhenReady)
        assertEquals(0f, adjacent.volume)
        session.relinquish("pager-test-1", adjacent)
        assertEquals(0, session.currentMediaItemIndex)
        assertTrue(session.playWhenReady)
    }

    @Test fun notificationNextAndPreviousSelectPagePlayers() = withPlayer { session ->
        val first = session.acquire("pager-test-0")!!
        val second = session.acquire("pager-test-1")!!
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
        assertTrue(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        session.seekToNextMediaItem()
        assertEquals(1, session.currentMediaItemIndex)
        session.play()
        assertTrue(second.playWhenReady)
        session.seekToPreviousMediaItem()
        assertEquals(0, session.currentMediaItemIndex)
        assertTrue(first.playWhenReady)
        assertFalse(second.playWhenReady)
        session.relinquish("pager-test-0", first)
        session.relinquish("pager-test-1", second)
    }

    @Test fun platformTransportCommandsKeepPlaylistOrderAndRespectBoundaries() = withPlayer { session ->
        session.seekTo(2, 30_000)
        session.seekToPrevious()
        assertEquals("pager-test-1", session.currentMediaItem!!.mediaId)
        session.seekToNext()
        assertEquals("pager-test-2", session.currentMediaItem!!.mediaId)

        session.seekTo(0, 0)
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        session.seekToPrevious()
        assertEquals(0, session.currentMediaItemIndex)
        session.seekToNext()
        assertEquals(1, session.currentMediaItemIndex)

        session.seekTo(4, 0)
        assertFalse(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        session.seekToNext()
        assertEquals(4, session.currentMediaItemIndex)
        session.seekToPrevious()
        assertEquals(3, session.currentMediaItemIndex)
    }

    @Test fun onlyImmediateNeighborsAreAllocatedAndThePoolNeverExceedsThree() = withPlayer { session ->
        session.seekTo(1, 0)
        val previous = session.acquire("pager-test-0")!!
        val current = session.acquire("pager-test-1")!!
        val next = session.acquire("pager-test-2")!!
        assertEquals(3, session.residentPlayerCount)
        assertNull(session.acquire("pager-test-3"))
        session.seekTo(2, 0)
        val newNext = session.acquire("pager-test-3")!!
        assertEquals(3, session.residentPlayerCount)
        assertSame(next, session.acquire("pager-test-2"))
        session.relinquish("pager-test-2", next)
        session.relinquish("pager-test-0", previous)
        session.relinquish("pager-test-1", current)
        session.relinquish("pager-test-2", next)
        session.relinquish("pager-test-3", newNext)
        assertEquals(1, session.residentPlayerCount)
    }

    @Test fun activePageSurvivesViewerDisposalAndPlaylistCanBeCleared() = withPlayer { session ->
        val active = session.acquire("pager-test-0")!!
        session.relinquish("pager-test-0", active)
        val reacquired = session.acquire("pager-test-0")!!
        assertSame(active, reacquired)
        session.relinquish("pager-test-0", reacquired)
        session.stop()
        session.clearMediaItems()
        assertEquals(0, session.mediaItemCount)
        assertNull(session.currentMediaItem)
        assertNull(session.acquire("pager-test-0"))
    }
}
