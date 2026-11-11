package com.example.pocketmuseprototype

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pocketmuseprototype.auth.BrowserResult
import com.example.pocketmuseprototype.auth.AuthState
import com.example.pocketmuseprototype.auth.CivitaiOAuth
import com.example.pocketmuseprototype.catalog.CatalogCheckpoint
import com.example.pocketmuseprototype.download.EnqueueResult
import com.example.pocketmuseprototype.ui.PocketMuseApp
import com.example.pocketmuseprototype.ui.theme.PocketMusePrototypeTheme
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationService

class MainActivity : ComponentActivity() {
    private val app get() = application as PocketMuseApplication
    private val auth get() = app.authManager
    private var libraryRequest by mutableIntStateOf(0)
    private var askedNotifications = false
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var browserService: AuthorizationService? = null
    private var authTabVerifier: String? = null
    private val authTab = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        val verifier = authTabVerifier
        authTabVerifier = null
        val state = auth.pendingAuthorizationState
        if (verifier != null && state != null) {
            auth.finishAuthorization(CivitaiOAuth.authTabResult(result.resultCode, result.resultUri, state, verifier))
        }
    }
    private val browser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        releaseBrowserService()
        auth.pendingAuthorizationState?.let { expectedState ->
            auth.finishAuthorization(CivitaiOAuth.browserResult(result, expectedState))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openLibraryIfRequested(intent)
        askedNotifications = savedInstanceState?.getBoolean(ASKED_NOTIFICATIONS) ?: false
        auth.restoreAuthorization(savedInstanceState?.getString(AUTHORIZATION_STATE))
        authTabVerifier = savedInstanceState?.getString(AUTH_TAB_VERIFIER)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            val authState by auth.state.collectAsStateWithLifecycle()
            val library by app.downloads.models.collectAsStateWithLifecycle(emptyList())
            val snackbar = remember { SnackbarHostState() }
            PocketMusePrototypeTheme(darkTheme = true, dynamicColor = false) {
                PocketMuseApp(
                    authState = authState,
                    onConnect = ::connectCivitai,
                    onRetryAuth = auth::retrySession,
                    onDisconnect = auth::disconnect,
                    library = library,
                    onDownloadRequest = { enqueue(it, snackbar) },
                    onRemoveModel = { model -> app.appScope.launch { app.downloads.remove(model) } },
                    snackbar = snackbar,
                    libraryRequest = libraryRequest
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        auth.onForeground()
        app.appScope.launch { app.downloads.reconcile() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLibraryIfRequested(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(AUTHORIZATION_STATE, auth.pendingAuthorizationState)
        outState.putString(AUTH_TAB_VERIFIER, authTabVerifier)
        outState.putBoolean(ASKED_NOTIFICATIONS, askedNotifications)
    }

    override fun onDestroy() {
        releaseBrowserService()
        super.onDestroy()
    }

    private fun connectCivitai() {
        val request = CivitaiOAuth.authorizationRequest()
        if (!auth.beginAuthorization(checkNotNull(request.state))) return
        try {
            val provider = CustomTabsClient.getPackageName(this, null)
            if (provider != null && CustomTabsClient.isAuthTabSupported(this, provider)) {
                authTabVerifier = checkNotNull(request.codeVerifier)
                val tab = AuthTabIntent.Builder().build()
                tab.intent.setPackage(provider)
                tab.launch(authTab, request.toUri(), checkNotNull(request.redirectUri.host),
                    checkNotNull(request.redirectUri.path))
            } else {
                val service = AuthorizationService(this).also { browserService = it }
                browser.launch(service.getAuthorizationRequestIntent(request))
            }
        } catch (_: ActivityNotFoundException) {
            authTabVerifier = null
            releaseBrowserService()
            auth.finishAuthorization(BrowserResult.Failed)
        }
    }

    private fun enqueue(checkpoint: CatalogCheckpoint, snackbar: SnackbarHostState) {
        if (auth.state.value !is AuthState.Connected) return
        lifecycleScope.launch {
            val result = app.downloads.enqueue(checkpoint)
            if (result == EnqueueResult.Queued) requestNotifications()
            snackbar.showSnackbar(getString(when (result) {
                EnqueueResult.Queued -> R.string.market_download_queued
                EnqueueResult.Exists -> R.string.market_download_exists
                EnqueueResult.StorageUnavailable -> R.string.library_error_storage
                EnqueueResult.SchedulingFailed -> R.string.library_error_schedule
            }))
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openLibraryIfRequested(intent: Intent) {
        if (!intent.getBooleanExtra(OPEN_LIBRARY, false)) return
        intent.removeExtra(OPEN_LIBRARY)
        libraryRequest++
    }

    private fun releaseBrowserService() {
        browserService?.dispose()
        browserService = null
    }

    internal companion object {
        const val OPEN_LIBRARY = "openLibrary"
        private const val AUTHORIZATION_STATE = "civitai.authorizationState"
        private const val AUTH_TAB_VERIFIER = "civitai.authTabVerifier"
        private const val ASKED_NOTIFICATIONS = "askedNotifications"
    }
}
