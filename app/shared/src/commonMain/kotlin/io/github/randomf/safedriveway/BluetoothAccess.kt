package io.github.randomf.safedriveway

import androidx.compose.runtime.Composable

/**
 * The platform's side of Bluetooth: the app's permission to use it, and whether it's on.
 * [permissionBlocked] means the system no longer asks for the permission, so [requestPermission]
 * opens the app's settings instead.
 */
class BluetoothAccess(
    val permitted: Boolean,
    val permissionBlocked: Boolean,
    val on: Boolean,
    val requestPermission: () -> Unit
)

/** Asks for the Bluetooth permission when first shown, and follows both values while on screen. */
@Composable
expect fun rememberBluetoothAccess(): BluetoothAccess
