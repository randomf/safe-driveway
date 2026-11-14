package io.github.randomf.safedriveway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.randomf.safedriveway.demo.DemoBackend
import io.github.randomf.safedriveway.demo.DemoWorld
import io.github.randomf.safedriveway.passage.Connection
import io.github.randomf.safedriveway.passage.Direction
import io.github.randomf.safedriveway.passage.DrivingView
import io.github.randomf.safedriveway.passage.End
import io.github.randomf.safedriveway.passage.Reading
import io.github.randomf.safedriveway.passage.ReadingStatus
import io.github.randomf.safedriveway.passage.Steer
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.passage.UnitLive
import io.github.randomf.safedriveway.passage.drivingView
import io.github.randomf.safedriveway.ui.driving.PassageView
import io.github.randomf.safedriveway.ui.driving.SteeringArrow
import io.github.randomf.safedriveway.ui.driving.UnitChips
import io.github.randomf.safedriveway.ui.setup.SetupDraft
import io.github.randomf.safedriveway.ui.setup.SetupOverviewScreen
import io.github.randomf.safedriveway.ui.setup.ThresholdsStep
import io.github.randomf.safedriveway.ui.setup.UnitsStep
import io.github.randomf.safedriveway.ui.start.NoticeScreen
import io.github.randomf.safedriveway.ui.start.PassagesScreen
import kotlin.time.TimeSource

// Previews of the main states, for Android Studio. The running app shows the rest through the
// demo panel.

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    AppTheme { Surface(Modifier.fillMaxSize(), content = content) }
}

/** The garage with these distances from its units, in its demo order, null for no car. */
private fun garage(vararg distancesMm: Int?, battery: Int? = 80): DrivingView {
    val now = TimeSource.Monotonic.markNow()
    val setup = DemoWorld.garage
    val units: Map<UnitId, UnitLive> = setup.units.zip(
        distancesMm.toList()
    ).associate { (unit, mm) ->
        unit.id to UnitLive(
            Connection.Connected,
            batteryPercent = battery,
            reading = Reading(
                mm ?: 0,
                if (mm == null) ReadingStatus.NoTarget else ReadingStatus.Valid,
                now
            )
        )
    }
    return drivingView(setup, units, Direction.Forwards, End.Usual, now)
}

@Preview
@Composable
private fun PassageBeforeTheCarPreview() = PreviewSurface {
    PassageView(garage(null, null, null, null), Modifier.padding(16.dp))
}

@Preview
@Composable
private fun PassageWithTheCarPreview() = PreviewSurface {
    // Gate 1: 12.5 and 26.0 cm; gate 2: 4.0 cm on the left, critical.
    PassageView(garage(190, 330, 160, 340), Modifier.padding(16.dp))
}

@Preview
@Composable
private fun UnitChipsPreview() = PreviewSurface {
    UnitChips(garage(null, null, null, null, battery = 12), onClick = {}, Modifier.padding(16.dp))
}

@Preview
@Composable
private fun SteeringArrowsPreview() = PreviewSurface {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Steer.entries.forEach { SteeringArrow(it, Color.White, Modifier.size(96.dp)) }
    }
}

@Preview
@Composable
private fun NoticePreview() = PreviewSurface { NoticeScreen {} }

@Preview
@Composable
private fun PassagesPreview() = PreviewSurface {
    val scope = rememberCoroutineScope()
    val backend = remember { DemoBackend(scope) }
    PassagesScreen(backend, autoOpen = false, onRoute = {
    }, onOpen = {}, onNewPassage = {}, onAbout = {})
}

@Preview
@Composable
private fun SetupOverviewPreview() = PreviewSurface {
    val draft = SetupDraft.forEdit(DemoWorld.garage)
    SetupOverviewScreen(draft, false, {
    }, onSection = {}, onSave = {}, onDiscard = {}, onDelete = {})
}

@Preview
@Composable
private fun UnitsBoardPreview() = PreviewSurface {
    val scope = rememberCoroutineScope()
    val backend = remember { DemoBackend(scope) }
    val draft = SetupDraft.forEdit(DemoWorld.garage)
    UnitsStep(draft, {}, backend, wizard = true, onBack = {}, onNext = {})
}

@Preview
@Composable
private fun ThresholdsPreview() = PreviewSurface {
    val draft = SetupDraft.forNew()
    Column { ThresholdsStep(draft, {}, wizard = true, onBack = {}, onNext = {}) }
}
