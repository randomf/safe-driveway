package io.github.randomf.safedriveway

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import io.github.randomf.safedriveway.live.LiveBackend
import io.github.randomf.safedriveway.ui.AlarmEffect
import io.github.randomf.safedriveway.ui.AppNavigation
import io.github.randomf.safedriveway.ui.AppTheme
import io.github.randomf.safedriveway.ui.start.BluetoothScreen
import io.github.randomf.safedriveway.ui.start.NoticeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/** What the platform tells the app about itself. */
data class AppInfo(val version: String)

/**
 * The app: the driving-aid notice, then the Bluetooth check, then the screens. They run on the
 * real units, or in demo mode on pretend ones, which About switches on (ADR 0009). The app always
 * starts on the real units.
 */
@Composable
fun App(info: AppInfo, platform: Platform) {
    AppTheme {
        val live = remember { LiveBackend(platform.storage) }
        DisposableEffect(live) {
            onDispose {
                live.close()
                platform.alarms.release()
            }
        }
        val scope = rememberCoroutineScope()
        // Demo mode is on while there is a demo backend. It runs in a scope of its own, which
        // ends with demo mode.
        var demo by remember { mutableStateOf<Pair<DemoBackend, CoroutineScope>?>(null) }
        fun setDemoMode(on: Boolean) {
            demo?.second?.cancel()
            demo = if (on) {
                CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
                    .let { DemoBackend(it) to it }
            } else {
                null
            }
        }
        var noticeAccepted by rememberSaveable { mutableStateOf(false) }
        val foreground = isInForeground()
        val current = demo?.first

        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                if (!noticeAccepted) {
                    NoticeScreen { noticeAccepted = true }
                } else if (current == null) {
                    val (status, fix) = phoneBluetooth()
                    LaunchedEffect(foreground, status) {
                        live.setActive(foreground && status == BluetoothStatus.Ready)
                    }
                    DisposableEffect(live) { onDispose { live.setActive(false) } }
                    BluetoothGate(status, fix, onTryDemo = { setDemoMode(true) }) {
                        AppNavigation(
                            live,
                            info.version,
                            false,
                            ::setDemoMode,
                            platform.alarms::phoneVolume
                        )
                    }
                } else {
                    val settings by current.settings.collectAsState()
                    BluetoothGate(settings.bluetooth.status, {
                        current.changeSettings { it.copy(bluetooth = DemoBluetooth.Ready) }
                    }) {
                        AppNavigation(
                            current,
                            info.version,
                            true,
                            ::setDemoMode,
                            platform.alarms::phoneVolume
                        )
                    }
                }
                if (current != null) DemoButton(current, Modifier.align(Alignment.CenterEnd))
            }
        }
        AlarmEffect(current ?: live, platform.alarms, enabled = foreground && noticeAccepted)
    }
}

/** What the demo pretends about Bluetooth. Demo mode never uses the phone's own. */
private val DemoBluetooth.status: BluetoothStatus
    get() = when (this) {
        DemoBluetooth.Ready -> BluetoothStatus.Ready
        DemoBluetooth.Off -> BluetoothStatus.Off
        DemoBluetooth.PermissionNeeded -> BluetoothStatus.PermissionNeeded
        DemoBluetooth.NotSupported -> BluetoothStatus.NotSupported
    }

/**
 * Shows [content] once Bluetooth is ready, and the Bluetooth screen over it whenever it isn't.
 * [fix] is what the screen's button does; [onTryDemo], when set, offers demo mode instead.
 */
@Composable
private fun BluetoothGate(
    status: BluetoothStatus,
    fix: () -> Unit,
    onTryDemo: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    // The screens start once Bluetooth has been ready, and stay put while it's off for a moment.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(status) { if (status == BluetoothStatus.Ready) started = true }
    Box(Modifier.fillMaxSize()) {
        if (started) content()
        if (status != BluetoothStatus.Ready) {
            Surface(Modifier.fillMaxSize()) { BluetoothScreen(status, fix, onTryDemo) }
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
