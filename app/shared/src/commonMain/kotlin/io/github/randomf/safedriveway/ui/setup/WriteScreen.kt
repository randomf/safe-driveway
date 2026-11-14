package io.github.randomf.safedriveway.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.randomf.safedriveway.backend.Backend
import io.github.randomf.safedriveway.backend.WriteState
import io.github.randomf.safedriveway.passage.PassageSetup
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.done
import io.github.randomf.safedriveway.resources.new_unit_name
import io.github.randomf.safedriveway.resources.try_again
import io.github.randomf.safedriveway.resources.write_deleting
import io.github.randomf.safedriveway.resources.write_failed
import io.github.randomf.safedriveway.resources.write_failed_deleting_hint
import io.github.randomf.safedriveway.resources.write_failed_hint
import io.github.randomf.safedriveway.resources.write_keep_near
import io.github.randomf.safedriveway.resources.write_out_of_reach
import io.github.randomf.safedriveway.resources.write_out_of_reach_deleting
import io.github.randomf.safedriveway.resources.write_out_of_reach_hint
import io.github.randomf.safedriveway.resources.write_reset
import io.github.randomf.safedriveway.resources.write_saved
import io.github.randomf.safedriveway.resources.write_title
import io.github.randomf.safedriveway.resources.write_waiting
import io.github.randomf.safedriveway.resources.write_writing
import io.github.randomf.safedriveway.ui.AppColors
import io.github.randomf.safedriveway.ui.Hint
import io.github.randomf.safedriveway.ui.ScreenScaffold
import io.github.randomf.safedriveway.ui.StatusDot
import io.github.randomf.safedriveway.ui.unitName
import org.jetbrains.compose.resources.stringResource

/**
 * The setup going to each unit, one after another. Units out of reach get it the next time a
 * phone that knows the PIN finds them. [setups] name the units.
 */
@Composable
fun WriteScreen(backend: Backend, setups: List<PassageSetup>, onDone: () -> Unit) {
    val progress by backend.writing.collectAsState()
    val current = progress ?: return
    ScreenScaffold(
        stringResource(if (current.deleting) Res.string.write_deleting else Res.string.write_title),
        onBack = null,
        bottomBar = {
            if (current.finished && current.failed) {
                OutlinedButton(onClick = backend::retryWrite) {
                    Text(stringResource(Res.string.try_again))
                }
            }
            Button(onClick = onDone, enabled = current.finished) {
                Text(stringResource(Res.string.done))
            }
        }
    ) {
        Hint(stringResource(Res.string.write_keep_near))
        val doneText = if (current.deleting) Res.string.write_reset else Res.string.write_saved
        current.units.forEach { (id, state) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state == WriteState.Writing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    StatusDot(
                        when (state) {
                            WriteState.Done -> AppColors.Clear
                            WriteState.Failed -> AppColors.Critical
                            WriteState.OutOfReach -> AppColors.Warning
                            else -> AppColors.NoData
                        }
                    )
                }
                Text(
                    nameOf(id, setups),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(
                        when (state) {
                            WriteState.Waiting -> Res.string.write_waiting
                            WriteState.Writing -> Res.string.write_writing
                            WriteState.Done -> doneText
                            WriteState.Failed -> Res.string.write_failed
                            WriteState.OutOfReach -> Res.string.write_out_of_reach
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        // A unit that keeps failing mustn't trap the driver here: it keeps what it had.
        if (current.finished && current.failed) {
            Hint(
                stringResource(
                    if (current.deleting) {
                        Res.string.write_failed_deleting_hint
                    } else {
                        Res.string.write_failed_hint
                    }
                )
            )
        }
        if (current.units.any { it.state == WriteState.OutOfReach }) {
            Hint(
                stringResource(
                    if (current.deleting) {
                        Res.string.write_out_of_reach_deleting
                    } else {
                        Res.string.write_out_of_reach_hint
                    }
                )
            )
        }
    }
}

@Composable
private fun nameOf(id: UnitId, setups: List<PassageSetup>): String =
    setups.firstNotNullOfOrNull { it.unit(id) }?.let { unitName(it) }
        ?: stringResource(Res.string.new_unit_name, id.short)
