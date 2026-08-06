package com.vault999.android.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vault999.android.VaultApplication
import com.vault999.android.auth.AccountProjection
import com.vault999.android.auth.CloudLibraryProjection
import com.vault999.android.auth.CloudLibraryRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudLibraryUiState(
    val projection: CloudLibraryProjection = CloudLibraryProjection(emptyList(), emptyList(), emptyList(), emptyList(), false),
    val activeAccountId: String? = null,
    val syncing: Boolean = false,
    val message: String? = null,
)

class CloudLibraryViewModel(
    private val account: AccountRepository,
    private val repository: CloudLibraryRepository?,
    private val scheduler: CloudSyncScheduler,
) : ViewModel() {
    private val mutable = MutableStateFlow(CloudLibraryUiState())
    val state: StateFlow<CloudLibraryUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            account.state.collectLatest { accountState ->
                val activeId = (accountState.projection as? AccountProjection.SignedIn)?.account?.id
                if (repository == null) {
                    mutable.value = CloudLibraryUiState(activeAccountId = activeId)
                } else if (activeId == null) {
                    mutable.value = CloudLibraryUiState(projection = repository.projection(null))
                } else {
                    mutable.update { it.copy(activeAccountId = activeId, syncing = true, message = null) }
                    load(activeId)
                    val refreshed = runCatching { repository.refresh(activeId) }.getOrDefault(false)
                    runCatching { repository.flushReady(activeId) }
                    load(activeId)
                    scheduler.schedule(activeId)
                    mutable.update { it.copy(syncing = false, message = if (refreshed) null else "Cloud library is showing saved and pending account data.") }
                }
            }
        }
    }

    fun setLike(songId: Long, liked: Boolean) = mutate { accountId -> repository?.setLike(accountId, songId, liked) }

    fun migrateLegacyLikes(migrations: Map<Long, Long>) = mutate { accountId ->
        migrations.forEach { (legacyId, canonicalId) ->
            if (legacyId != canonicalId) {
                repository?.setLike(accountId, legacyId, false)
                repository?.setLike(accountId, canonicalId, true)
            }
        }
    }

    fun createPlaylist(name: String, description: String = "") = mutate { accountId ->
        repository?.createPlaylist(accountId, name, description)
    }

    fun updatePlaylist(localId: String, name: String, description: String) = mutate { accountId ->
        repository?.updatePlaylist(accountId, localId, name, description)
    }

    fun deletePlaylist(localId: String) = mutate { accountId -> repository?.deletePlaylist(accountId, localId) }

    fun setPlaylistSong(localId: String, songId: Long, included: Boolean) = mutate { accountId ->
        repository?.setPlaylistSong(accountId, localId, songId, included)
    }

    fun reorderPlaylist(localId: String, songIds: List<Long>) = mutate { accountId ->
        repository?.reorderPlaylist(accountId, localId, songIds)
    }

    fun retrySync() {
        val accountId = mutable.value.activeAccountId ?: return
        scheduler.schedule(accountId)
        viewModelScope.launch {
            mutable.update { it.copy(syncing = true, message = null) }
            runCatching { repository?.refresh(accountId) }
            runCatching { repository?.flushReady(accountId) }
            load(accountId)
            mutable.update { it.copy(syncing = false) }
        }
    }

    private fun mutate(block: suspend (String) -> Any?) {
        val accountId = mutable.value.activeAccountId ?: return
        viewModelScope.launch {
            runCatching { block(accountId) }
                .onSuccess {
                    load(accountId)
                    scheduler.schedule(accountId)
                }
                .onFailure { mutable.update { state -> state.copy(message = "The cloud change could not be queued safely.") } }
        }
    }

    private suspend fun load(accountId: String) {
        repository?.let { cloud -> mutable.update { it.copy(projection = cloud.projection(accountId), activeAccountId = accountId) } }
    }

    companion object {
        fun factory(account: AccountRepository, repository: CloudLibraryRepository?, scheduler: CloudSyncScheduler): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CloudLibraryViewModel(account, repository, scheduler) as T
            }
    }
}

class CloudSyncScheduler(private val context: Context) {
    fun schedule(accountId: String) {
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setInputData(Data.Builder().putString(KEY_ACCOUNT_ID, accountId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("vault-cloud-${accountId.hashCode()}", ExistingWorkPolicy.REPLACE, request)
    }

    companion object { const val KEY_ACCOUNT_ID = "account_id" }
}

class CloudSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val accountId = inputData.getString(CloudSyncScheduler.KEY_ACCOUNT_ID) ?: return Result.failure()
        val graph = (applicationContext as VaultApplication).graph
        val activeId = (graph.accountRepository.state.value.projection as? AccountProjection.SignedIn)?.account?.id
        if (activeId != accountId) return Result.success()
        val repository = graph.cloudLibraryRepository ?: return Result.success()
        val summary = runCatching { repository.flushReady(accountId) }.getOrElse { return Result.retry() }
        val listening = runCatching { graph.listeningSyncRepository?.sync(accountId) }.getOrElse { return Result.retry() }
        return if (summary.deferred > 0 || listening?.needsContinuation == true) Result.retry() else Result.success()
    }
}
