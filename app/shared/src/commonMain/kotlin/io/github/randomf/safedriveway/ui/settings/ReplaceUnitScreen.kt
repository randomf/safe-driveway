package io.github.randomf.safedriveway.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.randomf.safedriveway.backend.Backend
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.new_unit_name
import io.github.randomf.safedriveway.resources.no_new_units
import io.github.randomf.safedriveway.resources.replace
import io.github.randomf.safedriveway.resources.replace_confirm_text
import io.github.randomf.safedriveway.resources.replace_confirm_title
import io.github.randomf.safedriveway.resources.replace_hint
import io.github.randomf.safedriveway.resources.replace_title
import io.github.randomf.safedriveway.ui.ConfirmDialog
import io.github.randomf.safedriveway.ui.Hint
import io.github.randomf.safedriveway.ui.ScreenScaffold
import io.github.randomf.safedriveway.ui.setup.NearbyUnitCard
import io.github.randomf.safedriveway.ui.unitName
import org.jetbrains.compose.resources.stringResource

/** Replaces a broken unit with a new one nearby, which takes over its setup. */
@Composable
fun ReplaceUnitScreen(
    backend: Backend,
    passageId: PassageId,
    unitId: UnitId,
    onBack: () -> Unit,
    onReplace: (UnitId) -> Unit
) {
    val open by backend.open.collectAsState()
    val nearby by backend.nearby.collectAsState()
    val unit = open?.setup?.takeIf { it.id == passageId }?.unit(unitId) ?: return
    var chosen by remember { mutableStateOf<UnitId?>(null) }
    val name = unitName(unit)

    ScreenScaffold(stringResource(Res.string.replace_title, name), onBack) {
        Hint(stringResource(Res.string.replace_hint, name))
        if (nearby.unassigned.isEmpty()) {
            Hint(stringResource(Res.string.no_new_units))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        nearby.unassigned.forEach { candidate ->
            NearbyUnitCard(candidate, onIdentify = { backend.identify(candidate.id) }) {
                chosen = candidate.id
            }
        }
    }

    chosen?.let { new ->
        ConfirmDialog(
            title = stringResource(Res.string.replace_confirm_title, name),
            text = stringResource(
                Res.string.replace_confirm_text,
                stringResource(Res.string.new_unit_name, new.short),
                name
            ),
            confirm = stringResource(Res.string.replace),
            onConfirm = {
                chosen = null
                onReplace(new)
            },
            onDismiss = { chosen = null }
        )
    }
}
