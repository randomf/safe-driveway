package io.github.randomf.safedriveway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.juul.kable.Bluetooth
import com.juul.kable.ExperimentalKableApi
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.allow_bluetooth
import io.github.randomf.safedriveway.resources.bluetooth_checking
import io.github.randomf.safedriveway.resources.bluetooth_not_supported
import io.github.randomf.safedriveway.resources.bluetooth_off
import io.github.randomf.safedriveway.resources.bluetooth_permission_blocked
import io.github.randomf.safedriveway.resources.bluetooth_permission_needed
import io.github.randomf.safedriveway.resources.bluetooth_ready
import io.github.randomf.safedriveway.resources.greeting
import io.github.randomf.safedriveway.resources.open_settings
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The app's only screen: a greeting and the state of Bluetooth. Dark by default (features.md). */
@OptIn(ExperimentalKableApi::class)
@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        val access = rememberBluetoothAccess()
        val supported by produceState<Boolean?>(null) { value = Bluetooth.isSupported() }
        val status =
            bluetoothStatus(supported, access.permitted, access.permissionBlocked, access.on)

        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(Res.string.greeting),
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center
                )
                Text(
                    stringResource(status.message),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                status.action?.let { action ->
                    Button(onClick = access.requestPermission) {
                        Text(stringResource(action))
                    }
                }
            }
        }
    }
}

private val BluetoothStatus.message: StringResource
    get() =
        when (this) {
            BluetoothStatus.Checking -> Res.string.bluetooth_checking
            BluetoothStatus.NotSupported -> Res.string.bluetooth_not_supported
            BluetoothStatus.PermissionNeeded -> Res.string.bluetooth_permission_needed
            BluetoothStatus.PermissionBlocked -> Res.string.bluetooth_permission_blocked
            BluetoothStatus.Off -> Res.string.bluetooth_off
            BluetoothStatus.Ready -> Res.string.bluetooth_ready
        }

/** The label of the button that fixes the problem, where the app can help. */
private val BluetoothStatus.action: StringResource?
    get() =
        when (this) {
            BluetoothStatus.PermissionNeeded -> Res.string.allow_bluetooth
            BluetoothStatus.PermissionBlocked -> Res.string.open_settings
            else -> null
        }
