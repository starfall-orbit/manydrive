package com.starfall.gsadrive

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.starfall.gsadrive.data.DriveFile
import com.starfall.gsadrive.data.S3Config
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class S3PlaybackSourceTest {
    @Test fun s3ResolvesToSignedHttpRangeWithoutDownloadingOrUsingLocalCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "s3-stream-test-${System.nanoTime()}")
        val cached = File(directory, "media.mp4")
        val source = PlaybackSource(
            mediaId = "s3:test-video",
            file = DriveFile(id = "folder/a + b.mp4", name = "a + b.mp4", mimeType = "video/mp4", modifiedTime = null),
            accountType = "S3", accessToken = null,
            s3Config = S3Config("https://s3.example.invalid", "test-access", "test-secret", "test-bucket"),
            cacheFile = cached
        )
        PlaybackSourceRegistry.replace(listOf(source))
        try {
            val request = DataSpec.Builder()
                .setUri(source.toMediaItem().localConfiguration!!.uri)
                .setPosition(123_456L).setLength(8192L)
                .setKey(source.mediaId).build()
            val resolved = resolvePlaybackDataSpec(request)
            assertEquals("https", resolved.uri.scheme)
            assertEquals("s3.example.invalid", resolved.uri.host)
            assertEquals("/test-bucket/folder/a + b.mp4", resolved.uri.path)
            assertFalse(resolved.uri.getQueryParameter("X-Amz-Signature").isNullOrEmpty())
            assertEquals(123_456L, resolved.position)
            assertEquals(8192L, resolved.length)
            assertEquals(source.mediaId, resolved.key)
            // Presigning with static credentials needs no request to the unreachable endpoint.
            assertFalse(directory.exists())
            directory.mkdirs()
            cached.writeText("old cache")
            assertEquals("https", resolvePlaybackDataSpec(request).uri.scheme)
            assertEquals("old cache", cached.readText())
            try {
                PlaybackSourceRegistry.resolve(source.mediaId)
                fail("S3 must never use full-file cache resolution")
            } catch (_: IOException) { }
        } finally {
            PlaybackSourceRegistry.clear()
            directory.deleteRecursively()
        }
    }

    @Test fun otherUrisPassThroughUnchanged() {
        val request = DataSpec.Builder().setUri(Uri.parse("file:///test.mp4")).setPosition(64L).build()
        assertSame(request, resolvePlaybackDataSpec(request))
    }
}
