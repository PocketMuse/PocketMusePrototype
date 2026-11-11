package com.example.pocketmuseprototype.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import net.openid.appauth.AuthorizationManagementActivity

class RedirectUriReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let(CivitaiOAuth::verifiedRedirect)?.let { callback ->
                startActivity(AuthorizationManagementActivity.createResponseHandlingIntent(this, callback))
            }
        }
        finish()
    }
}
