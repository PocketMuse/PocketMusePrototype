package com.example.pocketmuseprototype

import android.app.Application
import com.example.pocketmuseprototype.auth.AppAuthRemote
import com.example.pocketmuseprototype.auth.AuthManager
import com.example.pocketmuseprototype.auth.KeystoreSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.openid.appauth.internal.AppAuthLogging

class PocketMuseApplication : Application() {
    internal val authManager by lazy {
        AuthManager(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            KeystoreSessionStore(this),
            AppAuthRemote(this)
        )
    }

    override fun onCreate() {
        super.onCreate()
        AppAuthLogging.disable()
    }
}
