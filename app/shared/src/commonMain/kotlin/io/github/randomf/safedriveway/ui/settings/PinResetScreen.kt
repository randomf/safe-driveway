package io.github.randomf.safedriveway.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.randomf.safedriveway.backend.Backend
import io.github.randomf.safedriveway.backend.PinResetState
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.done
import io.github.randomf.safedriveway.resources.pin_reset_done
import io.github.randomf.safedriveway.resources.pin_reset_how
import io.github.randomf.safedriveway.resources.pin_reset_missed
import io.github.randomf.safedriveway.resources.pin_reset_setting
import io.github.randomf.safedriveway.resources.pin_reset_start
import io.github.randomf.safedriveway.resources.pin_reset_switch
import io.github.randomf.safedriveway.resources.pin_reset_title
import io.github.randomf.safedriveway.resources.pin_reset_waiting
import io.github.randomf.safedriveway.ui.AppColors
import io.github.randomf.safedriveway.ui.Hint
import io.github.randomf.safedriveway.ui.NewPinFields
import io.github.randomf.safedriveway.ui.ScreenScaffold
import io.github.randomf.safedriveway.ui.StatusDot
import io.github.randomf.safedriveway.ui.unitName
import org.jetbrains.compose.resources.stringResource

/**
 * Sets a new PIN when the old one is forgotten. The driver types it once, then switches each unit
 * off and on; the app sets it on each unit within the 2 minutes after the unit comes back.
 */
@Composable
fun PinResetScreen(backend: Backend, passageId: PassageId, onBack: () -> Unit, onDone: () -> Unit) {
    val remembered by backend.remembered.collectAsState()
    val open by backend.open.collectAsState()
    val progress by backend.pinReset.collectAsState()
    val setup = open?.setup?.takeIf { it.id == passageId }
        ?: remembered.find { it.setup.id == passageId }?.setup
    var pin by remember { mutableStateOf<String?>(null) }
    val current = progress
    // However the driver leaves, by back, the system's back or Done, the reset ends with the screen.
    DisposableEffect(backend) { onDispose { backend.clearPinReset() } }

    ScreenScaffold(
        stringResource(Res.string.pin_reset_title),
        onBack = onBack,
        bottomBar = {
            if (current == null) {
                Button(
                    onClick = { backend.startPinReset(passageId, pin!!) },
                    enabled = pin != null
                ) { Text(stringResource(Res.string.pin_reset_start)) }
            } else {
                Button(
                    onClick = onDone,
                    enabled = current.finished
                ) { Text(stringResource(Res.string.done)) }
            }
        }
    ) {
        Text(setup?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
        if (current == null) {
            Hint(stringResource(Res.string.pin_reset_how))
            NewPinFields(onChange = { pin = it })
        } else {
            Text(stringResource(Res.string.pin_reset_switch))
            current.units.forEach { (id, state) ->
                val unit = setup?.unit(id) ?: return@forEach
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatusDot(
                        when (state) {
                            PinResetState.Done -> AppColors.Clear
                            PinResetState.Missed -> AppColors.Critical
                            is PinResetState.Setting -> AppColors.Warning
                            PinResetState.WaitingForRestart -> AppColors.NoData
                        }
                    )
                    Column(Modifier.weight(1f)) {
                        Text(unitName(unit), style = MaterialTheme.typography.titleMedium)
                        Hint(
                            when (state) {
                                PinResetState.WaitingForRestart -> stringResource(
                                    Res.string.pin_reset_waiting
                                )

                                is PinResetState.Setting -> stringResource(
                                    Res.string.pin_reset_setting,
                                    minutesAndSeconds(state.secondsLeft)
                                )

                                PinResetState.Done -> stringResource(Res.string.pin_reset_done)

                                PinResetState.Missed -> stringResource(Res.string.pin_reset_missed)
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Such as "1:58". */
private fun minutesAndSeconds(seconds: Int): String =
    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
