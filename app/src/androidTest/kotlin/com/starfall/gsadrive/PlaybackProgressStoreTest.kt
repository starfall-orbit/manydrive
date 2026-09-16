package com.starfall.gsadrive

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackProgressStoreTest {
    @Test fun onlyActiveMediaSurvivesRestartAndCloseErasesEverything() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("playback_progress_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val session = PlaybackProgressStore(prefs)
            session.activate("a", 60_000L)
            session.activate("b", 120_000L)
            session.save("a", 90_000L)
            assertEquals(90_000L, session.read("a"))
            assertEquals(120_000L, session.read("b"))

            val restarted = PlaybackProgressStore(prefs)
            assertEquals(0L, restarted.read("a"))
            assertEquals(120_000L, restarted.read("b"))
            session.retainActive()
            assertEquals(0L, session.read("a"))
            assertEquals(120_000L, session.read("b"))

            restarted.clear()
            // A late save from the old player must not recreate persistent history.
            restarted.save("b", 150_000L)
            val reopened = PlaybackProgressStore(prefs)
            assertEquals(0L, reopened.read("a"))
            assertEquals(0L, reopened.read("b"))
            assertTrue(prefs.all.isEmpty())
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun legacyHistoryIsDiscardedAndNoActiveMediaIsPersisted() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("playback_progress_test", Context.MODE_PRIVATE)
        prefs.edit().clear().putLong("old-media", 180_000L).commit()
        try {
            val session = PlaybackProgressStore(prefs)
            assertEquals(0L, session.read("old-media"))
            session.activate("a", 60_000L)
            session.activate(null)
            assertEquals(60_000L, session.read("a"))
            assertEquals(0L, PlaybackProgressStore(prefs).read("a"))
            session.clear()
            assertEquals(0L, session.read("a"))
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
