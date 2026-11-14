package io.github.randomf.safedriveway.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.randomf.safedriveway.passage.Direction
import io.github.randomf.safedriveway.passage.End
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.demo
import io.github.randomf.safedriveway.resources.demo_bluetooth
import io.github.randomf.safedriveway.resources.demo_bluetooth_not_supported
import io.github.randomf.safedriveway.resources.demo_bluetooth_off
import io.github.randomf.safedriveway.resources.demo_bluetooth_permission
import io.github.randomf.safedriveway.resources.demo_bluetooth_phone
import io.github.randomf.safedriveway.resources.demo_course_clear
import io.github.randomf.safedriveway.resources.demo_course_critical
import io.github.randomf.safedriveway.resources.demo_course_tight
import io.github.randomf.safedriveway.resources.demo_end_far
import io.github.randomf.safedriveway.resources.demo_end_usual
import io.github.randomf.safedriveway.resources.demo_hint
import io.github.randomf.safedriveway.resources.demo_in_range
import io.github.randomf.safedriveway.resources.demo_in_range_both
import io.github.randomf.safedriveway.resources.demo_in_range_garage
import io.github.randomf.safedriveway.resources.demo_in_range_new_units
import io.github.randomf.safedriveway.resources.demo_in_range_nothing
import io.github.randomf.safedriveway.resources.demo_pass
import io.github.randomf.safedriveway.resources.demo_pass_needs_passage
import io.github.randomf.safedriveway.resources.demo_pass_playing
import io.github.randomf.safedriveway.resources.demo_pin_known
import io.github.randomf.safedriveway.resources.demo_play
import io.github.randomf.safedriveway.resources.demo_problem_in_use
import io.github.randomf.safedriveway.resources.demo_problem_lost
import io.github.randomf.safedriveway.resources.demo_problem_low_battery
import io.github.randomf.safedriveway.resources.demo_problem_needs_newer_app
import io.github.randomf.safedriveway.resources.demo_problem_out_of_date
import io.github.randomf.safedriveway.resources.demo_problem_phone_limit
import io.github.randomf.safedriveway.resources.demo_problem_sensor_fault
import io.github.randomf.safedriveway.resources.demo_problem_write_fails
import io.github.randomf.safedriveway.resources.demo_problems
import io.github.randomf.safedriveway.resources.direction_forwards
import io.github.randomf.safedriveway.resources.direction_reversing
import io.github.randomf.safedriveway.ui.Hint
import io.github.randomf.safedriveway.ui.SectionTitle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The "Demo" pill on every screen of a debug build, which opens the demo panel. */
@Composable
fun DemoButton(backend: DemoBackend, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Surface(
        onClick = { open = true },
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    ) {
        Text(
            stringResource(Res.string.demo),
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
    if (open) DemoPanel(backend, onDismiss = { open = false })
}

/** Sets the world the prototype pretends to be in, and plays a simulated car through. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DemoPanel(backend: DemoBackend, onDismiss: () -> Unit) {
    val settings by backend.settings.collectAsState()
    val open by backend.open.collectAsState()
    val passing by backend.passing.collectAsState()
    fun change(block: (DemoSettings) -> DemoSettings) = backend.changeSettings(block)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(
                rememberScrollState()
            ).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(Res.string.demo), style = MaterialTheme.typography.titleLarge)
            Hint(stringResource(Res.string.demo_hint, DEMO_PIN))

            SectionTitle(stringResource(Res.string.demo_pass))
            Choices(
                listOf(
                    Direction.Forwards to Res.string.direction_forwards,
                    Direction.Reversing to Res.string.direction_reversing
                ),
                settings.direction
            ) { value -> change { it.copy(direction = value) } }
            Choices(
                listOf(End.Usual to Res.string.demo_end_usual, End.Far to Res.string.demo_end_far),
                settings.entry
            ) { value -> change { it.copy(entry = value) } }
            Choices(
                listOf(
                    DemoCourse.Clear to Res.string.demo_course_clear,
                    DemoCourse.Tight to Res.string.demo_course_tight,
                    DemoCourse.Critical to Res.string.demo_course_critical
                ),
                settings.course
            ) { value -> change { it.copy(course = value) } }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        backend.playPass()
                        onDismiss()
                    },
                    enabled = open != null && !passing
                ) { Text(stringResource(Res.string.demo_play)) }
                when {
                    open == null -> Hint(stringResource(Res.string.demo_pass_needs_passage))
                    passing -> Hint(stringResource(Res.string.demo_pass_playing))
                }
            }

            SectionTitle(stringResource(Res.string.demo_in_range))
            Choices(
                listOf(
                    DemoInRange.Garage to Res.string.demo_in_range_garage,
                    DemoInRange.GarageAndFrontGate to Res.string.demo_in_range_both,
                    DemoInRange.Nothing to Res.string.demo_in_range_nothing,
                    DemoInRange.NewUnits to Res.string.demo_in_range_new_units
                ),
                settings.inRange
            ) { value -> change { it.copy(inRange = value) } }

            SectionTitle(stringResource(Res.string.demo_problems))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    DemoProblem.LowBattery to Res.string.demo_problem_low_battery,
                    DemoProblem.SensorFault to Res.string.demo_problem_sensor_fault,
                    DemoProblem.OutOfDate to Res.string.demo_problem_out_of_date,
                    DemoProblem.InUse to Res.string.demo_problem_in_use,
                    DemoProblem.PhoneLimit to Res.string.demo_problem_phone_limit,
                    DemoProblem.NeedsNewerApp to Res.string.demo_problem_needs_newer_app,
                    DemoProblem.LostDuringPass to Res.string.demo_problem_lost,
                    DemoProblem.WriteFails to Res.string.demo_problem_write_fails
                ).forEach { (problem, label) ->
                    val on = problem in settings.problems
                    FilterChip(
                        selected = on,
                        onClick = {
                            change {
                                it.copy(
                                    problems = if (on) {
                                        it.problems - problem
                                    } else {
                                        it.problems +
                                            problem
                                    }
                                )
                            }
                        },
                        label = { Text(stringResource(label)) }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.demo_pin_known), Modifier.weight(1f))
                Switch(settings.pinKnown, onCheckedChange = { on ->
                    change { it.copy(pinKnown = on) }
                })
            }

            SectionTitle(stringResource(Res.string.demo_bluetooth))
            Choices(
                listOf(
                    DemoBluetooth.Phone to Res.string.demo_bluetooth_phone,
                    DemoBluetooth.Off to Res.string.demo_bluetooth_off,
                    DemoBluetooth.PermissionNeeded to Res.string.demo_bluetooth_permission,
                    DemoBluetooth.NotSupported to Res.string.demo_bluetooth_not_supported
                ),
                settings.bluetooth
            ) { value -> change { it.copy(bluetooth = value) } }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One choice out of a few, as chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(
    options: List<Pair<T, StringResource>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(stringResource(label)) }
            )
        }
    }
}
