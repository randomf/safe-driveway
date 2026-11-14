package io.github.randomf.safedriveway

import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Shows the app's screen in the activity. androidApp calls this, so that all Compose code stays
 * in this module and androidApp needs no Compose of its own. A debuggable build shows the demo
 * panel.
 */
fun ComponentActivity.setAppContent() = setContent {
    App(
        AppInfo(
            version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
            debug = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        )
    )
}
