package io.github.randomf.safedriveway.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.randomf.safedriveway.passage.GapLevel
import io.github.randomf.safedriveway.passage.UnitStatus

/** High-contrast colours on near black, to be read at arm's length in a phone holder. */
object AppColors {
    val Background = Color(0xFF0B0D10)
    val Clear = Color(0xFF4ADE80)
    val Warning = Color(0xFFFFA53A)
    val Critical = Color(0xFFFF4B4B)
    val NoData = Color(0xFF8C939D)
    val Wall = Color(0xFFD9DEE5)
    val Car = Color(0xFFB8C4D6)
}

fun GapLevel.color(): Color = when (this) {
    GapLevel.Clear -> AppColors.Clear
    GapLevel.Warning -> AppColors.Warning
    GapLevel.Critical -> AppColors.Critical
    GapLevel.NoData -> AppColors.NoData
}

/** The colour of a unit's status dot: green when it works, orange when it needs care. */
fun UnitStatus.color(): Color = when (this) {
    UnitStatus.Connected -> AppColors.Clear
    UnitStatus.LowBattery, UnitStatus.Reconnecting, UnitStatus.PhoneLimit -> AppColors.Warning
    UnitStatus.SensorFault, UnitStatus.NeedsNewerApp, UnitStatus.InUse -> AppColors.Critical
    UnitStatus.Searching, UnitStatus.Connecting, UnitStatus.Disconnected -> AppColors.NoData
}

private val colors = darkColorScheme(
    primary = Color(0xFF9CC2FF),
    onPrimary = Color(0xFF00213F),
    primaryContainer = Color(0xFF1E3A5C),
    onPrimaryContainer = Color(0xFFD4E3FF),
    background = AppColors.Background,
    surface = AppColors.Background,
    surfaceContainerLowest = Color(0xFF07080A),
    surfaceContainerLow = Color(0xFF12151A),
    surfaceContainer = Color(0xFF171B21),
    surfaceContainerHigh = Color(0xFF1E232A),
    surfaceContainerHighest = Color(0xFF262C34),
    error = AppColors.Critical
)

/** The app is always dark (app/features.md). */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

/** Digits that keep their width, so that a changing number doesn't jump around. */
val Digits = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold)
