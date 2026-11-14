package io.github.randomf.safedriveway.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.randomf.safedriveway.backend.Backend
import io.github.randomf.safedriveway.backend.DriverPreferences
import io.github.randomf.safedriveway.passage.Direction
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.change_setup
import io.github.randomf.safedriveway.resources.change_setup_hint
import io.github.randomf.safedriveway.resources.forgot_pin
import io.github.randomf.safedriveway.resources.on_this_phone
import io.github.randomf.safedriveway.resources.passage_settings
import io.github.randomf.safedriveway.resources.reconnect_all
import io.github.randomf.safedriveway.resources.remove
import io.github.randomf.safedriveway.resources.remove_from_phone
import io.github.randomf.safedriveway.resources.remove_from_phone_text
import io.github.randomf.safedriveway.resources.remove_from_phone_title
import io.github.randomf.safedriveway.resources.setup
import io.github.randomf.safedriveway.resources.sound
import io.github.randomf.safedriveway.resources.sound_hint
import io.github.randomf.safedriveway.resources.units
import io.github.randomf.safedriveway.resources.usual_direction
import io.github.randomf.safedriveway.resources.usual_direction_hint
import io.github.randomf.safedriveway.resources.usual_forwards
import io.github.randomf.safedriveway.resources.usual_reversing
import io.github.randomf.safedriveway.resources.volume
import io.github.randomf.safedriveway.ui.ConfirmDialog
import io.github.randomf.safedriveway.ui.DangerButton
import io.github.randomf.safedriveway.ui.Hint
import io.github.randomf.safedriveway.ui.ScreenScaffold
import io.github.randomf.safedriveway.ui.SectionTitle
import org.jetbrains.compose.resources.stringResource

/**
 * A passage's settings: the driver's preferences on this phone, reconnecting the units, and the
 * way into its setup, which needs the PIN.
 */
@Composable
fun PassageSettingsScreen(
    backend: Backend,
    passageId: PassageId,
    onBack: () -> Unit,
    onChangeSetup: () -> Unit,
    onForgotPin: () -> Unit,
    onRemoved: () -> Unit
) {
    val remembered by backend.remembered.collectAsState()
    val passage = remembered.find { it.setup.id == passageId }
    val preferences = passage?.preferences ?: DriverPreferences()
    fun set(change: (DriverPreferences) -> DriverPreferences) =
        backend.setPreferences(passageId, change(preferences))
    var confirmRemove by remember { mutableStateOf(false) }

    ScreenScaffold(stringResource(Res.string.passage_settings), onBack) {
        Text(passage?.setup?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)

        SectionTitle(stringResource(Res.string.on_this_phone))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.usual_direction))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Direction.entries.forEachIndexed { i, direction ->
                    SegmentedButton(
                        selected = preferences.usualDirection == direction,
                        onClick = { set { it.copy(usualDirection = direction) } },
                        shape = SegmentedButtonDefaults.itemShape(i, Direction.entries.size)
                    ) {
                        Text(
                            stringResource(
                                when (direction) {
                                    Direction.Forwards -> Res.string.usual_forwards
                                    Direction.Reversing -> Res.string.usual_reversing
                                }
                            )
                        )
                    }
                }
            }
            Hint(stringResource(Res.string.usual_direction_hint))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.sound))
                Hint(stringResource(Res.string.sound_hint))
            }
            Switch(preferences.sound, onCheckedChange = { on -> set { it.copy(sound = on) } })
        }
        Column {
            Text(stringResource(Res.string.volume))
            Slider(
                value = preferences.volume,
                onValueChange = { volume -> set { it.copy(volume = volume) } },
                enabled = preferences.sound
            )
        }

        SectionTitle(stringResource(Res.string.units))
        OutlinedButton(onClick = backend::reconnectAll, Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.reconnect_all))
        }

        SectionTitle(stringResource(Res.string.setup))
        Hint(stringResource(Res.string.change_setup_hint))
        FilledTonalButton(onClick = onChangeSetup, Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.change_setup))
        }
        TextButton(onClick = onForgotPin) { Text(stringResource(Res.string.forgot_pin)) }

        DangerButton(stringResource(Res.string.remove_from_phone), { confirmRemove = true })
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(
                Res.string.remove_from_phone_title,
                passage?.setup?.name.orEmpty()
            ),
            text = stringResource(Res.string.remove_from_phone_text),
            confirm = stringResource(Res.string.remove),
            onConfirm = {
                confirmRemove = false
                backend.removeFromPhone(passageId)
                onRemoved()
            },
            onDismiss = { confirmRemove = false },
            destructive = true
        )
    }
}
