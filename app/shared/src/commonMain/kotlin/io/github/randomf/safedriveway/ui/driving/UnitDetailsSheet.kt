package io.github.randomf.safedriveway.ui.driving

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.randomf.safedriveway.passage.Connection
import io.github.randomf.safedriveway.passage.Gap
import io.github.randomf.safedriveway.passage.ReadingStatus
import io.github.randomf.safedriveway.passage.UnitLive
import io.github.randomf.safedriveway.passage.UnitSetup
import io.github.randomf.safedriveway.passage.status
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.connect
import io.github.randomf.safedriveway.resources.detail_battery
import io.github.randomf.safedriveway.resources.detail_firmware
import io.github.randomf.safedriveway.resources.detail_gap
import io.github.randomf.safedriveway.resources.detail_no_battery_gauge
import io.github.randomf.safedriveway.resources.detail_placement
import io.github.randomf.safedriveway.resources.detail_reading
import io.github.randomf.safedriveway.resources.detail_sensor
import io.github.randomf.safedriveway.resources.detail_setup
import io.github.randomf.safedriveway.resources.detail_signal
import io.github.randomf.safedriveway.resources.detail_signal_dbm
import io.github.randomf.safedriveway.resources.detail_unit_id
import io.github.randomf.safedriveway.resources.detail_zero_offset
import io.github.randomf.safedriveway.resources.disconnect
import io.github.randomf.safedriveway.resources.forget
import io.github.randomf.safedriveway.resources.forget_unit
import io.github.randomf.safedriveway.resources.forget_unit_last
import io.github.randomf.safedriveway.resources.forget_unit_text
import io.github.randomf.safedriveway.resources.forget_unit_title
import io.github.randomf.safedriveway.resources.identify
import io.github.randomf.safedriveway.resources.no_data
import io.github.randomf.safedriveway.resources.percent
import io.github.randomf.safedriveway.resources.reading_no_target
import io.github.randomf.safedriveway.resources.reading_too_close
import io.github.randomf.safedriveway.resources.reading_unreliable
import io.github.randomf.safedriveway.resources.replace_unit
import io.github.randomf.safedriveway.resources.sensor_fault
import io.github.randomf.safedriveway.resources.sensor_ok
import io.github.randomf.safedriveway.resources.setup_out_of_date
import io.github.randomf.safedriveway.resources.setup_up_to_date
import io.github.randomf.safedriveway.ui.ConfirmDialog
import io.github.randomf.safedriveway.ui.Hint
import io.github.randomf.safedriveway.ui.centimetres
import io.github.randomf.safedriveway.ui.color
import io.github.randomf.safedriveway.ui.gapNumber
import io.github.randomf.safedriveway.ui.sideName
import io.github.randomf.safedriveway.ui.text
import io.github.randomf.safedriveway.ui.unitName
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A unit's details and what the driver can do with it, over the lower part of the main screen. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UnitDetailsSheet(
    unit: UnitSetup,
    live: UnitLive?,
    gap: Gap,
    passageName: String,
    isLastUnit: Boolean,
    onIdentify: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReplace: () -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmForget by remember { mutableStateOf(false) }
    val status = live.status()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(SheetValue.Hidden, FullSheet)
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row {
                Text(
                    unitName(unit),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    stringResource(status.text),
                    style = MaterialTheme.typography.titleMedium,
                    color = status.color()
                )
            }
            Hint(stringResource(Res.string.detail_placement, unit.gate + 1, sideName(unit.side)))

            val connected = live?.connection == Connection.Connected
            val reading = live?.reading.takeIf { connected }
            Detail(
                Res.string.detail_reading,
                when (reading?.status) {
                    null, ReadingStatus.SensorFault -> stringResource(Res.string.no_data)
                    ReadingStatus.Valid -> centimetres(reading.distanceMm)
                    ReadingStatus.TooClose -> stringResource(Res.string.reading_too_close)
                    ReadingStatus.NoTarget -> stringResource(Res.string.reading_no_target)
                    ReadingStatus.Unreliable -> stringResource(Res.string.reading_unreliable)
                }
            )
            Detail(Res.string.detail_gap, gapNumber(gap), valueColor = gap.level.color())
            Detail(Res.string.detail_zero_offset, centimetres(unit.zeroOffsetMm))
            Detail(
                Res.string.detail_battery,
                when {
                    !connected -> stringResource(Res.string.no_data)

                    live.batteryPercent == null -> stringResource(
                        Res.string.detail_no_battery_gauge
                    )

                    else -> stringResource(Res.string.percent, live.batteryPercent)
                }
            )
            Detail(
                Res.string.detail_signal,
                live?.rssi?.takeIf {
                    connected
                }?.let { stringResource(Res.string.detail_signal_dbm, it) }
                    ?: stringResource(Res.string.no_data)
            )
            Detail(Res.string.detail_firmware, live?.firmware ?: stringResource(Res.string.no_data))
            Detail(
                Res.string.detail_sensor,
                when {
                    !connected -> stringResource(Res.string.no_data)
                    live.sensorFault -> stringResource(Res.string.sensor_fault)
                    else -> stringResource(Res.string.sensor_ok)
                }
            )
            Detail(
                Res.string.detail_setup,
                when {
                    !connected -> stringResource(Res.string.no_data)
                    live.outOfDate -> stringResource(Res.string.setup_out_of_date)
                    else -> stringResource(Res.string.setup_up_to_date)
                }
            )
            Detail(Res.string.detail_unit_id, unit.id.hex.uppercase())

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(onClick = onIdentify, enabled = connected) {
                    Text(stringResource(Res.string.identify))
                }
                if (live?.connection == Connection.Disconnected) {
                    FilledTonalButton(onClick = onConnect) {
                        Text(stringResource(Res.string.connect))
                    }
                } else {
                    FilledTonalButton(onClick = onDisconnect) {
                        Text(stringResource(Res.string.disconnect))
                    }
                }
                OutlinedButton(onClick = onReplace) {
                    Text(stringResource(Res.string.replace_unit))
                }
                OutlinedButton(onClick = { confirmForget = true }, enabled = !isLastUnit) {
                    Text(stringResource(Res.string.forget_unit))
                }
            }
            if (isLastUnit) Hint(stringResource(Res.string.forget_unit_last))
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmForget) {
        ConfirmDialog(
            title = stringResource(Res.string.forget_unit_title, unitName(unit)),
            text = stringResource(Res.string.forget_unit_text, passageName),
            confirm = stringResource(Res.string.forget),
            onConfirm = {
                confirmForget = false
                onForget()
            },
            onDismiss = { confirmForget = false },
            destructive = true
        )
    }
}

@Composable
private fun Detail(
    label: StringResource,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            stringResource(label),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyLarge, color = valueColor)
    }
}
