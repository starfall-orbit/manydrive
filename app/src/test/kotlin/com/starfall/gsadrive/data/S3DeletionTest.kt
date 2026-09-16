package com.starfall.gsadrive.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class S3DeletionTest {
    @Test fun deletesAllPagesIncludingNestedFilesAndFolderMarker() = runBlocking {
        val tokens = mutableListOf<String?>()
        val batches = mutableListOf<List<String>>()
        deleteS3Folder("photos", { prefix, token ->
            assertEquals("photos/", prefix)
            tokens += token
            if (token == null) S3DeletePage(listOf("photos/") + (1..999).map { "photos/nested/$it" }, "page-2")
            else S3DeletePage(listOf("photos/nested/1000", "photos/last"))
        }, { batches += it })
        assertEquals(listOf(null, "page-2"), tokens)
        assertEquals(listOf(1000, 2), batches.map { it.size })
        assertEquals(1002, batches.flatten().distinct().size)
    }

    @Test fun preservesExactKeyAndRejectsSiblingPrefixes() = runBlocking {
        var deleted = false
        try {
            deleteS3Folder("a// b", { prefix, _ ->
                assertEquals("a// b/", prefix)
                S3DeletePage(listOf("a// b/file", "a// backup/file"))
            }, { deleted = true })
            fail("Objects outside the selected prefix must not be deleted")
        } catch (_: IllegalArgumentException) { }
        assertFalse(deleted)
    }

    @Test fun emptyFolderRequiresNoDeleteRequest() = runBlocking {
        deleteS3Folder("empty/", { _, _ -> S3DeletePage(emptyList()) }, { fail("No keys to delete") })
    }

    @Test fun bucketRootCannotBeDeleted() = runBlocking {
        for (key in listOf("", "/")) {
            try {
                deleteS3Folder(key, { _, _ -> error("Must not list the bucket") }, { error("Must not delete") })
                fail("Root must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun deleteFailureStopsTraversalAndIsReported() = runBlocking {
        var requests = 0
        try {
            deleteS3Folder("folder/", { _, _ ->
                requests++
                S3DeletePage(listOf("folder/file"), "next")
            }, { throw IllegalStateException("AccessDenied") })
            fail("Deletion failures must propagate")
        } catch (error: IllegalStateException) {
            assertEquals("AccessDenied", error.message)
        }
        assertEquals(1, requests)
    }

    @Test fun repeatedContinuationTokenDoesNotLoopForever() = runBlocking {
        var requests = 0
        try {
            deleteS3Folder("folder/", { _, _ ->
                requests++
                S3DeletePage(emptyList(), "same-token")
            }, {})
            fail("Invalid pagination must fail")
        } catch (_: IllegalStateException) { }
        assertEquals(2, requests)
    }
}
