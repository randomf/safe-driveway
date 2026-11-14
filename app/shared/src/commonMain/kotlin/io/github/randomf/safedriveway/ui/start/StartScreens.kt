package io.github.randomf.safedriveway.ui.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.randomf.safedriveway.BluetoothStatus
import io.github.randomf.safedriveway.backend.Backend
import io.github.randomf.safedriveway.backend.Nearby
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.allow_bluetooth
import io.github.randomf.safedriveway.resources.bluetooth_checking
import io.github.randomf.safedriveway.resources.bluetooth_not_supported
import io.github.randomf.safedriveway.resources.bluetooth_off
import io.github.randomf.safedriveway.resources.bluetooth_permission_blocked
import io.github.randomf.safedriveway.resources.bluetooth_permission_needed
import io.github.randomf.safedriveway.resources.bluetooth_ready
import io.github.randomf.safedriveway.resources.bluetooth_title
import io.github.randomf.safedriveway.resources.notice_accept
import io.github.randomf.safedriveway.resources.notice_late
import io.github.randomf.safedriveway.resources.notice_responsible
import io.github.randomf.safedriveway.resources.notice_title
import io.github.randomf.safedriveway.resources.open_settings
import io.github.randomf.safedriveway.resources.scanning
import io.github.randomf.safedriveway.resources.which_passage_hint
import io.github.randomf.safedriveway.resources.which_passage_title
import io.github.randomf.safedriveway.ui.Hint
import io.github.randomf.safedriveway.ui.ScreenScaffold
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Shown when the app opens: the driver stays responsible for the car (app/features.md). */
@Composable
fun NoticeScreen(onAccept: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
    ) {
        Text(
            stringResource(Res.string.notice_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            stringResource(Res.string.notice_responsible),
            style = MaterialTheme.typography.bodyLarge
        )
        Text(stringResource(Res.string.notice_late), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(Res.string.notice_accept))
        }
    }
}

/** Shown instead of everything else while Bluetooth isn't ready. */
@Composable
fun BluetoothScreen(status: BluetoothStatus, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(Res.string.bluetooth_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            stringResource(status.message),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        status.action?.let { action ->
            Button(onClick = onAction) { Text(stringResource(action)) }
        }
    }
}

private val BluetoothStatus.message: StringResource
    get() = when (this) {
        BluetoothStatus.Checking -> Res.string.bluetooth_checking
        BluetoothStatus.NotSupported -> Res.string.bluetooth_not_supported
        BluetoothStatus.PermissionNeeded -> Res.string.bluetooth_permission_needed
        BluetoothStatus.PermissionBlocked -> Res.string.bluetooth_permission_blocked
        BluetoothStatus.Off -> Res.string.bluetooth_off
        BluetoothStatus.Ready -> Res.string.bluetooth_ready
    }

/** The label of the button that fixes the problem, where the app can help. */
private val BluetoothStatus.action: StringResource?
    get() = when (this) {
        BluetoothStatus.PermissionNeeded -> Res.string.allow_bluetooth
        BluetoothStatus.PermissionBlocked -> Res.string.open_settings
        else -> null
    }

/** Where the app goes once it has heard the units in range. */
sealed interface StartRoute {
    data class Open(val passage: PassageId) : StartRoute

    data object Choose : StartRoute

    data object NoneInRange : StartRoute
}

/**
 * One passage in range opens at once. With several, the app asks which one, unless the driver
 * already chose among the same passages.
 */
fun startRoute(nearby: Nearby, chosenAmong: (Set<PassageId>) -> PassageId?): StartRoute {
    val ids = nearby.passages.map { it.id }.toSet()
    return when {
        ids.isEmpty() -> StartRoute.NoneInRange
        ids.size == 1 -> StartRoute.Open(ids.single())
        else -> chosenAmong(ids)?.let { StartRoute.Open(it) } ?: StartRoute.Choose
    }
}

/** Waits for the first scan, then goes where [startRoute] says. */
@Composable
fun ScanningScreen(backend: Backend, onRoute: (StartRoute) -> Unit) {
    val nearby by backend.nearby.collectAsState()
    LaunchedEffect(nearby) {
        if (nearby.settled) onRoute(startRoute(nearby, backend::chosenAmong))
    }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(Modifier.size(56.dp))
        Text(stringResource(Res.string.scanning), style = MaterialTheme.typography.titleMedium)
    }
}

/** Several passages are in range: which one is the driver driving through? */
@Composable
fun WhichPassageScreen(backend: Backend, onBack: () -> Unit, onChosen: (PassageId) -> Unit) {
    val nearby by backend.nearby.collectAsState()
    ScreenScaffold(stringResource(Res.string.which_passage_title), onBack) {
        Hint(stringResource(Res.string.which_passage_hint))
        nearby.passages.forEach { passage ->
            Card(
                onClick = {
                    backend.choose(nearby.passages.map { it.id }.toSet(), passage.id)
                    onChosen(passage.id)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    passage.name,
                    Modifier.padding(24.dp),
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        }
    }
}
