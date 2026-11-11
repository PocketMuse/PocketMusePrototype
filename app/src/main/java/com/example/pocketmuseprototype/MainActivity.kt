package com.example.pocketmuseprototype

import android.content.ActivityNotFoundException
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pocketmuseprototype.auth.BrowserResult
import com.example.pocketmuseprototype.auth.CivitaiOAuth
import com.example.pocketmuseprototype.ui.PocketMuseApp
import com.example.pocketmuseprototype.ui.theme.PocketMusePrototypeTheme
import net.openid.appauth.AuthorizationService

class MainActivity : ComponentActivity() {
    private val auth by lazy { (application as PocketMuseApplication).authManager }
    private var browserService: AuthorizationService? = null
    private val browser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        releaseBrowserService()
        auth.pendingAuthorizationState?.let { expectedState ->
            auth.finishAuthorization(CivitaiOAuth.browserResult(result, expectedState))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth.restoreAuthorization(savedInstanceState?.getString(AUTHORIZATION_STATE))
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            val authState by auth.state.collectAsStateWithLifecycle()
            PocketMusePrototypeTheme(darkTheme = true, dynamicColor = false) {
                PocketMuseApp(
                    authState = authState,
                    onConnect = ::connectCivitai,
                    onRetryAuth = auth::retrySession,
                    onDisconnect = auth::disconnect
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        auth.onForeground()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(AUTHORIZATION_STATE, auth.pendingAuthorizationState)
    }

    override fun onDestroy() {
        releaseBrowserService()
        super.onDestroy()
    }

    private fun connectCivitai() {
        val request = CivitaiOAuth.authorizationRequest()
        if (!auth.beginAuthorization(checkNotNull(request.state))) return
        try {
            val service = AuthorizationService(this).also { browserService = it }
            browser.launch(service.getAuthorizationRequestIntent(request))
        } catch (_: ActivityNotFoundException) {
            releaseBrowserService()
            auth.finishAuthorization(BrowserResult.Failed)
        }
    }

    private fun releaseBrowserService() {
        browserService?.dispose()
        browserService = null
    }

    private companion object {
        const val AUTHORIZATION_STATE = "civitai.authorizationState"
    }
}
