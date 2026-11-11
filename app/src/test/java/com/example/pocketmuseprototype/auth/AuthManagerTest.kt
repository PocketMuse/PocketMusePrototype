package com.example.pocketmuseprototype.auth

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthManagerTest {
    private val profile = CivitaiProfile("42", "creator")
    private val issued = TokenPair("generated-access-1", "generated-refresh-1", 3600_000)
    private val rotated = TokenPair("generated-access-2", "generated-refresh-2", 7200_000)

    @Test
    fun loginChecksStateAndConsumesResultOnlyOnce() = runTest {
        val store = MemoryStore()
        val remote = FakeRemote()
        val auth = AuthManager(this, store, remote) { 0 }
        assertSame(AuthState.Initializing, auth.state.value)
        advanceUntilIdle()
        assertEquals(AuthState.Disconnected(), auth.state.value)
        assertEquals(true, auth.beginAuthorization("attempt-1"))
        assertEquals(false, auth.beginAuthorization("attempt-2"))
        auth.finishAuthorization(BrowserResult.Code("unrelated", "code", "verifier"))
        advanceUntilIdle()
        assertEquals(AuthState.Error(AuthOperation.Login), auth.state.value)
        assertEquals(0, remote.exchanges)

        assertEquals(true, auth.beginAuthorization("attempt-3"))
        val response = BrowserResult.Code("attempt-3", "code", "verifier")
        auth.finishAuthorization(response)
        auth.finishAuthorization(response)
        advanceUntilIdle()
        assertEquals(AuthState.Connected(profile), auth.state.value)
        assertEquals(1, remote.exchanges)
        assertEquals(issued, store.tokens)
        val restarted = AuthManager(this, store, remote) { 0 }
        advanceUntilIdle()
        assertEquals(AuthState.Connected(profile), restarted.state.value)
        assertEquals(issued.accessToken, restarted.validAccessToken())
        assertEquals(0, remote.refreshes)
    }

    @Test
    fun cancellationAndBrowserErrorsDoNotExchangeACode() = runTest {
        val remote = FakeRemote()
        val auth = AuthManager(this, MemoryStore(), remote) { 0 }
        advanceUntilIdle()
        auth.beginAuthorization("attempt-1")
        auth.finishAuthorization(BrowserResult.Canceled)
        assertEquals(AuthState.Disconnected(), auth.state.value)
        auth.beginAuthorization("attempt-2")
        auth.finishAuthorization(BrowserResult.Failed)
        assertEquals(AuthState.Error(AuthOperation.Login), auth.state.value)
        assertEquals(0, remote.exchanges)
    }

    @Test
    fun pendingBrowserResultCanFinishAfterProcessRecreation() = runTest {
        val remote = FakeRemote()
        val auth = AuthManager(this, MemoryStore(), remote) { 0 }
        auth.restoreAuthorization("pending")
        advanceUntilIdle()
        assertSame(AuthState.Connecting, auth.state.value)
        auth.finishAuthorization(BrowserResult.Code("pending", "code", "verifier"))
        advanceUntilIdle()
        assertEquals(AuthState.Connected(profile), auth.state.value)
    }

    @Test
    fun concurrentRequestsRefreshOnceAndPersistTheRotatedPairBeforeReturning() = runTest {
        var now = 0L
        val store = MemoryStore(issued)
        val remote = FakeRemote()
        val auth = AuthManager(this, store, remote) { now }
        advanceUntilIdle()
        now = issued.expiresAtMillis - 1
        val reply = CompletableDeferred<TokenPair>()
        remote.refreshResponse = { reply.await() }
        val abandoned = async { auth.validAccessToken() }
        advanceUntilIdle()
        abandoned.cancel()
        val second = async { auth.validAccessToken() }
        val third = async { auth.validAccessToken() }
        advanceUntilIdle()
        assertEquals(1, remote.refreshes)
        assertEquals(issued, store.tokens)
        reply.complete(rotated)
        assertEquals(rotated.accessToken, second.await())
        assertEquals(rotated.accessToken, third.await())
        assertEquals(rotated, store.tokens)
        assertEquals(listOf(issued.refreshToken), remote.refreshedTokens)
    }

    @Test
    fun temporaryRefreshFailureKeepsSessionAndInvalidGrantRequiresLogin() = runTest {
        val store = MemoryStore(issued)
        val remote = FakeRemote().apply { refreshResponse = { throw IOException() } }
        val auth = AuthManager(this, store, remote) { issued.expiresAtMillis }
        advanceUntilIdle()
        assertEquals(AuthState.Error(AuthOperation.Session, hasSession = true), auth.state.value)
        assertEquals(issued, store.tokens)
        remote.refreshResponse = { throw SessionRejected() }
        auth.retrySession()
        advanceUntilIdle()
        assertSame(AuthState.ReauthenticationRequired, auth.state.value)
        assertNull(store.tokens)
        assertNull(auth.validAccessToken())
    }

    @Test
    fun profileFailureKeepsTheIssuedTokensAndRetryDoesNotRepeatExchange() = runTest {
        val store = MemoryStore()
        val remote = FakeRemote().apply { profileResponse = { throw IOException() } }
        val auth = AuthManager(this, store, remote) { 0 }
        advanceUntilIdle()
        auth.beginAuthorization("attempt")
        auth.finishAuthorization(BrowserResult.Code("attempt", "code", "verifier"))
        advanceUntilIdle()
        assertEquals(issued, store.tokens)
        assertEquals(AuthState.Error(AuthOperation.Session, hasSession = true), auth.state.value)
        remote.profileResponse = { profile }
        auth.retrySession()
        advanceUntilIdle()
        assertEquals(AuthState.Connected(profile), auth.state.value)
        assertEquals(1, remote.exchanges)
    }

    @Test
    fun unauthorizedProfileIsRetriedOnceWithThePersistedRotatedToken() = runTest {
        val store = MemoryStore(issued)
        val remote = FakeRemote()
        val presented = mutableListOf<String>()
        remote.profileResponse = { token ->
            presented += token
            if (token == issued.accessToken) throw SessionRejected()
            assertEquals(rotated, store.tokens)
            profile
        }
        val auth = AuthManager(this, store, remote) { 0 }
        advanceUntilIdle()
        assertEquals(AuthState.Connected(profile), auth.state.value)
        assertEquals(listOf(issued.accessToken, rotated.accessToken), presented)
        assertEquals(1, remote.refreshes)
    }

    @Test
    fun disconnectClearsSessionEvenWhenRemoteRevocationFails() = runTest {
        val store = MemoryStore(issued)
        val remote = FakeRemote().apply { revokeResponse = { throw IOException() } }
        val auth = AuthManager(this, store, remote) { 0 }
        advanceUntilIdle()
        auth.disconnect()
        advanceUntilIdle()
        assertEquals(issued.refreshToken, remote.revokedToken)
        assertNull(store.tokens)
        assertEquals(AuthState.Disconnected(revocationUnconfirmed = true), auth.state.value)
        assertNull(auth.validAccessToken())
    }

    @Test
    fun failedRotationWriteNeverReturnsAnUnpersistedTokenOrReusesOldRefreshToken() = runTest {
        val store = MemoryStore(issued).apply { failWrites = true }
        val remote = FakeRemote()
        val auth = AuthManager(this, store, remote) { issued.expiresAtMillis }
        advanceUntilIdle()
        assertNull(auth.validAccessToken())
        assertEquals(issued, store.tokens)
        store.failWrites = false
        assertEquals(rotated.accessToken, auth.validAccessToken())
        assertEquals(rotated, store.tokens)
        assertEquals(1, remote.refreshes)
    }

    private class MemoryStore(var tokens: TokenPair? = null) : SessionStore {
        var failWrites = false
        override suspend fun read() = tokens
        override suspend fun write(tokens: TokenPair?) {
            if (failWrites) throw IOException()
            this.tokens = tokens
        }
    }

    private inner class FakeRemote : AuthRemote {
        var exchanges = 0
        val refreshedTokens = mutableListOf<String>()
        val refreshes get() = refreshedTokens.size
        var revokedToken: String? = null
        var refreshResponse: suspend () -> TokenPair = { rotated }
        var profileResponse: suspend (String) -> CivitaiProfile = { profile }
        var revokeResponse: suspend () -> Unit = {}

        override suspend fun exchange(result: BrowserResult.Code): TokenPair {
            exchanges++
            return issued
        }

        override suspend fun refresh(refreshToken: String): TokenPair {
            refreshedTokens += refreshToken
            return refreshResponse()
        }

        override suspend fun userInfo(accessToken: String) = profileResponse(accessToken)

        override suspend fun revoke(refreshToken: String) {
            revokedToken = refreshToken
            revokeResponse()
        }
    }
}
