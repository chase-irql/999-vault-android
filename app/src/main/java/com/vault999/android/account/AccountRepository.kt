package com.vault999.android.account

import android.content.Context
import com.vault999.android.auth.AccountProjection
import com.vault999.android.auth.AccountTransportResult
import com.vault999.android.auth.AtomicEncryptedSessionStore
import com.vault999.android.auth.AuthSessionManager
import com.vault999.android.auth.AuthorizationStateMachine
import com.vault999.android.auth.CallbackConsumption
import com.vault999.android.auth.KeystoreSessionEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient

data class AccountUiState(
    val configured: Boolean = false,
    val projection: AccountProjection = AccountProjection.SignedOut,
    val working: Boolean = false,
    val browserUrl: String? = null,
    val message: String? = null,
)

class AccountRepository(
    context: Context,
    http: OkHttpClient,
    origin: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val transport = origin.takeIf(String::isNotBlank)?.let { AccountApiTransport(it, http) }
    private val stateMachine = AuthorizationStateMachine(
        EncryptedPendingAuthorizationStore(context.filesDir.toPath().resolve("pending-account-auth.bin")),
    )
    private val manager = transport?.let {
        AuthSessionManager(
            AtomicEncryptedSessionStore(
                context.filesDir.toPath().resolve("account-session.bin"),
                KeystoreSessionEnvelope(),
            ),
            it,
            now,
        )
    }
    private val mutable = MutableStateFlow(AccountUiState(configured = transport != null))
    val state: StateFlow<AccountUiState> = mutable.asStateFlow()
    internal val authSessionManager: AuthSessionManager? get() = manager

    suspend fun restore() {
        val projection = manager?.restoreAtStartup() ?: AccountProjection.SignedOut
        mutable.update { it.copy(projection = projection, working = false) }
    }

    suspend fun startSignIn() {
        val api = transport ?: run {
            mutable.update { it.copy(message = "Account service is not configured. Signed-out features remain available.") }
            return
        }
        mutable.update { it.copy(working = true, message = null) }
        val pending = runCatching { stateMachine.begin(now()) }.getOrElse {
            mutable.update { state -> state.copy(working = false, message = "Secure sign-in state could not be saved.") }
            return
        }
        when (val result = api.startAuthorization(pending)) {
            is AccountTransportResult.Success -> mutable.update { it.copy(working = false, browserUrl = result.value.authorizeUrl) }
            else -> {
                runCatching { stateMachine.cancel() }
                mutable.update { it.copy(working = false, message = "Sign-in service is unavailable.") }
            }
        }
    }

    fun browserOpened() { mutable.update { it.copy(browserUrl = null) } }

    suspend fun consumeCallback(uri: String?) {
        if (uri.isNullOrBlank() || !uri.startsWith("vault999://auth/callback")) return
        val sessionManager = manager ?: return
        mutable.update { it.copy(working = true, message = null) }
        when (val consumed = stateMachine.consume(uri, now())) {
            is CallbackConsumption.Accepted -> when (val result = sessionManager.completeSignIn(consumed)) {
                is AccountTransportResult.Success -> mutable.update { it.copy(projection = result.value, working = false, message = "Signed in securely.") }
                AccountTransportResult.AuthenticationRejected -> mutable.update { it.copy(working = false, message = "This sign-in callback was rejected or already used.") }
                is AccountTransportResult.TransientFailure -> mutable.update { it.copy(working = false, message = "The account service could not finish sign-in.") }
            }
            else -> mutable.update { it.copy(working = false, message = "The sign-in callback was invalid, expired, or already used.") }
        }
    }

    suspend fun logout() {
        mutable.update { it.copy(working = true, message = null) }
        manager?.logout()
        mutable.update { it.copy(projection = AccountProjection.SignedOut, working = false, message = "Signed out. Device music was preserved.") }
    }
}
