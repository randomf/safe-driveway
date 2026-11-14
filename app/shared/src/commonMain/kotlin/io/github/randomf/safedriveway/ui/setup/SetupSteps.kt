package io.github.randomf.safedriveway.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.randomf.safedriveway.backend.Backend
import io.github.randomf.safedriveway.passage.Limits
import io.github.randomf.safedriveway.passage.formatMillimetresAsCentimetres
import io.github.randomf.safedriveway.passage.parseCentimetres
import io.github.randomf.safedriveway.passage.utf8Size
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.critical_gap
import io.github.randomf.safedriveway.resources.decrease
import io.github.randomf.safedriveway.resources.detail_gap
import io.github.randomf.safedriveway.resources.detail_placement
import io.github.randomf.safedriveway.resources.detail_reading
import io.github.randomf.safedriveway.resources.gate_number
import io.github.randomf.safedriveway.resources.increase
import io.github.randomf.safedriveway.resources.name_bytes
import io.github.randomf.safedriveway.resources.name_hint
import io.github.randomf.safedriveway.resources.no_data
import io.github.randomf.safedriveway.resources.passage_name
import io.github.randomf.safedriveway.resources.pin_change_hint
import io.github.randomf.safedriveway.resources.pin_hint
import io.github.randomf.safedriveway.resources.positions_after
import io.github.randomf.safedriveway.resources.positions_hint
import io.github.randomf.safedriveway.resources.positions_start
import io.github.randomf.safedriveway.resources.set_zero
import io.github.randomf.safedriveway.resources.step_name
import io.github.randomf.safedriveway.resources.step_pin
import io.github.randomf.safedriveway.resources.step_pin_change
import io.github.randomf.safedriveway.resources.step_positions
import io.github.randomf.safedriveway.resources.step_thresholds
import io.github.randomf.safedriveway.resources.step_zero_offsets
import io.github.randomf.safedriveway.resources.thresholds_hint
import io.github.randomf.safedriveway.resources.warning_gap
import io.github.randomf.safedriveway.resources.zero_hint
import io.github.randomf.safedriveway.resources.zero_offset_cm
import io.github.randomf.safedriveway.ui.Digits
import io.github.randomf.safedriveway.ui.Hint
import io.github.randomf.safedriveway.ui.NewPinFields
import io.github.randomf.safedriveway.ui.centimetres
import io.github.randomf.safedriveway.ui.decimalSeparator
import io.github.randomf.safedriveway.ui.halfCentimetres
import io.github.randomf.safedriveway.ui.sideName
import io.github.randomf.safedriveway.ui.unitName
import org.jetbrains.compose.resources.stringResource

@Composable
fun NameStep(
    draft: SetupDraft,
    onChange: (SetupDraft) -> Unit,
    wizard: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val name = draft.setup.name
    StepScaffold(
        stringResource(Res.string.step_name),
        draft,
        Step.Name,
        wizard,
        draft.setup.problems(Step.Name).takeIf { name.isNotEmpty() }.orEmpty(),
        onBack,
        onNext,
        ready = name.isNotBlank()
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { onChange(draft.edit { setup -> setup.copy(name = it) }) },
            label = { Text(stringResource(Res.string.passage_name)) },
            supportingText = {
                Text(
                    stringResource(
                        Res.string.name_bytes,
                        name.utf8Size(),
                        Limits.PASSAGE_NAME_BYTES
                    )
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Hint(stringResource(Res.string.name_hint))
    }
}

/** The distance from each gate to the one before it, as a tape measure gives it. */
@Composable
fun PositionsStep(
    draft: SetupDraft,
    onChange: (SetupDraft) -> Unit,
    wizard: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val texts =
        remember {
            mutableStateListOf(*draft.spacingsMm.map { (it / 10).toString() }.toTypedArray())
        }
    StepScaffold(
        stringResource(Res.string.step_positions),
        draft,
        Step.Positions,
        wizard,
        draft.setup.problems(Step.Positions),
        onBack,
        onNext
    ) {
        Hint(stringResource(Res.string.positions_hint))
        Text(
            stringResource(Res.string.positions_start, stringResource(Res.string.gate_number, 1)),
            style = MaterialTheme.typography.titleMedium
        )
        texts.forEachIndexed { i, text ->
            OutlinedTextField(
                value = text,
                onValueChange = { value ->
                    texts[i] = value.filter(Char::isDigit).take(4)
                    onChange(draft.withSpacings(texts.map { (it.toIntOrNull() ?: 0) * 10 }))
                },
                label = { Text(stringResource(Res.string.gate_number, i + 2)) },
                suffix = { Text(stringResource(Res.string.positions_after, i + 1)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Each unit's zero offset: typed in, or taken from a board held on the line with Set zero. Live
 * readings show the result.
 */
@Composable
fun ZeroOffsetsStep(
    draft: SetupDraft,
    onChange: (SetupDraft) -> Unit,
    backend: Backend,
    wizard: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val units = draft.setup.units.sortedWith(compareBy({ it.gate }, { it.side }))
    DisposableEffect(units.map { it.id }) {
        backend.startSetupReadings(units.map { it.id })
        onDispose { backend.stopSetupReadings() }
    }
    val readings by backend.setupReadings.collectAsState()
    val separator = decimalSeparator()
    val texts = remember {
        mutableStateMapOf(
            *units.map {
                it.id to formatMillimetresAsCentimetres(it.zeroOffsetMm, separator)
            }.toTypedArray()
        )
    }
    StepScaffold(
        stringResource(Res.string.step_zero_offsets),
        draft,
        Step.ZeroOffsets,
        wizard,
        emptyList(),
        onBack,
        onNext
    ) {
        Hint(stringResource(Res.string.zero_hint))
        units.forEach { unit ->
            val reading = readings[unit.id]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(unitName(unit), style = MaterialTheme.typography.titleMedium)
                    if (unit.name.isNotEmpty()) {
                        Hint(
                            stringResource(
                                Res.string.detail_placement,
                                unit.gate + 1,
                                sideName(unit.side)
                            )
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        LiveValue(
                            stringResource(Res.string.detail_reading),
                            reading?.let { centimetres(it.distanceMm) }
                        )
                        LiveValue(
                            stringResource(Res.string.detail_gap),
                            reading?.let { centimetres(it.distanceMm - unit.zeroOffsetMm) }
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = texts[unit.id].orEmpty(),
                            onValueChange = { value ->
                                texts[unit.id] = value
                                parseCentimetres(value)?.let { mm ->
                                    onChange(
                                        draft.update(unit.id) {
                                            it.copy(zeroOffsetMm = mm.coerceAtLeast(0))
                                        }
                                    )
                                }
                            },
                            label = { Text(stringResource(Res.string.zero_offset_cm)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = {
                                val mm = reading?.distanceMm ?: return@FilledTonalButton
                                onChange(draft.update(unit.id) { it.copy(zeroOffsetMm = mm) })
                                texts[unit.id] = formatMillimetresAsCentimetres(mm, separator)
                            },
                            enabled = reading != null
                        ) { Text(stringResource(Res.string.set_zero)) }
                    }
                }
            }
        }
    }
}

@Composable
fun ThresholdsStep(
    draft: SetupDraft,
    onChange: (SetupDraft) -> Unit,
    wizard: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val setup = draft.setup
    StepScaffold(
        stringResource(Res.string.step_thresholds),
        draft,
        Step.Thresholds,
        wizard,
        setup.problems(Step.Thresholds),
        onBack,
        onNext
    ) {
        Hint(stringResource(Res.string.thresholds_hint))
        Stepper(stringResource(Res.string.warning_gap), setup.warningGapMm) {
            onChange(draft.edit { setup -> setup.copy(warningGapMm = it) })
        }
        Stepper(stringResource(Res.string.critical_gap), setup.criticalGapMm) {
            onChange(draft.edit { setup -> setup.copy(criticalGapMm = it) })
        }
    }
}

/** A live value with its label, or "—" until it arrives. */
@Composable
private fun LiveValue(label: String, value: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: stringResource(Res.string.no_data), style = Digits)
    }
}

/** A gap set in 0.5 cm steps with − and +. */
@Composable
private fun Stepper(label: String, mm: Int, onChange: (Int) -> Unit) {
    val decrease = stringResource(Res.string.decrease)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        OutlinedIconButton(onClick = {
            onChange((mm - Limits.THRESHOLD_STEP_MM).coerceAtLeast(0))
        }) {
            // The icons have no minus, so it's a character, read out as a word.
            Text(
                "−",
                Modifier.clearAndSetSemantics { contentDescription = decrease },
                style = MaterialTheme.typography.titleLarge
            )
        }
        Text(
            halfCentimetres(mm),
            Modifier.width(110.dp),
            style = Digits.merge(MaterialTheme.typography.titleLarge),
            textAlign = TextAlign.Center
        )
        OutlinedIconButton(onClick = { onChange(mm + Limits.THRESHOLD_STEP_MM) }) {
            Icon(Icons.Filled.Add, stringResource(Res.string.increase))
        }
    }
}

/** The passage's PIN: set in the wizard, changed in setup editing. */
@Composable
fun PinStep(
    draft: SetupDraft,
    onChange: (SetupDraft) -> Unit,
    wizard: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    var pin by remember { mutableStateOf(draft.newPin) }
    StepScaffold(
        stringResource(if (draft.isNew) Res.string.step_pin else Res.string.step_pin_change),
        draft,
        Step.Pin,
        wizard,
        emptyList(),
        onBack,
        onNext = {
            onChange(draft.copy(newPin = pin))
            onNext()
        },
        ready = pin != null
    ) {
        Hint(stringResource(Res.string.pin_hint))
        if (!draft.isNew) Hint(stringResource(Res.string.pin_change_hint))
        NewPinFields(onChange = { pin = it })
    }
}
