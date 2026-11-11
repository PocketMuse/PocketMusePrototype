package com.example.pocketmuseprototype.auth

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.browser.auth.AuthTabIntent
import androidx.core.net.toUri
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest

internal object CivitaiOAuth {
    private val redirect = "https://pocketmuse.github.io/oauth/callback/".toUri()
    private val configuration = AuthorizationServiceConfiguration(
        (CIVITAI_OAUTH_BASE + "authorize").toUri(),
        (CIVITAI_OAUTH_BASE + "token").toUri()
    )

    fun authorizationRequest(): AuthorizationRequest = AuthorizationRequest.Builder(
        configuration, CIVITAI_CLIENT_ID, ResponseTypeValues.CODE, redirect
    ).setScope("ModelsRead").setNonce(null).build()

    fun verifiedRedirect(uri: Uri): Uri? {
        if (!uri.isHierarchical || uri.scheme != redirect.scheme ||
            uri.encodedAuthority != redirect.encodedAuthority || uri.encodedPath != redirect.encodedPath ||
            uri.fragment != null
        ) return null
        val state = uri.getQueryParameters("state").singleOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val codes = uri.getQueryParameters("code")
        val errors = uri.getQueryParameters("error")
        // Pass only fields AppAuth needs for this code flow. Its parser also reads implicit-flow fields.
        val callback = redirect.buildUpon().appendQueryParameter("state", state)
        val code = codes.singleOrNull()
        val error = errors.singleOrNull()
        return when {
            !code.isNullOrBlank() && errors.isEmpty() -> callback.appendQueryParameter("code", code).build()
            !error.isNullOrBlank() && codes.isEmpty() -> callback.appendQueryParameter("error", error).build()
            else -> null
        }
    }

    fun browserResult(result: ActivityResult, expectedState: String): BrowserResult {
        val data = result.data
        if (result.resultCode == Activity.RESULT_CANCELED && data == null) return BrowserResult.Canceled
        if (data == null) return BrowserResult.Failed
        val error = AuthorizationException.fromIntent(data)
        if (error == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW) return BrowserResult.Canceled

        val uri = data.data ?: return BrowserResult.Failed
        if (verifiedRedirect(uri) != uri || uri.getQueryParameter("state") != expectedState) return BrowserResult.Failed
        if (error == AuthorizationException.AuthorizationRequestErrors.ACCESS_DENIED) return BrowserResult.Canceled
        if (error != null || result.resultCode != Activity.RESULT_OK) return BrowserResult.Failed

        val response = try {
            AuthorizationResponse.fromIntent(data)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return BrowserResult.Failed
        val state = response.state ?: return BrowserResult.Failed
        val code = response.authorizationCode?.takeIf { it.isNotBlank() } ?: return BrowserResult.Failed
        val verifier = response.request.codeVerifier ?: return BrowserResult.Failed
        if (state != expectedState || state != response.request.state ||
            response.request.codeVerifierChallengeMethod != "S256" ||
            uri.getQueryParameters("code").singleOrNull() != code
        ) return BrowserResult.Failed
        return BrowserResult.Code(state, code, verifier)
    }

    fun authTabResult(resultCode: Int, uri: Uri?, expectedState: String, verifier: String): BrowserResult {
        if (resultCode == AuthTabIntent.RESULT_CANCELED) return BrowserResult.Canceled
        if (resultCode != AuthTabIntent.RESULT_OK) return BrowserResult.Failed
        val callback = uri?.let(::verifiedRedirect) ?: return BrowserResult.Failed
        if (callback.getQueryParameter("state") != expectedState) return BrowserResult.Failed
        if (callback.getQueryParameter("error") == "access_denied") return BrowserResult.Canceled
        val code = callback.getQueryParameter("code") ?: return BrowserResult.Failed
        return BrowserResult.Code(expectedState, code, verifier)
    }

    fun tokenRequest() = TokenRequest.Builder(configuration, CIVITAI_CLIENT_ID)

    fun exchangeRequest(result: BrowserResult.Code): TokenRequest = tokenRequest()
        .setRedirectUri(redirect)
        .setAuthorizationCode(result.code)
        .setCodeVerifier(result.verifier)
        .build()
}

internal class AppAuthRemote(
    private val context: Context,
    private val accountApi: CivitaiAccountApi = CivitaiAccountApi()
) : AuthRemote {
    private val clientConfiguration = AppAuthConfiguration.Builder()
        .setBrowserMatcher { false }
        .build()

    override suspend fun exchange(result: BrowserResult.Code): TokenPair =
        requestTokens(CivitaiOAuth.exchangeRequest(result))

    override suspend fun refresh(refreshToken: String): TokenPair =
        requestTokens(CivitaiOAuth.tokenRequest().setRefreshToken(refreshToken).build())

    override suspend fun userInfo(accessToken: String) = accountApi.userInfo(accessToken)

    override suspend fun revoke(refreshToken: String) = accountApi.revoke(refreshToken)

    private suspend fun requestTokens(request: TokenRequest): TokenPair {
        val service = AuthorizationService(context, clientConfiguration)
        try {
            return suspendCancellableCoroutine { continuation ->
                service.performTokenRequest(request) { response, error ->
                    val access = response?.accessToken
                    val refresh = response?.refreshToken
                    val expiry = response?.accessTokenExpirationTime
                    when {
                        error == AuthorizationException.TokenRequestErrors.INVALID_GRANT ->
                            continuation.resumeWithException(SessionRejected())
                        response?.tokenType.equals("Bearer", ignoreCase = true) &&
                            !access.isNullOrEmpty() && access.all { it in '!'..'~' } && !refresh.isNullOrBlank() &&
                            expiry != null && expiry > System.currentTimeMillis() ->
                            continuation.resume(TokenPair(access, refresh, expiry))
                        else -> continuation.resumeWithException(IOException("Civitai token request failed"))
                    }
                }
            }
        } finally {
            service.dispose()
        }
    }
}
