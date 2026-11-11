package com.example.pocketmuseprototype

import android.app.Application
import android.os.Environment
import androidx.room.Room
import androidx.work.WorkManager
import com.example.pocketmuseprototype.auth.AppAuthRemote
import com.example.pocketmuseprototype.auth.AuthManager
import com.example.pocketmuseprototype.auth.AuthState
import com.example.pocketmuseprototype.auth.KeystoreSessionStore
import com.example.pocketmuseprototype.download.CivitaiTransfer
import com.example.pocketmuseprototype.download.DownloadError
import com.example.pocketmuseprototype.download.DownloadFailure
import com.example.pocketmuseprototype.download.LibraryDatabase
import com.example.pocketmuseprototype.download.ModelDownloads
import com.example.pocketmuseprototype.download.ModelStorage
import com.example.pocketmuseprototype.download.WorkManagerDownloads
import com.example.pocketmuseprototype.download.writeWebpThumbnail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.openid.appauth.internal.AppAuthLogging

class PocketMuseApplication : Application() {
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val authManager by lazy {
        AuthManager(
            appScope,
            KeystoreSessionStore(this),
            AppAuthRemote(this)
        )
    }
    private val downloadScheduler by lazy { WorkManagerDownloads(WorkManager.getInstance(this)) }
    internal val downloads by lazy {
        val library = Room.databaseBuilder(this, LibraryDatabase::class.java, LibraryDatabase.NAME).build()
        ModelDownloads(
            library.models(),
            ModelStorage {
                getExternalFilesDir(null)?.takeIf {
                    Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
                }
            },
            downloadScheduler,
            token = {
                authManager.state.first { it != AuthState.Initializing }
                authManager.validAccessToken() ?: throw DownloadFailure(
                    if (authManager.state.value is AuthState.Error) DownloadError.AuthUnavailable
                    else DownloadError.AuthRequired
                )
            },
            CivitaiTransfer(),
            ::writeWebpThumbnail
        )
    }

    override fun onCreate() {
        super.onCreate()
        AppAuthLogging.disable()
        appScope.launch {
            downloadScheduler.changes.collect { downloads.reconcile() }
        }
    }
}
