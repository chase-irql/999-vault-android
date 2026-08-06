package com.vault999.android.downloads

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A relative, provider-independent location below a configured vault root. */
@JvmInline
value class VaultPath private constructor(val value: String) {
    val segments: List<String> get() = value.split('/')

    companion object {
        fun of(untrusted: String): VaultPath = VaultPath(SafeArchivePath.segments(untrusted).joinToString("/"))
    }
}

data class VaultEntry(
    val path: VaultPath,
    val exists: Boolean,
    val isDirectory: Boolean,
    val size: Long?,
    /** A user-displayable location. This may be a content URI and is never presented as a fake file path. */
    val displayLocation: String,
)

class StoragePermissionLostException(message: String, cause: Throwable? = null) : Exception(message, cause)
class StorageNotSeekableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Blocking storage boundary used by workers. Callers must invoke it from an I/O dispatcher.
 * A sink positioned at [offset] truncates any bytes after that checkpoint before writing.
 */
interface VaultStorage {
    val rootDisplayName: String
    fun inspect(path: VaultPath): VaultEntry
    fun createDirectories(path: VaultPath)
    fun openSource(path: VaultPath): InputStream
    fun openSink(path: VaultPath, offset: Long = 0): OutputStream
    fun delete(path: VaultPath): Boolean
    fun availableBytes(): Long?
    fun move(source: VaultPath, destination: VaultPath, replaceExisting: Boolean = false)
}

class AppSpecificVaultStorage(root: File) : VaultStorage {
    private val root = root.canonicalFile.also { require(it.mkdirs() || it.isDirectory) { "Cannot create vault root" } }

    override val rootDisplayName: String get() = root.absolutePath

    override fun inspect(path: VaultPath): VaultEntry {
        val file = resolve(path)
        return VaultEntry(path, file.exists(), file.isDirectory, file.takeIf(File::isFile)?.length(), file.absolutePath)
    }

    override fun createDirectories(path: VaultPath) {
        val directory = resolve(path)
        if (!directory.mkdirs() && !directory.isDirectory) throw IllegalStateException("Cannot create directory")
    }

    override fun openSource(path: VaultPath): InputStream = FileInputStream(resolve(path))

    override fun openSink(path: VaultPath, offset: Long): OutputStream {
        require(offset >= 0) { "Negative offset" }
        val file = resolve(path)
        file.parentFile?.let { if (!it.mkdirs() && !it.isDirectory) error("Cannot create parent directory") }
        val randomAccess = RandomAccessFile(file, "rw")
        try {
            require(offset <= randomAccess.length()) { "Checkpoint exceeds destination length" }
            randomAccess.setLength(offset)
            randomAccess.seek(offset)
            return RandomAccessFileOutputStream(randomAccess)
        } catch (failure: Throwable) {
            randomAccess.close()
            throw failure
        }
    }

    override fun delete(path: VaultPath): Boolean = resolve(path).delete()

    // The policy deliberately uses immediately writable bytes and retains its own safety reserve;
    // it must not evict unrelated caches merely to make a transfer fit.
    @SuppressLint("UsableSpace")
    override fun availableBytes(): Long = root.usableSpace

    override fun move(source: VaultPath, destination: VaultPath, replaceExisting: Boolean) {
        val from = resolve(source)
        val to = resolve(destination)
        to.parentFile?.let { if (!it.mkdirs() && !it.isDirectory) error("Cannot create parent directory") }
        val options = if (replaceExisting) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(from.toPath(), to.toPath(), *options)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            val fallback = if (replaceExisting) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
            Files.move(from.toPath(), to.toPath(), *fallback)
        }
    }

    private fun resolve(path: VaultPath): File {
        var candidate = root
        path.segments.forEach { candidate = File(candidate, it) }
        val canonical = candidate.canonicalFile
        require(canonical.toPath().startsWith(root.toPath())) { "Path escaped vault root" }
        return canonical
    }
}

/** Storage Access Framework implementation backed by a persistable document-tree URI. */
class SafVaultStorage(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : VaultStorage {
    private val rootUri: Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    override val rootDisplayName: String get() = treeUri.toString()

    override fun inspect(path: VaultPath): VaultEntry {
        val uri = find(path)
        if (uri == null) return VaultEntry(path, false, false, null, treeUri.toString())
        val metadata = metadata(uri)
        return VaultEntry(path, true, metadata.mimeType == DocumentsContract.Document.MIME_TYPE_DIR, metadata.size, uri.toString())
    }

    override fun createDirectories(path: VaultPath) {
        ensureParent(path.segments, includeLast = true)
    }

    override fun openSource(path: VaultPath): InputStream = permissionBoundary {
        val uri = find(path) ?: throw java.io.FileNotFoundException(path.value)
        resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(path.value)
    }

    override fun openSink(path: VaultPath, offset: Long): OutputStream = permissionBoundary {
        require(offset >= 0) { "Negative offset" }
        val parent = ensureParent(path.segments, includeLast = false)
        val name = path.segments.last()
        val document = findChild(parent, name)?.uri ?: DocumentsContract.createDocument(
            resolver,
            parent,
            "application/octet-stream",
            name,
        ) ?: error("Provider refused to create document")
        val descriptor = resolver.openFileDescriptor(document, "rw")
            ?: throw java.io.FileNotFoundException(path.value)
        val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        try {
            val channel = stream.channel
            if (offset > channel.size()) throw IllegalArgumentException("Checkpoint exceeds destination length")
            channel.truncate(offset)
            channel.position(offset)
            stream
        } catch (failure: Throwable) {
            stream.close()
            throw StorageNotSeekableException("The selected document provider cannot resume writes", failure)
        }
    }

    override fun delete(path: VaultPath): Boolean = permissionBoundary {
        find(path)?.let { DocumentsContract.deleteDocument(resolver, it) } ?: false
    }

    override fun availableBytes(): Long? = permissionBoundary {
        resolver.openFileDescriptor(rootUri, "r")?.use { descriptor ->
            runCatching {
                val stats = Os.fstatvfs(descriptor.fileDescriptor)
                Math.multiplyExact(stats.f_bavail, stats.f_bsize)
            }.getOrNull()
        }
    }

    override fun move(source: VaultPath, destination: VaultPath, replaceExisting: Boolean) {
        if (source == destination) return
        val sourceUri = find(source) ?: throw java.io.FileNotFoundException(source.value)
        val sourceParent = findParent(source)
        val destinationParent = ensureParent(destination.segments, includeLast = false)
        val destinationName = destination.segments.last()
        val existing = findChild(destinationParent, destinationName)?.uri
        if (existing != null) {
            check(replaceExisting) { "Destination exists" }
        }

        // SAF has no portable atomic replace primitive. Preserve the old final under a temporary
        // name until the new document has been moved and renamed successfully, then remove it.
        // Providers which cannot rename the old final fail before publication changes anything.
        var backup: Uri? = null
        var moved: Uri = sourceUri
        try {
            if (existing != null) {
                val backupName = uniqueBackupName(destinationParent, destinationName)
                backup = permissionBoundary {
                    DocumentsContract.renameDocument(resolver, existing, backupName)
                        ?: error("Provider refused to protect the existing destination")
                }
            }
            if (sourceParent != destinationParent) {
                moved = permissionBoundary {
                    DocumentsContract.moveDocument(resolver, sourceUri, sourceParent, destinationParent)
                        ?: error("Provider refused to move document")
                }
            }
            if (source.segments.last() != destinationName) {
                moved = permissionBoundary {
                    DocumentsContract.renameDocument(resolver, moved, destinationName)
                        ?: error("Provider refused to rename document")
                }
            }
        } catch (publicationFailure: Throwable) {
            // Best-effort rollback is deliberately non-destructive: never delete the prior final.
            // A provider may leave the incoming partial in either parent, but the old final is
            // restored whenever its rename operation remains available.
            if (sourceParent != destinationParent && moved != sourceUri) {
                runCatching {
                    permissionBoundary {
                        DocumentsContract.moveDocument(resolver, moved, destinationParent, sourceParent)
                            ?: error("Provider refused to roll back the incoming document")
                    }
                }.exceptionOrNull()?.let(publicationFailure::addSuppressed)
            }
            backup?.let { protectedFinal ->
                runCatching {
                    permissionBoundary {
                        DocumentsContract.renameDocument(resolver, protectedFinal, destinationName)
                            ?: error("Provider refused to restore the existing destination")
                    }
                }.exceptionOrNull()?.let(publicationFailure::addSuppressed)
            }
            throw publicationFailure
        }

        // Publication succeeded. Failure to remove the backup is harmless and leaves a
        // recoverable duplicate instead of risking loss of the newly published final.
        backup?.let { protectedFinal ->
            runCatching { permissionBoundary { DocumentsContract.deleteDocument(resolver, protectedFinal) } }
        }
    }

    private fun uniqueBackupName(parent: Uri, destinationName: String): String {
        repeat(8) {
            val candidate = ".$destinationName.vault-backup-${UUID.randomUUID()}"
            if (findChild(parent, candidate) == null) return candidate
        }
        error("Cannot allocate a safe replacement backup name")
    }

    private fun find(path: VaultPath): Uri? {
        var current = rootUri
        for (segment in path.segments) current = findChild(current, segment)?.uri ?: return null
        return current
    }

    private fun findParent(path: VaultPath): Uri {
        if (path.segments.size == 1) return rootUri
        return find(VaultPath.of(path.segments.dropLast(1).joinToString("/")))
            ?: throw java.io.FileNotFoundException(path.value)
    }

    private fun ensureParent(segments: List<String>, includeLast: Boolean): Uri {
        var current = rootUri
        val directorySegments = if (includeLast) segments else segments.dropLast(1)
        directorySegments.forEach { name ->
            val child = findChild(current, name)
            if (child != null) {
                check(child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) { "Path component is not a directory" }
                current = child.uri
            } else {
                current = permissionBoundary {
                    DocumentsContract.createDocument(
                        resolver,
                        current,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        name,
                    ) ?: error("Provider refused to create directory")
                }
            }
        }
        return current
    }

    private fun findChild(parent: Uri, name: String): DocumentMetadata? = permissionBoundary {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.string(1) == name) {
                    return@permissionBoundary DocumentMetadata(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.string(0)),
                        cursor.string(1),
                        cursor.string(2),
                        cursor.nullableLong(3),
                    )
                }
            }
            null
        }
    }

    private fun metadata(uri: Uri): DocumentMetadata = permissionBoundary {
        resolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) throw java.io.FileNotFoundException(uri.toString())
            DocumentMetadata(uri, cursor.string(1), cursor.string(2), cursor.nullableLong(3))
        } ?: throw java.io.FileNotFoundException(uri.toString())
    }

    private inline fun <T> permissionBoundary(block: () -> T): T {
        val persisted = resolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && (permission.isReadPermission || permission.isWritePermission)
        }
        if (!persisted) throw StoragePermissionLostException("Access to the selected folder was revoked")
        return try {
            block()
        } catch (failure: SecurityException) {
            throw StoragePermissionLostException("Access to the selected folder was revoked", failure)
        }
    }

    private data class DocumentMetadata(val uri: Uri, val name: String, val mimeType: String, val size: Long?)

    private fun Cursor.string(index: Int): String = getString(index)
    private fun Cursor.nullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
}

private class RandomAccessFileOutputStream(private val file: RandomAccessFile) : OutputStream() {
    override fun write(value: Int) = file.write(value)
    override fun write(bytes: ByteArray, offset: Int, length: Int) = file.write(bytes, offset, length)
    override fun flush() = file.fd.sync()
    override fun close() = file.close()
}
