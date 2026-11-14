package io.github.randomf.safedriveway.ui

import androidx.compose.runtime.Composable
import io.github.randomf.safedriveway.passage.Gap
import io.github.randomf.safedriveway.passage.Side
import io.github.randomf.safedriveway.passage.UnitSetup
import io.github.randomf.safedriveway.passage.UnitStatus
import io.github.randomf.safedriveway.passage.formatHalfCentimetres
import io.github.randomf.safedriveway.passage.formatMillimetresAsCentimetres
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.centimetres
import io.github.randomf.safedriveway.resources.decimal_separator
import io.github.randomf.safedriveway.resources.gap_less_than
import io.github.randomf.safedriveway.resources.no_data
import io.github.randomf.safedriveway.resources.side_left
import io.github.randomf.safedriveway.resources.side_right
import io.github.randomf.safedriveway.resources.status_connected
import io.github.randomf.safedriveway.resources.status_connecting
import io.github.randomf.safedriveway.resources.status_disconnected
import io.github.randomf.safedriveway.resources.status_in_use
import io.github.randomf.safedriveway.resources.status_low_battery
import io.github.randomf.safedriveway.resources.status_needs_newer_app
import io.github.randomf.safedriveway.resources.status_phone_limit
import io.github.randomf.safedriveway.resources.status_reconnecting
import io.github.randomf.safedriveway.resources.status_searching
import io.github.randomf.safedriveway.resources.status_sensor_fault
import io.github.randomf.safedriveway.resources.unit_default_name
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The decimal separator of the app's language: "12.5" in English, "12,5" in Czech. */
@Composable
fun decimalSeparator(): String = stringResource(Res.string.decimal_separator)

/** A gap in 0.5 cm steps without its unit, "—" for no data, or "< 0.0" when too close. */
@Composable
fun gapNumber(gap: Gap): String {
    val mm = gap.mm ?: return stringResource(Res.string.no_data)
    val number = formatHalfCentimetres(mm, decimalSeparator())
    return if (gap.tooClose) stringResource(Res.string.gap_less_than, number) else number
}

/** A distance in cm with one decimal and its unit, such as "6.5 cm". */
@Composable
fun centimetres(mm: Int): String =
    stringResource(Res.string.centimetres, formatMillimetresAsCentimetres(mm, decimalSeparator()))

/** A distance in 0.5 cm steps with its unit, such as "15.0 cm". */
@Composable
fun halfCentimetres(mm: Int): String =
    stringResource(Res.string.centimetres, formatHalfCentimetres(mm, decimalSeparator()))

@Composable
fun sideName(side: Side): String = stringResource(
    when (side) {
        Side.Left -> Res.string.side_left
        Side.Right -> Res.string.side_right
    }
)

/** The unit's name, or "Gate 1 left" when it has none. Gates are numbered from 1 for the driver. */
@Composable
fun unitName(unit: UnitSetup): String = unit.name.ifEmpty {
    stringResource(Res.string.unit_default_name, unit.gate + 1, sideName(unit.side))
}

val UnitStatus.text: StringResource
    get() = when (this) {
        UnitStatus.Searching -> Res.string.status_searching
        UnitStatus.Connecting -> Res.string.status_connecting
        UnitStatus.Connected -> Res.string.status_connected
        UnitStatus.Reconnecting -> Res.string.status_reconnecting
        UnitStatus.PhoneLimit -> Res.string.status_phone_limit
        UnitStatus.InUse -> Res.string.status_in_use
        UnitStatus.NeedsNewerApp -> Res.string.status_needs_newer_app
        UnitStatus.Disconnected -> Res.string.status_disconnected
        UnitStatus.LowBattery -> Res.string.status_low_battery
        UnitStatus.SensorFault -> Res.string.status_sensor_fault
    }
