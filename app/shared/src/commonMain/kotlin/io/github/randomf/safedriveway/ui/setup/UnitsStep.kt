package io.github.randomf.safedriveway.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.randomf.safedriveway.backend.Backend
import io.github.randomf.safedriveway.passage.Limits
import io.github.randomf.safedriveway.passage.Side
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.passage.UnitSetup
import io.github.randomf.safedriveway.passage.utf8Size
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.add_gate
import io.github.randomf.safedriveway.resources.add_unit
import io.github.randomf.safedriveway.resources.gate_number
import io.github.randomf.safedriveway.resources.identify
import io.github.randomf.safedriveway.resources.name_bytes
import io.github.randomf.safedriveway.resources.no_new_units
import io.github.randomf.safedriveway.resources.pick_unit_title
import io.github.randomf.safedriveway.resources.placement_and_id
import io.github.randomf.safedriveway.resources.remove_from_gate
import io.github.randomf.safedriveway.resources.remove_from_gate_hint
import io.github.randomf.safedriveway.resources.remove_gate
import io.github.randomf.safedriveway.resources.replace_unit
import io.github.randomf.safedriveway.resources.side_left_wall
import io.github.randomf.safedriveway.resources.side_right_wall
import io.github.randomf.safedriveway.resources.signals
import io.github.randomf.safedriveway.resources.signals_hint
import io.github.randomf.safedriveway.resources.step_units
import io.github.randomf.safedriveway.resources.unit_name
import io.github.randomf.safedriveway.resources.units_hint
import io.github.randomf.safedriveway.resources.you_stand_here
import io.github.randomf.safedriveway.ui.AppColors
import io.github.randomf.safedriveway.ui.Hint
import io.github.randomf.safedriveway.ui.sideName
import io.github.randomf.safedriveway.ui.unitName
import org.jetbrains.compose.resources.stringResource

/** A slot on the board: a gate and a side. */
private data class Slot(val gate: Int, val side: Side)

/**
 * The passage board: a drawing of the passage with end 0 at the bottom, where the driver stands,
 * and a left and a right slot at each gate. Tap an empty slot to give it a unit, a filled one to
 * name it, switch its signals or take it off.
 */
@Composable
fun UnitsStep(
    draft: SetupDraft,
    onChange: (SetupDraft) -> Unit,
    backend: Backend,
    wizard: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val setup = draft.setup
    var picking by remember { mutableStateOf<Slot?>(null) }
    var editing by remember { mutableStateOf<UnitId?>(null) }

    StepScaffold(
        stringResource(Res.string.step_units),
        draft,
        Step.Units,
        wizard,
        setup.problems(Step.Units).takeIf { setup.units.isNotEmpty() }.orEmpty(),
        onBack,
        onNext,
        ready = setup.units.isNotEmpty()
    ) {
        Hint(stringResource(Res.string.units_hint))
        Row(Modifier.fillMaxWidth().padding(start = 72.dp)) {
            listOf(Res.string.side_left_wall, Res.string.side_right_wall).forEach {
                Text(
                    stringResource(it),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center
                )
            }
        }
        if (setup.gateCount < Limits.MAX_GATES) {
            OutlinedButton(
                onClick = { onChange(draft.addGate()) },
                Modifier.fillMaxWidth().padding(start = 72.dp)
            ) {
                Icon(Icons.Filled.Add, null)
                Text(stringResource(Res.string.add_gate), Modifier.padding(start = 8.dp))
            }
        }
        // The furthest gate at the top, as seen from end 0.
        (setup.gateCount - 1 downTo 0).forEach { gate ->
            Row(
                Modifier.fillMaxWidth().height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.width(72.dp)) {
                    Text(
                        stringResource(Res.string.gate_number, gate + 1),
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (draft.canRemoveGate(gate)) {
                        TextButton(onClick = { onChange(draft.removeGate(gate)) }) {
                            Text(
                                stringResource(Res.string.remove_gate),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Wall()
                Side.entries.forEach { side ->
                    SlotButton(
                        unit = setup.unitAt(gate, side),
                        full = setup.units.size >= Limits.MAX_UNITS,
                        onEmpty = { picking = Slot(gate, side) },
                        onFilled = { editing = it },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                }
                Wall()
            }
        }
        Text(
            stringResource(Res.string.you_stand_here),
            Modifier.fillMaxWidth().padding(start = 72.dp),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )
    }

    picking?.let { slot ->
        UnitPicker(
            title = stringResource(Res.string.pick_unit_title, slot.gate + 1, sideName(slot.side)),
            backend = backend,
            exclude = setup.units.map { it.id }.toSet(),
            onPick = {
                onChange(draft.assign(it, slot.gate, slot.side))
                picking = null
            },
            onDismiss = { picking = null }
        )
    }
    editing?.let(setup::unit)?.let { unit ->
        UnitSheet(unit, draft, onChange, backend, onDismiss = { editing = null })
    }
}

@Composable
private fun Wall() {
    Box(Modifier.width(4.dp).fillMaxHeight().background(AppColors.Wall))
}

@Composable
private fun SlotButton(
    unit: UnitSetup?,
    full: Boolean,
    onEmpty: () -> Unit,
    onFilled: (UnitId) -> Unit,
    modifier: Modifier
) {
    if (unit == null) {
        OutlinedButton(onClick = onEmpty, modifier, enabled = !full) {
            Icon(Icons.Filled.Add, null)
            Text(stringResource(Res.string.add_unit), Modifier.padding(start = 4.dp), maxLines = 1)
        }
    } else {
        FilledTonalButton(onClick = { onFilled(unit.id) }, modifier) {
            Text(unitName(unit), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The units nearby that belong to no passage, to pick one from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitPicker(
    title: String,
    backend: Backend,
    exclude: Set<UnitId>,
    onPick: (UnitId) -> Unit,
    onDismiss: () -> Unit
) {
    val nearby by backend.nearby.collectAsState()
    val candidates = nearby.unassigned.filter { it.id !in exclude }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(
                rememberScrollState()
            ).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (candidates.isEmpty()) Hint(stringResource(Res.string.no_new_units))
            candidates.forEach { unit ->
                NearbyUnitCard(unit, onIdentify = { backend.identify(unit.id) }) { onPick(unit.id) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A unit on the board: its name, its signals, and taking it off or replacing it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitSheet(
    unit: UnitSetup,
    draft: SetupDraft,
    onChange: (SetupDraft) -> Unit,
    backend: Backend,
    onDismiss: () -> Unit
) {
    var replacing by remember { mutableStateOf(false) }
    if (replacing) {
        UnitPicker(
            title = stringResource(Res.string.replace_unit),
            backend = backend,
            exclude = draft.setup.units.map { it.id }.toSet(),
            onPick = {
                onChange(draft.replace(unit.id, it))
                onDismiss()
            },
            onDismiss = { replacing = false }
        )
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(
                rememberScrollState()
            ).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(unitName(unit), style = MaterialTheme.typography.titleLarge)
            Hint(
                stringResource(
                    Res.string.placement_and_id,
                    unit.gate + 1,
                    sideName(unit.side),
                    unit.id.short
                )
            )
            OutlinedTextField(
                value = unit.name,
                onValueChange = { name ->
                    if (name.utf8Size() <=
                        Limits.UNIT_NAME_BYTES
                    ) {
                        onChange(draft.update(unit.id) { it.copy(name = name) })
                    }
                },
                label = { Text(stringResource(Res.string.unit_name)) },
                placeholder = { Text(unitName(unit.copy(name = ""))) },
                supportingText = {
                    Text(
                        stringResource(
                            Res.string.name_bytes,
                            unit.name.utf8Size(),
                            Limits.UNIT_NAME_BYTES
                        )
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.signals))
                    Hint(stringResource(Res.string.signals_hint))
                }
                Switch(unit.signals, onCheckedChange = { on ->
                    onChange(draft.update(unit.id) { it.copy(signals = on) })
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { backend.identify(unit.id) }) {
                    Text(stringResource(Res.string.identify))
                }
                if (!draft.isNew && draft.original?.unit(unit.id) != null) {
                    OutlinedButton(onClick = {
                        replacing = true
                    }) { Text(stringResource(Res.string.replace_unit)) }
                }
            }
            TextButton(onClick = {
                onChange(draft.remove(unit.id))
                onDismiss()
            }) {
                Text(
                    stringResource(Res.string.remove_from_gate),
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (!draft.isNew && draft.original?.unit(unit.id) != null) {
                Hint(stringResource(Res.string.remove_from_gate_hint))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
