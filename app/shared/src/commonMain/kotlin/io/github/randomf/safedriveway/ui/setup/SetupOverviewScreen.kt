package io.github.randomf.safedriveway.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.delete
import io.github.randomf.safedriveway.resources.delete_passage
import io.github.randomf.safedriveway.resources.delete_passage_text
import io.github.randomf.safedriveway.resources.delete_passage_title
import io.github.randomf.safedriveway.resources.discard
import io.github.randomf.safedriveway.resources.discard_changes_text
import io.github.randomf.safedriveway.resources.discard_changes_title
import io.github.randomf.safedriveway.resources.gates_count
import io.github.randomf.safedriveway.resources.list_separator
import io.github.randomf.safedriveway.resources.new_pin_set
import io.github.randomf.safedriveway.resources.one_gate
import io.github.randomf.safedriveway.resources.positions_summary
import io.github.randomf.safedriveway.resources.save
import io.github.randomf.safedriveway.resources.setup_title
import io.github.randomf.safedriveway.resources.step_name
import io.github.randomf.safedriveway.resources.step_pin_change
import io.github.randomf.safedriveway.resources.step_positions
import io.github.randomf.safedriveway.resources.step_thresholds
import io.github.randomf.safedriveway.resources.step_units
import io.github.randomf.safedriveway.resources.step_zero_offsets
import io.github.randomf.safedriveway.resources.thresholds_summary
import io.github.randomf.safedriveway.resources.units_and_gates
import io.github.randomf.safedriveway.resources.units_count
import io.github.randomf.safedriveway.resources.unsaved_hint
import io.github.randomf.safedriveway.ui.ConfirmDialog
import io.github.randomf.safedriveway.ui.DangerButton
import io.github.randomf.safedriveway.ui.ErrorText
import io.github.randomf.safedriveway.ui.Hint
import io.github.randomf.safedriveway.ui.ScreenScaffold
import io.github.randomf.safedriveway.ui.halfCentimetres
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * An existing passage's setup, after the PIN: its sections, each opening the wizard's screen for
 * it. Nothing reaches the units until Save; Discard locks them unchanged. Whether it asks to
 * discard the changes is hoisted, so that system back asks too.
 */
@Composable
fun SetupOverviewScreen(
    draft: SetupDraft,
    confirmingDiscard: Boolean,
    onConfirmingDiscard: (Boolean) -> Unit,
    onSection: (Step) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDelete: () -> Unit
) {
    val setup = draft.setup
    var confirmDelete by remember { mutableStateOf(false) }
    val problems = setup.allProblems()

    ScreenScaffold(
        stringResource(Res.string.setup_title, draft.original?.name.orEmpty()),
        onBack = { if (draft.changed) onConfirmingDiscard(true) else onDiscard() },
        bottomBar = {
            OutlinedButton(onClick = {
                if (draft.changed) onConfirmingDiscard(true) else onDiscard()
            }) {
                Text(stringResource(Res.string.discard))
            }
            Button(onClick = onSave, enabled = draft.changed && problems.isEmpty()) {
                Text(stringResource(Res.string.save))
            }
        }
    ) {
        Section(stringResource(Res.string.step_name), setup.name) { onSection(Step.Name) }
        Section(
            stringResource(Res.string.step_units),
            stringResource(
                Res.string.units_and_gates,
                pluralStringResource(Res.plurals.units_count, setup.units.size, setup.units.size),
                pluralStringResource(Res.plurals.gates_count, setup.gateCount, setup.gateCount)
            )
        ) { onSection(Step.Units) }
        if (setup.gateCount > 1) {
            Section(
                stringResource(Res.string.step_positions),
                stringResource(
                    Res.string.positions_summary,
                    draft.spacingsMm.joinToString(stringResource(Res.string.list_separator)) {
                        "${it / 10}"
                    }
                )
            ) { onSection(Step.Positions) }
        } else {
            Section(
                stringResource(Res.string.step_positions),
                stringResource(Res.string.one_gate),
                null
            )
        }
        Section(stringResource(Res.string.step_zero_offsets), "") { onSection(Step.ZeroOffsets) }
        Section(
            stringResource(Res.string.step_thresholds),
            stringResource(
                Res.string.thresholds_summary,
                halfCentimetres(setup.warningGapMm),
                halfCentimetres(setup.criticalGapMm)
            )
        ) { onSection(Step.Thresholds) }
        Section(
            stringResource(Res.string.step_pin_change),
            if (draft.newPin != null) stringResource(Res.string.new_pin_set) else ""
        ) { onSection(Step.Pin) }

        problems.forEach { ErrorText(stringResource(it.text)) }
        if (draft.changed) Hint(stringResource(Res.string.unsaved_hint))
        DangerButton(stringResource(Res.string.delete_passage), { confirmDelete = true })
    }

    if (confirmingDiscard) {
        ConfirmDialog(
            title = stringResource(Res.string.discard_changes_title),
            text = stringResource(Res.string.discard_changes_text),
            confirm = stringResource(Res.string.discard),
            onConfirm = onDiscard,
            onDismiss = { onConfirmingDiscard(false) },
            destructive = true
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(
                Res.string.delete_passage_title,
                draft.original?.name.orEmpty()
            ),
            text = stringResource(Res.string.delete_passage_text),
            confirm = stringResource(Res.string.delete),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
            destructive = true
        )
    }
}

@Composable
private fun Section(title: String, value: String, onClick: (() -> Unit)?) {
    val content = @Composable {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (value.isNotEmpty()) Hint(value)
            }
            if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        Card(Modifier.fillMaxWidth()) { content() }
    }
}
