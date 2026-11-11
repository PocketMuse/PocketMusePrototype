package com.example.pocketmuseprototype

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pocketmuseprototype.auth.RedirectUriReceiverActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLinksTest {
    @Test
    fun onlyTheExactHttpsCallbackMatchesAppAuthReceiver() {
        assertEquals(
            listOf(RedirectUriReceiverActivity::class.java.name),
            matchingActivities("https://pocketmuse.github.io/oauth/callback/")
        )

        val otherUrls = listOf(
            "https://pocketmuse.github.io/",
            "https://pocketmuse.github.io/oauth/callback",
            "https://pocketmuse.github.io/oauth/callback/extra",
            "http://pocketmuse.github.io/oauth/callback/",
            "https://example.test/oauth/callback/"
        )
        otherUrls.forEach { url ->
            assertTrue("Unexpected app handler for $url", matchingActivities(url).isEmpty())
        }
    }

    @Test
    fun launcherStillResolvesToMainActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertEquals(MainActivity::class.java.name, launch?.component?.className)
    }

    @Test
    fun activityRecreationUsesTheSameProcessAuthManager() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as PocketMuseApplication
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val auth = app.authManager
            scenario.recreate()
            scenario.onActivity { activity ->
                assertSame(auth, (activity.application as PocketMuseApplication).authManager)
            }
        }
    }

    @Suppress("DEPRECATION") // The int flags overload supports API 29–32.
    private fun matchingActivities(url: String): List<String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(context.packageName)

        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.name }
    }
}
