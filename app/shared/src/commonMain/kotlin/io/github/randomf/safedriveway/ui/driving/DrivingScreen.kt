package io.github.randomf.safedriveway.ui.driving

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.randomf.safedriveway.KeepScreenOn
import io.github.randomf.safedriveway.backend.Backend
import io.github.randomf.safedriveway.passage.Direction
import io.github.randomf.safedriveway.passage.DrivingView
import io.github.randomf.safedriveway.passage.Entry
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.Steer
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.passage.UnitOnScreen
import io.github.randomf.safedriveway.passage.drivingView
import io.github.randomf.safedriveway.passage.notices
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.centimetres_unit
import io.github.randomf.safedriveway.resources.direction_forwards
import io.github.randomf.safedriveway.resources.direction_reversing
import io.github.randomf.safedriveway.resources.passage_settings
import io.github.randomf.safedriveway.resources.paused
import io.github.randomf.safedriveway.resources.reconnect
import io.github.randomf.safedriveway.resources.steer_left
import io.github.randomf.safedriveway.resources.steer_right
import io.github.randomf.safedriveway.resources.steer_straight
import io.github.randomf.safedriveway.resources.units_ready
import io.github.randomf.safedriveway.ui.AppColors
import io.github.randomf.safedriveway.ui.AppTopBar
import io.github.randomf.safedriveway.ui.Digits
import io.github.randomf.safedriveway.ui.color
import io.github.randomf.safedriveway.ui.gapNumber
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.scan
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How often the screen checks for readings that have gone stale. */
private val STALE_CHECK = 100.milliseconds

/**
 * The main screen, which the driver reads while driving through: the steering suggestion and the
 * smallest gap, the passage with every gap, and a chip per unit (app/features.md).
 */
@Composable
fun DrivingScreen(
    backend: Backend,
    passageId: PassageId,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onReplaceUnit: (UnitId) -> Unit,
    onForgetUnit: (UnitId) -> Unit,
    onBringUpToDate: () -> Unit
) {
    val open by backend.open.collectAsState()
    val remembered by backend.remembered.collectAsState()
    val passage = open?.takeIf { it.setup.id == passageId }
    val name =
        passage?.setup?.name ?: remembered.find { it.setup.id == passageId }?.setup?.name.orEmpty()
    val usual = remembered.find { it.setup.id == passageId }?.preferences?.usualDirection
    var direction by rememberSaveable(passageId) { mutableStateOf(usual ?: Direction.Forwards) }
    // The entry end carries over from reading to reading, and when the driver flips the switch.
    val view by produceState<DrivingView?>(null, passageId) {
        val ticks = flow {
            while (true) {
                emit(Unit)
                delay(STALE_CHECK)
            }
        }
        combine(backend.open, ticks, snapshotFlow { direction }) { open, _, direction ->
            open?.takeIf { it.setup.id == passageId } to direction
        }.scan(Entry() to null as DrivingView?) { (entry, _), (passage, direction) ->
            if (passage == null) {
                Entry() to null
            } else {
                val now = TimeSource.Monotonic.markNow()
                val next = entry.next(passage.setup, passage.units, now)
                next to drivingView(passage.setup, passage.units, direction, next.end, now)
            }
        }.collect { (_, view) -> value = view }
    }
    var details by remember { mutableStateOf<UnitId?>(null) }
    var showNotices by remember { mutableStateOf(false) }
    val notices = passage?.let { notices(it.setup, it.units) }.orEmpty()
    val paused = passage?.paused == true
    // The phone stays awake for the driver, until the app pauses after minutes with no car.
    if (!paused) KeepScreenOn()

    val current = view
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val topBar = @Composable {
            AppTopBar(name, onBack, onTitleClick = onBack) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, stringResource(Res.string.passage_settings))
                }
            }
        }
        val strip = @Composable { NoticeStrip(notices, onClick = { showNotices = true }) }
        val switch = @Composable {
            DirectionSwitch(direction, { direction = it }, Modifier.padding(horizontal = 16.dp))
        }
        if (current == null) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                topBar()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (landscape) {
            Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                PassageView(current, Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp))
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    topBar()
                    strip()
                    Header(
                        current,
                        paused,
                        backend::reconnectAll,
                        Modifier.fillMaxWidth().weight(1f)
                    )
                    switch()
                    UnitChips(current, { details = it }, Modifier.padding(12.dp))
                }
            }
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                topBar()
                strip()
                Header(
                    current,
                    paused,
                    backend::reconnectAll,
                    Modifier.fillMaxWidth().height(150.dp)
                )
                switch()
                PassageView(current, Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp))
                UnitChips(current, { details = it }, Modifier.padding(12.dp))
            }
        }
        if (current?.critical == true) CriticalBorder()
    }

    val shown = details?.let { id ->
        current?.gates?.flatMap { it.units }?.find { it.unit.id == id }
    }
    if (shown != null && passage != null) {
        UnitDetailsSheet(
            unit = shown.unit,
            live = shown.live,
            gap = shown.gap,
            passageName = name,
            isLastUnit = passage.setup.units.size == 1,
            onIdentify = { backend.identify(shown.unit.id) },
            onConnect = { backend.connect(shown.unit.id) },
            onDisconnect = { backend.disconnect(shown.unit.id) },
            onReplace = {
                details = null
                onReplaceUnit(shown.unit.id)
            },
            onForget = {
                details = null
                onForgetUnit(shown.unit.id)
            },
            onDismiss = { details = null }
        )
    }
    if (showNotices && notices.isNotEmpty()) {
        NoticesSheet(
            notices,
            onUpdate = {
                showNotices = false
                onBringUpToDate()
            },
            onDismiss = { showNotices = false }
        )
    }
}

/**
 * The arrow and the smallest gap while a gate sees the car; before that, how many units are ready;
 * and once the app has [paused], a button to reconnect.
 */
@Composable
private fun Header(
    view: DrivingView,
    paused: Boolean,
    onReconnect: () -> Unit,
    modifier: Modifier
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val steer = view.steer
        val smallest = view.smallest
        if (paused) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(Res.string.paused),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Button(onClick = onReconnect, Modifier.height(56.dp)) {
                    Text(stringResource(Res.string.reconnect))
                }
            }
        } else if (steer == null || smallest == null) {
            Text(
                pluralStringResource(
                    Res.plurals.units_ready,
                    view.totalUnits,
                    view.readyUnits,
                    view.totalUnits
                ),
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SteeringArrow(steer, MaterialTheme.colorScheme.onSurface, Modifier.size(96.dp))
                    Text(
                        stringResource(
                            when (steer) {
                                Steer.Left -> Res.string.steer_left
                                Steer.Straight -> Res.string.steer_straight
                                Steer.Right -> Res.string.steer_right
                            }
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                SmallestGap(smallest)
            }
        }
    }
}

@Composable
private fun SmallestGap(unit: UnitOnScreen) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(gapNumber(unit.gap), style = Digits, fontSize = 72.sp, color = unit.gap.level.color())
        Text(
            stringResource(Res.string.centimetres_unit),
            Modifier.padding(start = 4.dp, bottom = 14.dp),
            style = MaterialTheme.typography.titleLarge,
            color = unit.gap.level.color()
        )
    }
}

@Composable
private fun DirectionSwitch(
    direction: Direction,
    onChange: (Direction) -> Unit,
    modifier: Modifier
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        Direction.entries.forEachIndexed { i, option ->
            SegmentedButton(
                selected = direction == option,
                onClick = { onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(i, Direction.entries.size),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    stringResource(
                        when (option) {
                            Direction.Forwards -> Res.string.direction_forwards
                            Direction.Reversing -> Res.string.direction_reversing
                        }
                    )
                )
            }
        }
    }
}

/** Below the critical gap, the screen's border flashes red. */
@Composable
private fun CriticalBorder() {
    val flash by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(250, easing = LinearEasing), RepeatMode.Reverse)
    )
    Spacer(Modifier.fillMaxSize().border(10.dp, AppColors.Critical.copy(alpha = flash)))
}
