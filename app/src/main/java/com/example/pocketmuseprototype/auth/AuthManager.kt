package com.example.pocketmuseprototype.auth

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

internal data class CivitaiProfile(val id: String, val username: String)

internal sealed interface AuthState {
    data object Initializing : AuthState
    data class Disconnected(val revocationUnconfirmed: Boolean = false) : AuthState
    data object Connecting : AuthState
    data class Connected(val profile: CivitaiProfile) : AuthState
    data object ReauthenticationRequired : AuthState
    data object Disconnecting : AuthState
    data class Error(val operation: AuthOperation, val hasSession: Boolean = false) : AuthState
}

internal enum class AuthOperation { Restore, Login, Session, Disconnect }

internal sealed interface BrowserResult {
    data object Canceled : BrowserResult
    data object Failed : BrowserResult
    class Code(val state: String, val code: String, val verifier: String) : BrowserResult
}

@Serializable
internal data class TokenPair(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long) {
    override fun toString() = "TokenPair(expiresAtMillis=$expiresAtMillis)"
}

internal interface SessionStore {
    suspend fun read(): TokenPair?
    suspend fun write(tokens: TokenPair?)
}

internal interface AuthRemote {
    suspend fun exchange(result: BrowserResult.Code): TokenPair
    suspend fun refresh(refreshToken: String): TokenPair
    suspend fun userInfo(accessToken: String): CivitaiProfile
    suspend fun revoke(refreshToken: String)
}

internal class SessionRejected : IOException()

/** Owns one Civitai session. Browser and UI events run on the same main thread as [scope]. */
internal class AuthManager(
    private val scope: CoroutineScope,
    private val store: SessionStore,
    private val remote: AuthRemote,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Initializing)
    val state = mutableState.asStateFlow()

    private val sessionLock = Mutex()
    private var tokens: TokenPair? = null
    private var needsWrite = false
    private var profile: CivitaiProfile? = null

    var pendingAuthorizationState: String? = null
        private set

    init {
        scope.launch { sessionLock.withLock { restore() } }
    }

    fun restoreAuthorization(savedState: String?) {
        if (savedState != null && state.value == AuthState.Initializing) {
            pendingAuthorizationState = savedState
            mutableState.value = AuthState.Connecting
        }
    }

    fun beginAuthorization(expectedState: String): Boolean {
        val current = state.value
        if (current !is AuthState.Disconnected && current != AuthState.ReauthenticationRequired &&
            current != AuthState.Error(AuthOperation.Login)
        ) return false
        pendingAuthorizationState = expectedState
        mutableState.value = AuthState.Connecting
        return true
    }

    fun finishAuthorization(result: BrowserResult) {
        val expectedState = pendingAuthorizationState ?: return
        pendingAuthorizationState = null
        when {
            result == BrowserResult.Canceled -> mutableState.value = AuthState.Disconnected()
            result is BrowserResult.Code && result.state == expectedState -> scope.launch {
                sessionLock.withLock {
                    val issued = try {
                        remote.exchange(result)
                    } catch (_: IOException) {
                        mutableState.value = AuthState.Error(AuthOperation.Login)
                        return@withLock
                    }
                    tokens = issued
                    needsWrite = true
                    updateSession()
                }
            }
            else -> mutableState.value = AuthState.Error(AuthOperation.Login)
        }
    }

    fun onForeground() {
        scope.launch {
            sessionLock.withLock {
                if (state.value is AuthState.Connected) updateSession()
            }
        }
    }

    fun retrySession() {
        scope.launch {
            sessionLock.withLock {
                when ((state.value as? AuthState.Error)?.operation) {
                    AuthOperation.Restore -> {
                        mutableState.value = AuthState.Initializing
                        restore()
                    }
                    AuthOperation.Session -> updateSession()
                    AuthOperation.Disconnect -> disconnectSession()
                    else -> Unit
                }
            }
        }
    }

    /** Returns a usable Bearer credential or null. A started rotation finishes even if the caller cancels. */
    suspend fun validAccessToken(): String? = scope.async {
        sessionLock.withLock {
            if (state.value == AuthState.Error(AuthOperation.Disconnect, hasSession = true)) null
            else updateSession()?.accessToken
        }
    }.await()

    fun disconnect() {
        scope.launch {
            sessionLock.withLock { disconnectSession() }
        }
    }

    private suspend fun restore() {
        if (state.value != AuthState.Initializing) return
        val saved = try {
            store.read()
        } catch (_: SessionRejected) {
            if (state.value == AuthState.Initializing) {
                try {
                    invalidateSession()
                } catch (_: IOException) {
                    mutableState.value = AuthState.Error(AuthOperation.Restore)
                }
            }
            return
        } catch (_: IOException) {
            if (state.value == AuthState.Initializing) mutableState.value = AuthState.Error(AuthOperation.Restore)
            return
        }
        if (state.value != AuthState.Initializing) return
        tokens = saved
        if (saved == null) mutableState.value = AuthState.Disconnected()
        else updateSession()
    }

    private suspend fun updateSession(): TokenPair? {
        var current = tokens ?: return null
        try {
            persistPendingTokens()
            if (current.expiresAtMillis - nowMillis() <= REFRESH_WINDOW_MILLIS) current = rotate(current)
            if (profile == null) {
                profile = try {
                    remote.userInfo(current.accessToken)
                } catch (_: SessionRejected) {
                    current = rotate(current)
                    remote.userInfo(current.accessToken)
                }
            }
            mutableState.value = AuthState.Connected(checkNotNull(profile))
            return current
        } catch (_: SessionRejected) {
            try {
                invalidateSession()
            } catch (_: IOException) {
                mutableState.value = AuthState.Error(AuthOperation.Session, hasSession = true)
            }
        } catch (_: IOException) {
            mutableState.value = AuthState.Error(AuthOperation.Session, hasSession = true)
        }
        return null
    }

    private suspend fun rotate(current: TokenPair): TokenPair =
        remote.refresh(current.refreshToken).also { replaceTokens(it) }

    private suspend fun replaceTokens(replacement: TokenPair) {
        tokens = replacement
        needsWrite = true
        persistPendingTokens()
    }

    private suspend fun persistPendingTokens() {
        if (!needsWrite) return
        store.write(tokens)
        needsWrite = false
    }

    private suspend fun invalidateSession() {
        clearSession()
        mutableState.value = AuthState.ReauthenticationRequired
    }

    private suspend fun disconnectSession() {
        val current = tokens ?: return
        mutableState.value = AuthState.Disconnecting
        val revocationUnconfirmed = try {
            remote.revoke(current.refreshToken)
            false
        } catch (_: IOException) {
            true
        }
        try {
            clearSession()
            mutableState.value = AuthState.Disconnected(revocationUnconfirmed)
        } catch (_: IOException) {
            mutableState.value = AuthState.Error(AuthOperation.Disconnect, hasSession = true)
        }
    }

    private suspend fun clearSession() {
        store.write(null)
        tokens = null
        profile = null
        needsWrite = false
    }

    private companion object {
        const val REFRESH_WINDOW_MILLIS = 60_000L
    }
}
