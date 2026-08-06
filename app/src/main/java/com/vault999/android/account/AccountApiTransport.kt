package com.vault999.android.account

import com.vault999.android.auth.AccountSession
import com.vault999.android.auth.AccountSessionTransport
import com.vault999.android.auth.AccountTransportResult
import com.vault999.android.auth.OpaqueSecret
import com.vault999.android.auth.PendingAuthorization
import com.vault999.android.model.Account
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

data class AuthorizationStart(val authorizeUrl: String, val expiresAt: String?)

class AccountApiTransport(
    originText: String,
    client: OkHttpClient,
) : AccountSessionTransport {
    private val origin: HttpUrl
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    init {
        val parsed = originText.toHttpUrl()
        require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null) {
            "Account service must be an exact HTTPS origin"
        }
        origin = parsed
    }

    suspend fun startAuthorization(pending: PendingAuthorization): AccountTransportResult<AuthorizationStart> {
        val body = buildJsonObject {
            put("redirect_uri", REDIRECT_URI)
            put("state", pending.state)
            put("code_challenge", pending.challenge)
            put("code_challenge_method", "S256")
        }
        return when (val response = request("POST", "/v1/auth/discord/start", body)) {
            is RawResult.Success -> runCatching {
                val url = response.body.requiredString("authorize_url", 2048)
                val parsed = url.toHttpUrl()
                require(parsed.isHttps && parsed.host == "discord.com" && parsed.port == 443 && parsed.username.isEmpty() && parsed.password.isEmpty())
                AccountTransportResult.Success(AuthorizationStart(url, response.body.optionalString("expires_at", 64)))
            }.getOrElse { AccountTransportResult.TransientFailure("invalid_response") }
            RawResult.Rejected -> AccountTransportResult.AuthenticationRejected
            is RawResult.Failure -> AccountTransportResult.TransientFailure(response.code)
        }
    }

    override suspend fun exchange(
        ticket: OpaqueSecret,
        state: OpaqueSecret,
        verifier: OpaqueSecret,
        redirectUri: String,
    ): AccountTransportResult<AccountSession> {
        val body = ticket.use { ticketValue -> state.use { stateValue -> verifier.use { verifierValue ->
            buildJsonObject {
                put("ticket", ticketValue)
                put("state", stateValue)
                put("code_verifier", verifierValue)
                put("redirect_uri", redirectUri)
            }
        } } }
        return sessionResult(request("POST", "/v1/auth/discord/exchange", body))
    }

    override suspend fun refresh(refreshToken: OpaqueSecret): AccountTransportResult<AccountSession> =
        sessionResult(request("POST", "/v1/auth/refresh", refreshToken.use { buildJsonObject { put("refresh_token", it) } }))

    override suspend fun revoke(accessToken: OpaqueSecret): AccountTransportResult<Unit> =
        when (val response = request("POST", "/v1/auth/logout", body = buildJsonObject {}, bearer = accessToken)) {
            is RawResult.Success -> AccountTransportResult.Success(Unit)
            RawResult.Rejected -> AccountTransportResult.AuthenticationRejected
            is RawResult.Failure -> AccountTransportResult.TransientFailure(response.code)
        }

    override suspend fun account(accessToken: OpaqueSecret): AccountTransportResult<Account> =
        when (val response = request("GET", "/v1/me", bearer = accessToken)) {
            is RawResult.Success -> runCatching { AccountTransportResult.Success(accountFromJson(response.body)) }
                .getOrElse { AccountTransportResult.TransientFailure("invalid_response") }
            RawResult.Rejected -> AccountTransportResult.AuthenticationRejected
            is RawResult.Failure -> AccountTransportResult.TransientFailure(response.code)
        }

    private fun sessionResult(response: RawResult): AccountTransportResult<AccountSession> = when (response) {
        is RawResult.Success -> runCatching {
            val access = response.body.requiredString("access_token", 64 * 1024)
            val refresh = response.body.requiredString("refresh_token", 64 * 1024)
            val expires = response.body.requiredString("expires_at", 64).let(::expiryEpochMs)
            val account = accountFromJson(response.body["user"] as? JsonObject ?: error("missing user"))
            AccountTransportResult.Success(AccountSession.create(account, access, refresh, expires))
        }.getOrElse { AccountTransportResult.TransientFailure("invalid_response") }
        RawResult.Rejected -> AccountTransportResult.AuthenticationRejected
        is RawResult.Failure -> AccountTransportResult.TransientFailure(response.code)
    }

    private suspend fun request(method: String, path: String, body: JsonElement? = null, bearer: OpaqueSecret? = null): RawResult = withContext(Dispatchers.IO) {
        val url = origin.newBuilder().encodedPath(path).build()
        val requestBody = body?.toString()?.toRequestBody(JSON_MEDIA)
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        bearer?.use { builder.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(requestBody ?: ByteArray(0).toRequestBody(null))
            else -> error("unsupported method")
        }
        try {
            http.newCall(builder.build()).execute().use { response ->
                if (response.isRedirect) return@withContext RawResult.Failure("redirect_rejected")
                if (response.code == 401) return@withContext RawResult.Rejected
                if (!response.isSuccessful) return@withContext RawResult.Failure("http_${response.code}")
                val length = response.body.contentLength()
                if (length > MAX_JSON_BYTES) return@withContext RawResult.Failure("response_too_large")
                val output = ByteArrayOutputStream()
                val input = response.body.byteStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_JSON_BYTES) return@withContext RawResult.Failure("response_too_large")
                    output.write(buffer, 0, count)
                }
                val parsed = json.parseToJsonElement(output.toString(Charsets.UTF_8.name())).jsonObject
                RawResult.Success(parsed)
            }
        } catch (_: Exception) {
            RawResult.Failure("transport_unavailable")
        }
    }

    private fun JsonObject.requiredString(key: String, max: Int): String = optionalString(key, max)?.takeIf(String::isNotBlank) ?: error("missing $key")
    private fun JsonObject.optionalString(key: String, max: Int): String? = this[key]?.jsonPrimitive?.content?.trim()?.take(max)
    private fun expiryEpochMs(value: String): Long = value.toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000 else it }
        ?: Instant.parse(value).toEpochMilli()

    private sealed interface RawResult {
        data class Success(val body: JsonObject) : RawResult
        data class Failure(val code: String) : RawResult
        data object Rejected : RawResult
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MAX_JSON_BYTES = 512 * 1024
        private const val REDIRECT_URI = "vault999://auth/callback"
    }
}

internal fun accountFromJson(value: JsonObject) = Account(
    id = value.requiredAccountString("id", 128),
    displayName = value.requiredAccountString("display_name", 200),
    discordUsername = value.optionalAccountString("discord_username", 200),
    avatarUrl = value.optionalAccountString("discord_avatar", 2048)
        ?: value.optionalAccountString("avatar_url", 2048),
)

private fun JsonObject.requiredAccountString(key: String, max: Int): String =
    optionalAccountString(key, max)?.takeIf(String::isNotBlank) ?: error("missing $key")

private fun JsonObject.optionalAccountString(key: String, max: Int): String? =
    this[key]?.jsonPrimitive?.content?.trim()?.take(max)
