package com.vault999.android.account

import com.vault999.android.auth.AccountCloudHttpTransport
import com.vault999.android.auth.AuthSessionManager
import com.vault999.android.auth.CloudCallResult
import com.vault999.android.auth.SessionAccess
import com.vault999.android.database.ListeningEventEntity
import com.vault999.android.database.SyncDao
import com.vault999.android.model.ListeningEvent

data class ListeningSyncSummary(
    val acknowledged: Int,
    val downloaded: Int,
    val needsContinuation: Boolean,
)

class ListeningSyncRepository(
    private val dao: SyncDao,
    private val sessions: AuthSessionManager,
    private val transport: AccountCloudHttpTransport,
) {
    suspend fun sync(accountId: String): ListeningSyncSummary {
        val access = sessions.accessSession() as? SessionAccess.Granted ?: return ListeningSyncSummary(0, 0, true)
        if (access.session.account.id != accountId) return ListeningSyncSummary(0, 0, false)
        var session = access.session
        var acknowledged = 0
        val pending = dao.pendingEvents(500)
        if (pending.isNotEmpty()) {
            var uploaded = session.useAccessTokenSuspending { transport.uploadListeningEvents(it, pending.map(ListeningEventEntity::model)) }
            if (uploaded is CloudCallResult.Unauthorized) {
                val refreshed = sessions.refreshAfterUnauthorized(session) as? SessionAccess.Granted
                if (refreshed == null || refreshed.session.account.id != accountId) return ListeningSyncSummary(0, 0, true)
                session = refreshed.session
                uploaded = session.useAccessTokenSuspending { transport.uploadListeningEvents(it, pending.map(ListeningEventEntity::model)) }
            }
            val ids = (uploaded as? CloudCallResult.Success)?.value.orEmpty()
            if (ids.isNotEmpty()) {
                dao.acknowledgeEvents(ids.toList())
                acknowledged = ids.size
            }
            if (uploaded !is CloudCallResult.Success) return ListeningSyncSummary(acknowledged, 0, true)
        }

        val seenCursors = linkedSetOf<String>()
        var cursor: String? = null
        var downloaded = 0
        repeat(MAX_PAGES_PER_RUN) {
            var page = session.useAccessTokenSuspending { transport.listeningEvents(it, cursor) }
            if (page is CloudCallResult.Unauthorized) {
                val refreshed = sessions.refreshAfterUnauthorized(session) as? SessionAccess.Granted
                if (refreshed == null || refreshed.session.account.id != accountId) return ListeningSyncSummary(acknowledged, downloaded, true)
                session = refreshed.session
                page = session.useAccessTokenSuspending { transport.listeningEvents(it, cursor) }
            }
            val value = (page as? CloudCallResult.Success)?.value ?: return ListeningSyncSummary(acknowledged, downloaded, true)
            dao.insertEvents(value.events.map { it.entity(accountId) })
            downloaded += value.events.size
            val next = value.nextCursor ?: return ListeningSyncSummary(acknowledged, downloaded, false)
            check(seenCursors.add(next)) { "Listening cursor cycle detected" }
            cursor = next
        }
        return ListeningSyncSummary(acknowledged, downloaded, needsContinuation = cursor != null)
    }

    private companion object { const val MAX_PAGES_PER_RUN = 20 }
}

private fun ListeningEventEntity.model() = ListeningEvent(
    id, songId, playedAtEpochMs, listenedSeconds, durationSeconds, source, acknowledged,
)

private fun ListeningEvent.entity(accountId: String) = ListeningEventEntity(
    id = id,
    songId = songId,
    playedAtEpochMs = playedAtEpochMs,
    listenedSeconds = listenedSeconds,
    durationSeconds = durationSeconds,
    source = source,
    acknowledged = true,
    accountId = accountId,
)
