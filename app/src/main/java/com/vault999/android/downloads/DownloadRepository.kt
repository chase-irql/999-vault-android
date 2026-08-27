package com.vault999.android.downloads

import android.content.Context
import android.net.Uri
import com.vault999.android.database.DownloadDao
import com.vault999.android.database.DownloadEntity
import com.vault999.android.model.ArchiveEntry
import com.vault999.android.model.DownloadJob
import com.vault999.android.model.DownloadKind
import com.vault999.android.model.DownloadStage
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.vault999.android.preferences.VaultPreferences
import kotlinx.coroutines.flow.first
import com.vault999.android.network.JuiceWrldApiClient
import com.vault999.android.network.NetworkException
import com.vault999.android.network.ZipJobState
import com.vault999.android.model.VaultError
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DownloadRepository(
    private val context: Context,
    private val dao: DownloadDao,
    private val http: OkHttpClient,
    private val scheduler: VaultTransferScheduler,
    private val preferences: VaultPreferences,
    private val api: JuiceWrldApiClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observe(): Flow<List<DownloadJob>> = dao.observeAll().map { rows -> rows.map(DownloadEntity::asModel) }

    suspend fun enqueueFile(entry: ArchiveEntry, url: String): String {
        val id = UUID.randomUUID().toString()
        val root = storageRoot()
        val settings = preferences.settings.first()
        val selectedTree = settings.safTreeUri
        val destinationType = if (selectedTree != null) "SAF" else "APP_SPECIFIC"
        val destinationIdentity = selectedTree ?: root.absolutePath
        if (selectedTree != null) require(hasWritePermission(Uri.parse(selectedTree))) { "Access to the selected download folder was revoked" }
        val relative = VaultPath.of("Archive/${entry.path}").value
        val timestamp = now()
        dao.upsert(
            DownloadEntity(
                id = id,
                kind = DownloadKind.FILE.name,
                stage = DownloadStage.QUEUED.name,
                displayName = entry.name,
                destinationType = destinationType,
                destinationIdentity = destinationIdentity,
                sourceJson = encodeFileSource(url, relative),
                bytesCompleted = 0,
                bytesTotal = entry.sizeBytes,
                bytesPerSecond = null,
                etaSeconds = null,
                currentItem = entry.name,
                validator = null,
                checkpointJson = null,
                errorCode = null,
                createdAtEpochMs = timestamp,
                updatedAtEpochMs = timestamp,
            ),
        )
        scheduler.schedule(id, entry.sizeBytes, settings.networkPolicy == com.vault999.android.preferences.NetworkPolicy.WIFI_ONLY)
        return id
    }

    suspend fun enqueueFullCollection(paths: List<String> = listOf("Compilation")): String =
        enqueueCollection(paths, "Full Compilation collection")

    suspend fun enqueueCollection(paths: List<String>, displayName: String = "Archive selection"): String {
        require(paths.isNotEmpty())
        val id = UUID.randomUUID().toString()
        val settings = preferences.settings.first()
        val selectedTree = settings.safTreeUri
        val destinationType = if (selectedTree != null) "SAF" else "APP_SPECIFIC"
        val destinationIdentity = selectedTree ?: storageRoot().absolutePath
        if (selectedTree != null) require(hasWritePermission(Uri.parse(selectedTree))) { "Access to the selected download folder was revoked" }
        val timestamp = now()
        dao.upsert(
            DownloadEntity(
                id = id,
                kind = DownloadKind.FULL_COLLECTION.name,
                stage = DownloadStage.QUEUED.name,
                displayName = displayName.trim().take(120).ifBlank { "Archive selection" },
                destinationType = destinationType,
                destinationIdentity = destinationIdentity,
                sourceJson = "COLLECTION$SEPARATOR${paths.joinToString(PATH_SEPARATOR.toString())}",
                bytesCompleted = 0,
                bytesTotal = null,
                bytesPerSecond = null,
                etaSeconds = null,
                currentItem = "Server preparation",
                validator = null,
                checkpointJson = null,
                errorCode = null,
                createdAtEpochMs = timestamp,
                updatedAtEpochMs = timestamp,
            ),
        )
        scheduler.schedule(id, null, settings.networkPolicy == com.vault999.android.preferences.NetworkPolicy.WIFI_ONLY)
        return id
    }

    suspend fun pause(id: String) {
        dao.updateStage(id, DownloadStage.PAUSED.name, now())
        scheduler.stop(id)
    }

    suspend fun resume(id: String) {
        val row = requireNotNull(dao.get(id))
        dao.updateStage(id, DownloadStage.QUEUED.name, now())
        val wifiOnly = preferences.settings.first().networkPolicy == com.vault999.android.preferences.NetworkPolicy.WIFI_ONLY
        scheduler.schedule(id, row.bytesTotal, wifiOnly)
    }

    suspend fun cancel(id: String) {
        dao.updateStage(id, DownloadStage.CANCELLING.name, now())
        dao.get(id)?.checkpointJson?.takeIf { it.startsWith("zipjob:") }?.substringAfter(':')?.let { zipId ->
            runCatching { api.cancelZip(zipId) }
        }
        scheduler.stop(id)
        dao.updateStage(id, DownloadStage.CANCELLED.name, now())
    }

    suspend fun execute(id: String, progress: suspend (DownloadJob) -> Unit = {}): TransferExecutionResult = withContext(Dispatchers.IO) {
        val concurrency = preferences.settings.first().downloadConcurrency.coerceIn(1, 4)
        TransferConcurrencyGate.acquire(concurrency)
        try {
            var row = dao.get(id) ?: return@withContext TransferExecutionResult.FINISHED
            if (row.stage == DownloadStage.CANCELLED.name || row.stage == DownloadStage.COMPLETED.name) return@withContext TransferExecutionResult.FINISHED
            if (row.kind == DownloadKind.FULL_COLLECTION.name) {
                return@withContext executeCollection(row, progress)
            }
            val source = decodeFileSource(row.sourceJson)
            val storage: VaultStorage = when (row.destinationType) {
            "SAF" -> Uri.parse(row.destinationIdentity).also { require(hasWritePermission(it)) { "Access to the selected download folder was revoked" } }
                .let { SafVaultStorage(context.contentResolver, it) }
            else -> AppSpecificVaultStorage(storageRoot())
            }
            val partial = VaultPath.of("${source.relativePath}.part")
            val final = VaultPath.of(source.relativePath)
            val existing = storage.inspect(partial).size ?: 0L
            val savedTotal = row.bytesTotal
            val saved = if (existing > 0 && !row.validator.isNullOrBlank() && savedTotal != null) {
                ResumeMetadata(row.validator, savedTotal, minOf(existing, row.bytesCompleted))
            } else null
            row = row.copy(stage = DownloadStage.DOWNLOADING.name, bytesCompleted = saved?.completedBytes ?: 0, updatedAtEpochMs = now(), errorCode = null)
            dao.upsert(row)
            try {
                val estimator = EtaEstimator()
                val result = OkHttpStreamingTransfer(http).download(
                request = Request.Builder().url(source.url).get().build(),
                storage = storage,
                destination = partial,
                saved = saved,
                ) { checkpoint ->
                    val estimate = estimator.sample("downloading", checkpoint.completedBytes, checkpoint.totalBytes ?: row.bytesTotal, System.nanoTime())
                    row = row.copy(
                    stage = DownloadStage.DOWNLOADING.name,
                    bytesCompleted = checkpoint.completedBytes,
                    bytesTotal = checkpoint.totalBytes ?: row.bytesTotal,
                    validator = checkpoint.validator,
                    bytesPerSecond = estimate.bytesPerSecond,
                    etaSeconds = estimate.etaSeconds,
                    checkpointJson = checkpoint.completedBytes.toString(),
                    updatedAtEpochMs = now(),
                    )
                    dao.upsert(row)
                    progress(row.asModel())
                }
                row = row.copy(
                stage = DownloadStage.VALIDATING.name,
                bytesCompleted = result.completedBytes,
                bytesTotal = result.totalBytes,
                validator = result.validator,
                bytesPerSecond = null,
                etaSeconds = null,
                currentItem = "Validating file",
                updatedAtEpochMs = now(),
                )
                dao.upsert(row)
                storage.move(partial, final, replaceExisting = true)
                row = row.copy(stage = DownloadStage.COMPLETED.name, updatedAtEpochMs = now(), checkpointJson = null, currentItem = null)
                dao.upsert(row)
                progress(row.asModel())
                TransferExecutionResult.FINISHED
            } catch (cancelled: CancellationException) {
                val latest = dao.get(id)
                if (latest?.stage !in setOf(DownloadStage.PAUSED.name, DownloadStage.CANCELLED.name, DownloadStage.CANCELLING.name)) {
                    dao.updateStage(id, DownloadStage.INTERRUPTED.name, now())
                }
                throw cancelled
            } catch (failure: Throwable) {
                val latest = dao.get(id) ?: row
                row = latest.copy(
                    stage = transferFailureStage(failure).name,
                    errorCode = failure.javaClass.simpleName.take(80),
                    updatedAtEpochMs = now(),
                )
                dao.upsert(row)
                progress(row.asModel())
                if (row.stage == DownloadStage.INTERRUPTED.name) TransferExecutionResult.RETRY else TransferExecutionResult.FINISHED
            }
        } finally {
            TransferConcurrencyGate.release()
        }
    }

    private fun storageRoot(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "Vault")
    private fun hasWritePermission(uri: Uri): Boolean = context.contentResolver.persistedUriPermissions.any { grant ->
        grant.uri == uri && grant.isWritePermission
    }

    private suspend fun executeCollection(initial: DownloadEntity, progress: suspend (DownloadJob) -> Unit): TransferExecutionResult {
        var row = initial.copy(stage = DownloadStage.PREPARING.name, updatedAtEpochMs = now(), errorCode = null, currentItem = "Server preparation")
        dao.upsert(row)
        var serverJobId: String? = row.checkpointJson?.takeIf { it.startsWith("zipjob:") }?.substringAfter(':')
        try {
            val paths = row.sourceJson.substringAfter("COLLECTION$SEPARATOR").split(PATH_SEPARATOR).filter(String::isNotBlank)
            val tempRoot = File(context.cacheDir, "collection-transfers")
            val tempStorage = AppSpecificVaultStorage(tempRoot)
            val archivePart = VaultPath.of("${row.id}.zip.part")
            val archiveFinal = VaultPath.of("${row.id}.zip")
            val archiveFile = File(tempRoot, archiveFinal.value)
            if (!archiveFile.isFile || archiveFile.length() <= 0L) {
                var serverJob = if (serverJobId == null) api.startZip(paths).also { serverJobId = it.id } else api.zipStatus(requireNotNull(serverJobId))
                row = row.copy(checkpointJson = "zipjob:${serverJob.id}", updatedAtEpochMs = now())
                dao.upsert(row)
                var polls = 0
                while (zipJobNeedsPolling(serverJob.state)) {
                    check(polls++ < 1_440) { "Collection preparation timed out" }
                    delay(5_000)
                    serverJob = api.zipStatus(serverJob.id)
                }
                check(serverJob.state == ZipJobState.READY && serverJob.downloadUrl != null) { "Collection preparation failed" }
                val collectionUrl = requireNotNull(serverJob.downloadUrl)
                val existing = tempStorage.inspect(archivePart).size ?: 0L
                val savedTotal = row.bytesTotal
                val saved = if (existing > 0 && !row.validator.isNullOrBlank() && savedTotal != null) ResumeMetadata(row.validator, savedTotal, minOf(existing, row.bytesCompleted)) else null
                row = row.copy(stage = DownloadStage.DOWNLOADING.name, bytesCompleted = saved?.completedBytes ?: 0, updatedAtEpochMs = now(), currentItem = "Compilation ZIP transfer")
                dao.upsert(row)
                val transferEta = EtaEstimator()
                val result = OkHttpStreamingTransfer(http).download(
                    Request.Builder().url(collectionUrl).get().build(),
                    tempStorage,
                    archivePart,
                    saved,
                ) { checkpoint ->
                    val estimate = transferEta.sample("downloading", checkpoint.completedBytes, checkpoint.totalBytes, System.nanoTime())
                    row = row.copy(bytesCompleted = checkpoint.completedBytes, bytesTotal = checkpoint.totalBytes, validator = checkpoint.validator, bytesPerSecond = estimate.bytesPerSecond, etaSeconds = estimate.etaSeconds, checkpointJson = "zipjob:${serverJob.id}", updatedAtEpochMs = now())
                    dao.upsert(row)
                    progress(row.asModel())
                }
                tempStorage.move(archivePart, archiveFinal, replaceExisting = true)
                row = row.copy(bytesCompleted = result.completedBytes, bytesTotal = result.totalBytes)
            } else {
                row = row.copy(bytesCompleted = archiveFile.length(), bytesTotal = row.bytesTotal ?: archiveFile.length())
            }
            val destination: VaultStorage = when (row.destinationType) {
                "SAF" -> Uri.parse(row.destinationIdentity).also { require(hasWritePermission(it)) { "Access to the selected download folder was revoked" } }
                    .let { SafVaultStorage(context.contentResolver, it) }
                else -> AppSpecificVaultStorage(storageRoot())
            }
            val resumeIndex = row.checkpointJson?.takeIf { it.startsWith("extract:") }?.substringAfter(':')?.toIntOrNull()?.coerceAtLeast(0)
            row = row.copy(stage = DownloadStage.VALIDATING.name, bytesPerSecond = null, etaSeconds = null, currentItem = "Validating Zip64 archive", updatedAtEpochMs = now())
            dao.upsert(row)
            val extractor = SafeZipExtractor()
            val plan = extractor.inspect(archiveFile)
            when (DiskSpacePolicy().checkCollection(destination.availableBytes(), archiveFile.length(), plan.totalUncompressedBytes)) {
                is DiskSpaceDecision.Insufficient -> error("Not enough storage for collection extraction")
                else -> Unit
            }
            val completedEntries = resumeIndex?.let { index -> plan.entries.take(index).mapTo(linkedSetOf()) { it.originalName } } ?: emptySet()
            row = row.copy(stage = DownloadStage.EXTRACTING.name, checkpointJson = resumeIndex?.let { "extract:$it" } ?: "extract:-1", currentItem = "Preparing extraction", updatedAtEpochMs = now())
            dao.upsert(row)
            val extractionEta = EtaEstimator()
            extractor.extract(archiveFile, destination, completedEntries) { checkpoint ->
                val estimate = extractionEta.sample("extracting", checkpoint.totalExtractedBytes, plan.totalUncompressedBytes, System.nanoTime())
                row = row.copy(checkpointJson = "extract:${checkpoint.entryIndex}", bytesPerSecond = estimate.bytesPerSecond, etaSeconds = estimate.etaSeconds, currentItem = checkpoint.entryName, updatedAtEpochMs = now())
                dao.upsert(row)
                progress(row.asModel())
            }
            row = row.copy(stage = DownloadStage.COMPLETED.name, checkpointJson = null, bytesPerSecond = null, etaSeconds = null, currentItem = null, updatedAtEpochMs = now())
            dao.upsert(row)
            archiveFile.delete()
            progress(row.asModel())
            return TransferExecutionResult.FINISHED
        } catch (cancelled: CancellationException) {
            val latest = dao.get(row.id)
            if (latest?.stage !in setOf(DownloadStage.CANCELLED.name, DownloadStage.CANCELLING.name, DownloadStage.PAUSED.name)) dao.updateStage(row.id, DownloadStage.INTERRUPTED.name, now())
            throw cancelled
        } catch (failure: Throwable) {
            val latest = dao.get(row.id) ?: row
            row = latest.copy(stage = transferFailureStage(failure).name, errorCode = failure.javaClass.simpleName.take(80), updatedAtEpochMs = now())
            dao.upsert(row)
            progress(row.asModel())
            return if (row.stage == DownloadStage.INTERRUPTED.name) TransferExecutionResult.RETRY else TransferExecutionResult.FINISHED
        }
    }

    private data class Source(val url: String, val relativePath: String)
    private fun transferFailureStage(failure: Throwable): DownloadStage =
        if (retryableTransferFailure(failure)) {
            DownloadStage.INTERRUPTED
        } else {
            DownloadStage.FAILED
        }
    private fun encodeFileSource(url: String, relative: String): String = "FILE$SEPARATOR$url$SEPARATOR$relative"
    private fun decodeFileSource(value: String): Source {
        val parts = value.split(SEPARATOR, limit = 3)
        require(parts.size == 3 && parts[0] == "FILE" && parts[1].startsWith("https://"))
        return Source(parts[1], VaultPath.of(parts[2]).value)
    }

    companion object {
        private const val SEPARATOR = '\u001F'
        private const val PATH_SEPARATOR = '\u001E'
    }
}

enum class TransferExecutionResult { FINISHED, RETRY }

/** The server may briefly report a new state before settling into its documented queue states. */
internal fun zipJobNeedsPolling(state: ZipJobState): Boolean =
    state in setOf(ZipJobState.QUEUED, ZipJobState.PREPARING, ZipJobState.UNKNOWN)

internal fun retryableTransferFailure(failure: Throwable): Boolean = when (failure) {
    is HttpTransferException -> failure.status == 408 || failure.status == 429 || failure.status in 500..599
    is NetworkException -> failure.error is VaultError.Offline ||
        failure.error is VaultError.Timeout ||
        failure.error is VaultError.RateLimited ||
        failure.error is VaultError.Server
    is IOException -> true
    else -> false
}

private object TransferConcurrencyGate {
    private val mutex = Mutex()
    private var active = 0

    suspend fun acquire(limit: Int) {
        while (true) {
            val acquired = mutex.withLock {
                if (active < limit) {
                    active++
                    true
                } else {
                    false
                }
            }
            if (acquired) return
            delay(100)
        }
    }

    suspend fun release() {
        mutex.withLock { active = (active - 1).coerceAtLeast(0) }
    }
}

private fun DownloadEntity.asModel() = DownloadJob(
    id = id,
    kind = runCatching { DownloadKind.valueOf(kind) }.getOrDefault(DownloadKind.FILE),
    stage = runCatching { DownloadStage.valueOf(stage) }.getOrDefault(DownloadStage.FAILED),
    displayName = displayName,
    destinationLabel = destinationIdentity,
    bytesCompleted = bytesCompleted,
    bytesTotal = bytesTotal,
    bytesPerSecond = bytesPerSecond,
    etaSeconds = etaSeconds,
    currentItem = currentItem,
    errorCode = errorCode,
)
