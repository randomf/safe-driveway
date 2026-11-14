package io.github.randomf.safedriveway

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Shows the app's screen in the activity. androidApp calls this, so that all Compose code stays
 * in this module and androidApp needs no Compose of its own.
 */
fun ComponentActivity.setAppContent() = setContent { App() }
