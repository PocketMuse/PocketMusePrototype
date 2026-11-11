package com.example.pocketmuseprototype

import android.app.Activity
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pocketmuseprototype.auth.BrowserResult
import com.example.pocketmuseprototype.auth.CivitaiOAuth
import com.example.pocketmuseprototype.auth.KeystoreSessionStore
import com.example.pocketmuseprototype.auth.TokenPair
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import net.openid.appauth.AuthorizationResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CivitaiAuthTest {
    @Test
    fun appAuthBuildsCodeAndS256RequestsAndValidatesBrowserResult() {
        val request = CivitaiOAuth.authorizationRequest()
        val uri = request.toUri()
        val state = checkNotNull(request.state)
        assertEquals("auth.civitai.com", uri.host)
        assertEquals("/api/auth/oauth/authorize", uri.path)
        assertEquals("code", uri.getQueryParameter("response_type"))
        assertEquals("ModelsRead", uri.getQueryParameter("scope"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
        assertTrue(!uri.getQueryParameter("code_challenge").isNullOrBlank())
        assertNull(uri.getQueryParameter("code_verifier"))
        val reply = AuthorizationResponse.Builder(request).setState(state).setAuthorizationCode("synthetic-code")
            .build().toIntent().apply {
                data = request.redirectUri.buildUpon().appendQueryParameter("state", state)
                    .appendQueryParameter("code", "synthetic-code").build()
            }
        val result = ActivityResult(Activity.RESULT_OK, reply)
        assertSame(BrowserResult.Failed, CivitaiOAuth.browserResult(result, "another-request"))
        val code = CivitaiOAuth.browserResult(result, state) as BrowserResult.Code
        val exchange = CivitaiOAuth.exchangeRequest(code).requestParameters
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals("synthetic-code", exchange["code"])
        assertTrue(exchange["code_verifier"] == request.codeVerifier)
        assertEquals(request.redirectUri.toString(), exchange["redirect_uri"])
        assertFalse(exchange.containsKey("client_secret"))
    }

    @Test
    fun receiverChecksTheCallbackBeforeAppAuthParsesIt() {
        val url = "https://pocketmuse.github.io/oauth/callback/"
        val success = Uri.parse("$url?state=pending&code=synthetic")
        val denied = Uri.parse("$url?state=pending&error=access_denied")
        assertEquals(success, CivitaiOAuth.verifiedRedirect(success))
        assertEquals(denied, CivitaiOAuth.verifiedRedirect(denied))
        assertEquals(success, CivitaiOAuth.verifiedRedirect(Uri.parse("$success&expires_in=invalid")))
        listOf(
            "$url?state=pending&code=",
            "$url?state=pending&code=synthetic&state=duplicate",
            "$url?state=pending&error=access_denied&code=synthetic",
            "https://pocketmuse.github.io/oauth/callback?state=pending&code=synthetic",
            "https://example.test/oauth/callback/?state=pending&code=synthetic"
        ).forEach { assertNull(CivitaiOAuth.verifiedRedirect(Uri.parse(it))) }
    }

    @Test
    fun keystoreEncryptsBothTokensAndRestoresAfterStoreRecreation() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(target.noBackupFilesDir.toPath(), "auth-test-").toFile()
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = directory
        }
        val pair = TokenPair("generated-access-marker", "generated-refresh-marker", 1000)
        val store = KeystoreSessionStore(context)
        try {
            assertNull(store.read())
            store.write(pair)
            val raw = checkNotNull(directory.listFiles()).single().readBytes().decodeToString()
            assertFalse(raw.contains(pair.accessToken))
            assertFalse(raw.contains(pair.refreshToken))
            val restored = KeystoreSessionStore(context)
            assertEquals(pair, restored.read())
            restored.write(null)
            assertNull(store.read())
            assertTrue(checkNotNull(directory.list()).isEmpty())
        } finally {
            store.write(null)
            directory.delete()
        }
    }
}
