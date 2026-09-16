package com.starfall.gsadrive.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalFileAccessTest {
    @Test fun listsFilesAndFoldersWithoutRecursivelyLoadingChildren() {
        val root = Files.createTempDirectory("local-files").toFile()
        try {
            File(root, "folder").mkdir()
            File(root, "folder/nested.txt").writeText("nested")
            File(root, "video.mp4").writeText("video")
            val access = LocalFileAccess(root)
            assertEquals(setOf("folder", "video.mp4"), access.list(root.path).map { it.name }.toSet())
            assertEquals(listOf("nested.txt"), access.list(File(root, "folder").path).map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsParentTraversalAndSiblingWithSameNamePrefix() {
        val parent = Files.createTempDirectory("local-files").toFile()
        try {
            val root = File(parent, "sdcard").apply { mkdir() }
            File(parent, "sdcard-other").mkdir()
            val access = LocalFileAccess(root)
            for (path in listOf(File(root, "..").path, File(parent, "sdcard-other").path)) {
                assertThrows(IllegalArgumentException::class.java) { access.list(path) }
            }
        } finally { parent.deleteRecursively() }
    }

    @Test fun skipsSymlinksOutsideSharedStorage() {
        val parent = Files.createTempDirectory("local-files").toFile()
        try {
            val root = File(parent, "sdcard").apply { mkdir() }
            val outside = File(parent, "private").apply { mkdir() }
            Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
            val access = LocalFileAccess(root)
            assertTrue(access.list(root.path).isEmpty())
            assertThrows(IllegalArgumentException::class.java) { access.list(File(root, "escape").path) }
        } finally { parent.deleteRecursively() }
    }

    @Test fun missingFolderIsAnErrorNotAnEmptyListing() {
        val root = Files.createTempDirectory("local-files").toFile()
        try {
            assertThrows(java.io.IOException::class.java) { LocalFileAccess(root).list(File(root, "missing").path) }
        } finally { root.deleteRecursively() }
    }
    @Test fun copyMoveRenameAndDeletePreserveContentsAndEmptyFolders() {
        val root = Files.createTempDirectory("local-actions").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "empty").mkdir()
            File(source, "a.txt").writeText("hello")
            val destination = File(root, "destination").apply { mkdir() }
            val access = LocalFileAccess(root)
            access.transfer(source.path, destination.path, false)
            assertEquals("hello", File(destination, "source/a.txt").readText())
            assertTrue(File(destination, "source/empty").isDirectory)
            assertTrue(source.exists())
            access.rename(source.path, "renamed")
            access.transfer(File(root, "renamed").path, destination.path, true)
            assertFalse(File(root, "renamed").exists())
            assertEquals("hello", File(destination, "renamed/a.txt").readText())
            access.delete(File(destination, "renamed").path)
            assertFalse(File(destination, "renamed").exists())
            assertTrue(File(destination, "source/a.txt").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun refusesOverwriteSelfCopyRootDeletionAndInvalidNames() {
        val root = Files.createTempDirectory("local-actions").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val a = File(root, "a.txt").apply { writeText("original") }
            File(root, "b.txt").writeText("keep")
            val access = LocalFileAccess(root)
            assertThrows(IllegalArgumentException::class.java) { access.transfer(a.path, root.path, false) }
            assertThrows(IllegalArgumentException::class.java) { access.transfer(source.path, source.path, false) }
            assertThrows(IllegalArgumentException::class.java) { access.rename(a.path, "b.txt") }
            for (name in listOf("../escape", "", ".", "..", "a/b")) {
                assertThrows(IllegalArgumentException::class.java) { access.rename(a.path, name) }
            }
            assertThrows(IllegalArgumentException::class.java) { access.delete(root.path) }
            assertEquals("original", a.readText())
            assertEquals("keep", File(root, "b.txt").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun recursiveDeleteDoesNotFollowSymlinksOrPartiallyDeleteBeforeValidation() {
        val root = Files.createTempDirectory("local-actions").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "keep.txt").writeText("keep")
            val other = File(root, "other").apply { mkdir() }
            Files.createSymbolicLink(File(source, "link").toPath(), other.toPath())
            assertThrows(IllegalArgumentException::class.java) { LocalFileAccess(root).delete(source.path) }
            assertEquals("keep", File(source, "keep.txt").readText())
            assertTrue(other.isDirectory)
        } finally { root.deleteRecursively() }
    }

}
