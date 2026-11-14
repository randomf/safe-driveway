package io.github.randomf.safedriveway.ui.driving

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.randomf.safedriveway.passage.DrivingView
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.passage.UnitOnScreen
import io.github.randomf.safedriveway.passage.UnitStatus
import io.github.randomf.safedriveway.passage.status
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.percent
import io.github.randomf.safedriveway.ui.StatusDot
import io.github.randomf.safedriveway.ui.color
import io.github.randomf.safedriveway.ui.text
import io.github.randomf.safedriveway.ui.unitName
import org.jetbrains.compose.resources.stringResource

/**
 * One chip per unit, where the unit is drawn: the driver's left wall in the left column, the right
 * wall in the right, the top gate first.
 */
@Composable
fun UnitChips(view: DrivingView, onClick: (UnitId) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        view.gates.reversed().forEach { gate ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(gate.left, gate.right).forEach { unit ->
                    if (unit == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        UnitChip(unit, { onClick(unit.unit.id) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun UnitChip(unit: UnitOnScreen, onClick: () -> Unit, modifier: Modifier) {
    val status = unit.live.status()
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (status == UnitStatus.Connected) null else BorderStroke(1.dp, status.color())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusDot(status.color())
            Text(
                unitName(unit.unit),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val battery = unit.live?.batteryPercent
            val trailing = when (status) {
                UnitStatus.Connected -> battery?.let { stringResource(Res.string.percent, it) }
                UnitStatus.LowBattery -> stringResource(Res.string.percent, battery ?: 0)
                else -> stringResource(status.text)
            }
            if (trailing != null) {
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status == UnitStatus.Connected) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        status.color()
                    },
                    maxLines = 1
                )
            }
        }
    }
}
