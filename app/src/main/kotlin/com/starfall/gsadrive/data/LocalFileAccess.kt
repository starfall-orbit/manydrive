package com.starfall.gsadrive.data

import java.io.File
import java.io.IOException

/** Keep navigation within shared storage, including when an entry is a symbolic link. */
internal class LocalFileAccess(root: File) {
    val root: File = root.canonicalFile

    fun list(path: String): List<File> {
        val directory = File(path).canonicalFile
        require(contains(directory)) { "Path is outside shared storage" }
        val entries = directory.listFiles() ?: throw IOException("Cannot read directory")
        return entries.filter { runCatching { contains(it.canonicalFile) }.getOrDefault(false) }
    }

    fun checked(path: String): File {
        val file = File(path)
        require(contains(file.canonicalFile)) { "Path is outside shared storage" }
        require(file.canonicalFile != root) { "Cannot modify shared storage root" }
        require(file.exists()) { "File no longer exists" }
        return file
    }

    fun tree(path: String): List<File> {
        val start = checked(path)
        val result = mutableListOf<File>()
        fun visit(file: File, depth: Int) {
            require(depth < 128) { "Folder is too deep" }
            require(contains(file.canonicalFile)) { "Path is outside shared storage" }
            require(file.canonicalFile == File(file.parentFile.canonicalFile, file.name)) { "Symbolic links are not supported" }
            result += file
            if (file.isDirectory) (file.listFiles() ?: throw IOException("Cannot read folder: ${file.name}"))
                .forEach { visit(it, depth + 1) }
        }
        visit(start, 0)
        return result
    }

    fun rename(path: String, name: String) {
        require(name.isNotBlank() && name !in listOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Invalid name" }
        val source = checked(path)
        val target = File(source.parentFile, name)
        if (source == target) return
        require(!target.exists()) { "An item with this name already exists" }
        check(source.renameTo(target)) { "Cannot rename this item" }
    }

    fun delete(path: String) {
        tree(path).asReversed().forEach { check(it.delete()) { "Cannot delete: ${it.name}" } }
    }

    fun transfer(path: String, destination: String, move: Boolean) {
        val source = checked(path)
        val folder = File(destination).canonicalFile
        require(contains(folder) && folder.isDirectory) { "Invalid destination" }
        require(folder != source.canonicalFile && !folder.path.startsWith(source.canonicalPath + File.separator)) { "Cannot place a folder inside itself" }
        val target = File(folder, source.name)
        require(!target.exists()) { "An item with this name already exists" }
        val files = tree(path)
        if (move && source.renameTo(target)) return
        val temporary = File(folder, ".manydrive-${java.util.UUID.randomUUID()}")
        try {
            for (file in files) {
                val output = if (file == source) temporary else File(temporary, file.relativeTo(source).path)
                if (file.isDirectory) check(output.mkdirs()) { "Cannot create folder" }
                else file.copyTo(output, overwrite = false)
            }
            check(!target.exists() && temporary.renameTo(target)) { "Cannot finish copying" }
        } finally { temporary.deleteRecursively() }
        if (move) delete(path)
    }

    private fun contains(file: File): Boolean = file == root || file.path.startsWith(root.path + File.separator)
}
