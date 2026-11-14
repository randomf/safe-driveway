package io.github.randomf.safedriveway.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.randomf.safedriveway.backend.Backend
import io.github.randomf.safedriveway.backend.NearbyUnit
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.back
import io.github.randomf.safedriveway.resources.detail_signal_dbm
import io.github.randomf.safedriveway.resources.done
import io.github.randomf.safedriveway.resources.identify
import io.github.randomf.safedriveway.resources.new_unit_name
import io.github.randomf.safedriveway.resources.next
import io.github.randomf.safedriveway.resources.problem_bad_spacing
import io.github.randomf.safedriveway.resources.problem_bad_thresholds
import io.github.randomf.safedriveway.resources.problem_empty_gate
import io.github.randomf.safedriveway.resources.problem_name_too_long
import io.github.randomf.safedriveway.resources.problem_no_name
import io.github.randomf.safedriveway.resources.problem_no_units
import io.github.randomf.safedriveway.resources.problem_too_many_units
import io.github.randomf.safedriveway.resources.step_counter
import io.github.randomf.safedriveway.ui.ErrorText
import io.github.randomf.safedriveway.ui.Hint
import io.github.randomf.safedriveway.ui.ScreenScaffold
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A step of the wizard, or a section of setup editing. In the wizard it shows where the driver is
 * and goes Next; in editing, Done returns to the overview.
 */
@Composable
fun StepScaffold(
    title: String,
    draft: SetupDraft,
    step: Step,
    wizard: Boolean,
    problems: List<SetupProblem>,
    onBack: () -> Unit,
    onNext: () -> Unit,
    ready: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    ScreenScaffold(
        title,
        onBack,
        bottomBar = {
            if (wizard) {
                OutlinedButton(onClick = onBack) { Text(stringResource(Res.string.back)) }
            }
            Button(onClick = onNext, enabled = problems.isEmpty() && ready) {
                Text(stringResource(if (wizard) Res.string.next else Res.string.done))
            }
        }
    ) {
        if (wizard) {
            Hint(
                stringResource(
                    Res.string.step_counter,
                    draft.steps.indexOf(step) + 1,
                    draft.steps.size
                )
            )
        }
        content()
        problems.forEach { ErrorText(stringResource(it.text)) }
    }
}

val SetupProblem.text: StringResource
    get() = when (this) {
        SetupProblem.NoName -> Res.string.problem_no_name
        SetupProblem.NameTooLong -> Res.string.problem_name_too_long
        SetupProblem.NoUnits -> Res.string.problem_no_units
        SetupProblem.EmptyGate -> Res.string.problem_empty_gate
        SetupProblem.TooManyUnits -> Res.string.problem_too_many_units
        SetupProblem.BadSpacing -> Res.string.problem_bad_spacing
        SetupProblem.BadThresholds -> Res.string.problem_bad_thresholds
    }

/** A unit nearby that belongs to no passage, by its advertised name, with Identify. */
@Composable
fun NearbyUnitCard(unit: NearbyUnit, onIdentify: () -> Unit, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(Res.string.new_unit_name, unit.id.short),
                    style = MaterialTheme.typography.titleMedium
                )
                Hint(stringResource(Res.string.detail_signal_dbm, unit.rssi))
            }
            OutlinedButton(onClick = onIdentify) { Text(stringResource(Res.string.identify)) }
        }
    }
}

/** The screen for [step], in the wizard or in setup editing. */
@Composable
fun SetupStepScreen(
    step: Step,
    draft: SetupDraft,
    onChange: (SetupDraft) -> Unit,
    backend: Backend,
    wizard: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) = when (step) {
    Step.Name -> NameStep(draft, onChange, wizard, onBack, onNext)
    Step.Units -> UnitsStep(draft, onChange, backend, wizard, onBack, onNext)
    Step.Positions -> PositionsStep(draft, onChange, wizard, onBack, onNext)
    Step.ZeroOffsets -> ZeroOffsetsStep(draft, onChange, backend, wizard, onBack, onNext)
    Step.Thresholds -> ThresholdsStep(draft, onChange, wizard, onBack, onNext)
    Step.Pin -> PinStep(draft, onChange, wizard, onBack, onNext)
}
