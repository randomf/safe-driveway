package io.github.randomf.safedriveway.ui.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.randomf.safedriveway.backend.Backend
import io.github.randomf.safedriveway.backend.NearbyPassage
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.about
import io.github.randomf.safedriveway.resources.gates_count
import io.github.randomf.safedriveway.resources.new_units_nearby
import io.github.randomf.safedriveway.resources.no_new_units
import io.github.randomf.safedriveway.resources.no_passages
import io.github.randomf.safedriveway.resources.passage_in_range
import io.github.randomf.safedriveway.resources.passage_in_use
import io.github.randomf.safedriveway.resources.passage_new_here
import io.github.randomf.safedriveway.resources.passage_not_in_range
import io.github.randomf.safedriveway.resources.passages_title
import io.github.randomf.safedriveway.resources.scanning_nearby
import io.github.randomf.safedriveway.resources.set_up_passage
import io.github.randomf.safedriveway.resources.units_and_gates
import io.github.randomf.safedriveway.resources.units_count
import io.github.randomf.safedriveway.ui.AppColors
import io.github.randomf.safedriveway.ui.Hint
import io.github.randomf.safedriveway.ui.ScreenScaffold
import io.github.randomf.safedriveway.ui.SectionTitle
import io.github.randomf.safedriveway.ui.StatusDot
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The passages this phone has used and those in range, and the way to set up a new one. When the
 * app opened with no passage in range, [autoOpen] opens one as soon as it comes into range.
 */
@Composable
fun PassagesScreen(
    backend: Backend,
    autoOpen: Boolean,
    onRoute: (StartRoute) -> Unit,
    onOpen: (PassageId) -> Unit,
    onNewPassage: () -> Unit,
    onAbout: () -> Unit
) {
    val nearby by backend.nearby.collectAsState()
    val remembered by backend.remembered.collectAsState()
    if (autoOpen) {
        LaunchedEffect(nearby) {
            val route = startRoute(nearby, backend::chosenAmong)
            if (nearby.settled && route != StartRoute.NoneInRange) onRoute(route)
        }
    }

    ScreenScaffold(
        stringResource(Res.string.passages_title),
        onBack = null,
        actions = {
            IconButton(onClick = onAbout) {
                Icon(Icons.Filled.Info, stringResource(Res.string.about))
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint(stringResource(Res.string.scanning_nearby))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        val inRange = nearby.passages.associateBy { it.id }
        val rememberedIds = remembered.map { it.setup.id }.toSet()
        if (remembered.isEmpty() && inRange.isEmpty()) Hint(stringResource(Res.string.no_passages))
        remembered.forEach { passage ->
            PassageCard(
                name = passage.setup.name,
                nearby = inRange[passage.setup.id],
                units = passage.setup.units.size,
                gates = passage.setup.gateCount,
                onClick = { onOpen(passage.setup.id) }
            )
        }
        nearby.passages.filter { it.id !in rememberedIds }.forEach { passage ->
            PassageCard(passage.name, passage, units = null, gates = null) { onOpen(passage.id) }
        }

        SectionTitle(stringResource(Res.string.set_up_passage))
        val spare = nearby.unassigned.size
        if (spare > 0) {
            Text(pluralStringResource(Res.plurals.new_units_nearby, spare, spare))
            Button(onClick = onNewPassage, Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null)
                Text(stringResource(Res.string.set_up_passage), Modifier.padding(start = 8.dp))
            }
        } else {
            Hint(stringResource(Res.string.no_new_units))
            OutlinedButton(onClick = onNewPassage, Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.set_up_passage))
            }
        }
    }
}

@Composable
private fun PassageCard(
    name: String,
    nearby: NearbyPassage?,
    units: Int?,
    gates: Int?,
    onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (color, text) = when {
                    nearby == null -> AppColors.NoData to Res.string.passage_not_in_range
                    nearby.inUse -> AppColors.Critical to Res.string.passage_in_use
                    else -> AppColors.Clear to Res.string.passage_in_range
                }
                StatusDot(color)
                Text(
                    stringResource(text),
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (units != null && gates != null) {
                Hint(
                    stringResource(
                        Res.string.units_and_gates,
                        pluralStringResource(Res.plurals.units_count, units, units),
                        pluralStringResource(Res.plurals.gates_count, gates, gates)
                    )
                )
            } else {
                Hint(stringResource(Res.string.passage_new_here))
            }
        }
    }
}
