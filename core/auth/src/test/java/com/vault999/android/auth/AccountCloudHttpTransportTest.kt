package com.vault999.android.auth

import com.vault999.android.network.ExactOrigin
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.vault999.android.model.ListeningEvent

class AccountCloudHttpTransportTest {
    @Test fun `normalizes owned playlist envelope and sends opaque bearer`() = runBlocking {
        var authorization = ""
        val transport = transport { chain ->
            authorization = chain.request().header("Authorization").orEmpty()
            response(
                chain,
                200,
                """{"playlists":[{"id":"cloud-1","client_migration_id":"migration-1","name":"Night Drive","description":"Mix","cover_url":"https://cdn.example/cover.jpg","song_ids":[1,2],"revision":"rev-1","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T00:00:00Z"}]}""",
            )
        }

        val result = transport.playlists(OpaqueSecret.from("access-secret")) as CloudCallResult.Success

        assertEquals("Bearer access-secret", authorization)
        assertEquals(listOf(1L, 2L), result.value.single().songIds)
        assertFalse(result.toString().contains("access-secret"))
    }

    @Test fun `mutation uses exact route idempotency and no delete body`() = runBlocking {
        var method = ""
        var path = ""
        var key = ""
        var bodyLength = -1L
        val transport = transport { chain ->
            val request = chain.request()
            method = request.method
            path = request.url.encodedPath
            key = request.header("Idempotency-Key").orEmpty()
            bodyLength = request.body?.contentLength() ?: 0
            response(chain, 200, "{}")
        }
        val mutation = CloudMutation(
            "00000000-0000-4000-8000-000000000001",
            "account-1",
            CloudMutationOperation.SET_LIKE,
            songId = 42,
            desired = false,
            nextAttemptAtEpochMs = 1,
            createdAtEpochMs = 1,
        )

        assertTrue(transport.execute(OpaqueSecret.from("token"), mutation) is CloudCallResult.Success)
        assertEquals("DELETE", method)
        assertEquals("/v1/library/likes/42", path)
        assertEquals(mutation.idempotencyKey, key)
        assertEquals(0, bodyLength)
    }

    @Test fun `conflict is returned once without automatic retry`() = runBlocking {
        val calls = AtomicInteger()
        val transport = transport { chain ->
            calls.incrementAndGet()
            response(chain, 409, """{"code":"revision_conflict","message":"stale"}""")
        }
        val mutation = CloudMutation(
            "00000000-0000-4000-8000-000000000002",
            "account-1",
            CloudMutationOperation.REORDER_PLAYLIST,
            playlistLocalId = "local-1",
            playlistCloudId = "cloud-1",
            songIds = listOf(2, 1),
            baseRevision = "old",
            nextAttemptAtEpochMs = 1,
            createdAtEpochMs = 1,
        )

        assertEquals(CloudCallResult.Conflict, transport.execute(OpaqueSecret.from("token"), mutation))
        assertEquals(1, calls.get())
    }

    @Test fun `rejects oversized account response`() = runBlocking {
        val transport = transport { chain ->
            val body = ByteArray((2 * 1024 * 1024) + 1).toResponseBody("application/json".toMediaType())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }

        val result = transport.likes(OpaqueSecret.from("token"))

        assertEquals(CloudCallResult.Rejected("invalid_response", 200), result)
    }

    @Test fun `listening upload acknowledges only explicit event ids`() = runBlocking {
        var requestBody = ""
        val transport = transport { chain ->
            requestBody = chain.request().body?.let { body -> okio.Buffer().also(body::writeTo).readUtf8() }.orEmpty()
            response(chain, 200, """{"acknowledged_event_ids":["00000000-0000-4000-8000-000000000111"]}""")
        }
        val event = ListeningEvent("00000000-0000-4000-8000-000000000111", 9, 1_767_225_600_000, 30, 120, "catalog")

        val result = transport.uploadListeningEvents(OpaqueSecret.from("token"), listOf(event)) as CloudCallResult.Success

        assertEquals(setOf(event.id), result.value)
        assertTrue(requestBody.contains("\"songId\":9"))
        assertTrue(requestBody.contains("2026-01-01T00:00:00Z"))
    }

    @Test fun `listening page is bounded and normalized`() = runBlocking {
        val transport = transport { chain ->
            response(chain, 200, """{"events":[{"id":"00000000-0000-4000-8000-000000000112","songId":7,"timestamp":"2026-01-01T00:00:00Z","listenedSeconds":30,"durationSeconds":90,"source":"playlist"}],"next_cursor":"next-1"}""")
        }

        val result = transport.listeningEvents(OpaqueSecret.from("token")) as CloudCallResult.Success

        assertEquals(7L, result.value.events.single().songId)
        assertEquals("next-1", result.value.nextCursor)
    }

    private fun transport(interceptor: Interceptor): AccountCloudHttpTransport = AccountCloudHttpTransport(
        OkHttpClient.Builder().addInterceptor(interceptor).build(),
        ExactOrigin("https://account.example".toHttpUrl()),
    )

    private fun response(chain: Interceptor.Chain, code: Int, json: String): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(json.toResponseBody("application/json".toMediaType()))
        .build()
}
