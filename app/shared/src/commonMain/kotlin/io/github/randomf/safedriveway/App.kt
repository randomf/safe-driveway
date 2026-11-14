package io.github.randomf.safedriveway

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.juul.kable.Bluetooth
import com.juul.kable.ExperimentalKableApi
import io.github.randomf.safedriveway.demo.DemoBackend
import io.github.randomf.safedriveway.demo.DemoBluetooth
import io.github.randomf.safedriveway.demo.DemoButton
import io.github.randomf.safedriveway.ui.AppNavigation
import io.github.randomf.safedriveway.ui.AppTheme
import io.github.randomf.safedriveway.ui.start.BluetoothScreen
import io.github.randomf.safedriveway.ui.start.NoticeScreen

/** What the platform tells the app about itself. [debug] shows the demo panel. */
data class AppInfo(val version: String, val debug: Boolean)

/**
 * The app: the driving-aid notice, then the Bluetooth check, then the screens. The prototype runs
 * on the demo backend, with no Bluetooth of its own (ADR 0009).
 */
@Composable
fun App(info: AppInfo) {
    AppTheme {
        val scope = rememberCoroutineScope()
        val backend = remember { DemoBackend(scope) }
        val demo by backend.settings.collectAsState()
        var noticeAccepted by rememberSaveable { mutableStateOf(false) }

        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                if (!noticeAccepted) {
                    NoticeScreen { noticeAccepted = true }
                } else {
                    BluetoothGate(demo.bluetooth, {
                        backend.changeSettings { it.copy(bluetooth = DemoBluetooth.Phone) }
                    }) {
                        AppNavigation(backend, info.version)
                    }
                }
                if (info.debug) DemoButton(backend, Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

/**
 * Shows [content] once Bluetooth is ready, and the Bluetooth screen over it whenever it isn't. The
 * demo can pretend a problem; [onFixDemo] ends the pretence.
 */
@Composable
private fun BluetoothGate(
    demo: DemoBluetooth,
    onFixDemo: () -> Unit,
    content: @Composable () -> Unit
) {
    val (status, fix) = when (demo) {
        DemoBluetooth.Phone -> phoneBluetooth()
        DemoBluetooth.Off -> BluetoothStatus.Off to onFixDemo
        DemoBluetooth.PermissionNeeded -> BluetoothStatus.PermissionNeeded to onFixDemo
        DemoBluetooth.NotSupported -> BluetoothStatus.NotSupported to onFixDemo
    }
    // The screens start once Bluetooth has been ready, and stay put while it's off for a moment.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(status) { if (status == BluetoothStatus.Ready) started = true }
    Box(Modifier.fillMaxSize()) {
        if (started) content()
        if (status != BluetoothStatus.Ready) {
            Surface(Modifier.fillMaxSize()) { BluetoothScreen(status, fix) }
        }
    }
}

@OptIn(ExperimentalKableApi::class)
@Composable
private fun phoneBluetooth(): Pair<BluetoothStatus, () -> Unit> {
    val access = rememberBluetoothAccess()
    val supported by produceState<Boolean?>(null) { value = Bluetooth.isSupported() }
    return bluetoothStatus(supported, access.permitted, access.permissionBlocked, access.on) to
        access.requestPermission
}
