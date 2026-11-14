package io.github.randomf.safedriveway

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

// Android 12 and later: no location permission, see the manifest.
private val permissions = arrayOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT)

@Composable
actual fun rememberBluetoothAccess(): BluetoothAccess {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var permitted by remember { mutableStateOf(context.hasBluetoothPermissions()) }
    // After the driver denies the permission twice, Android stops asking: a request is denied
    // at once, without a dialog, and only the app's settings can allow it. A denial with no
    // rationale to show is treated so; after a dismissed dialog the settings work too.
    var blocked by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(RequestMultiplePermissions()) {
            permitted = context.hasBluetoothPermissions()
            blocked = !permitted &&
                permissions.none { activity?.shouldShowRequestPermissionRationale(it) == true }
        }

    // Ask once, not again after the screen rotates. The button asks again.
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!permitted && !asked) {
            asked = true
            launcher.launch(permissions)
        }
    }
    // The permission can be changed in the system settings while the app is in the background.
    LifecycleResumeEffect(Unit) {
        permitted = context.hasBluetoothPermissions()
        onPauseOrDispose {}
    }

    // key() starts over when the permission changes, so the previous value is never shown.
    val on = key(permitted) {
        val flow = remember { if (permitted) context.bluetoothOn() else flowOf(false) }
        flow.collectAsState(initial = permitted && context.isBluetoothOn()).value
    }
    return BluetoothAccess(permitted, blocked, on) {
        if (blocked) context.openAppSettings() else launcher.launch(permissions)
    }
}

private fun Context.hasBluetoothPermissions() =
    permissions.all { ContextCompat.checkSelfPermission(this, it) == PERMISSION_GRANTED }

private fun Context.openAppSettings() = startActivity(
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    )
)

private fun Context.isBluetoothOn() =
    getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

/** Whether Bluetooth is on, following changes. Needs BLUETOOTH_CONNECT to receive them. */
private fun Context.bluetoothOn(): Flow<Boolean> = callbackFlow {
    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
                trySend(state == BluetoothAdapter.STATE_ON)
            }
        }
    ContextCompat.registerReceiver(
        this@bluetoothOn,
        receiver,
        IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED
    )
    send(isBluetoothOn())
    awaitClose { unregisterReceiver(receiver) }
}
