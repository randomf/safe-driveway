package io.github.randomf.safedriveway.ui.driving

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.randomf.safedriveway.passage.Notice
import io.github.randomf.safedriveway.passage.NoticeKind
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.notice_in_use
import io.github.randomf.safedriveway.resources.notice_in_use_help
import io.github.randomf.safedriveway.resources.notice_lost
import io.github.randomf.safedriveway.resources.notice_lost_help
import io.github.randomf.safedriveway.resources.notice_low_battery
import io.github.randomf.safedriveway.resources.notice_low_battery_help
import io.github.randomf.safedriveway.resources.notice_more
import io.github.randomf.safedriveway.resources.notice_needs_newer_app
import io.github.randomf.safedriveway.resources.notice_needs_newer_app_help
import io.github.randomf.safedriveway.resources.notice_out_of_date
import io.github.randomf.safedriveway.resources.notice_out_of_date_help
import io.github.randomf.safedriveway.resources.notice_phone_limit
import io.github.randomf.safedriveway.resources.notice_phone_limit_help
import io.github.randomf.safedriveway.resources.notice_sensor_fault
import io.github.randomf.safedriveway.resources.notice_sensor_fault_help
import io.github.randomf.safedriveway.resources.notices_title
import io.github.randomf.safedriveway.resources.update_units
import io.github.randomf.safedriveway.ui.AppColors
import io.github.randomf.safedriveway.ui.Hint
import io.github.randomf.safedriveway.ui.unitName
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A sheet that opens all the way, so that its buttons are in sight at once. */
@OptIn(ExperimentalMaterial3Api::class)
internal val FullSheet = setOf(SheetValue.Hidden, SheetValue.Expanded)

private val NoticeKind.color: Color
    get() = when (this) {
        NoticeKind.Lost, NoticeKind.SensorFault, NoticeKind.NeedsNewerApp, NoticeKind.InUse ->
            AppColors.Critical

        else -> AppColors.Warning
    }

private val NoticeKind.title: StringResource
    get() = when (this) {
        NoticeKind.Lost -> Res.string.notice_lost
        NoticeKind.SensorFault -> Res.string.notice_sensor_fault
        NoticeKind.NeedsNewerApp -> Res.string.notice_needs_newer_app
        NoticeKind.InUse -> Res.string.notice_in_use
        NoticeKind.PhoneLimit -> Res.string.notice_phone_limit
        NoticeKind.OutOfDate -> Res.string.notice_out_of_date
        NoticeKind.LowBattery -> Res.string.notice_low_battery
    }

private val NoticeKind.help: StringResource
    get() = when (this) {
        NoticeKind.Lost -> Res.string.notice_lost_help
        NoticeKind.SensorFault -> Res.string.notice_sensor_fault_help
        NoticeKind.NeedsNewerApp -> Res.string.notice_needs_newer_app_help
        NoticeKind.InUse -> Res.string.notice_in_use_help
        NoticeKind.PhoneLimit -> Res.string.notice_phone_limit_help
        NoticeKind.OutOfDate -> Res.string.notice_out_of_date_help
        NoticeKind.LowBattery -> Res.string.notice_low_battery_help
    }

@Composable
private fun Notice.title(): String =
    stringResource(kind.title, unitName(unit), live.batteryPercent ?: 0)

/** One line under the top bar: the most urgent notice, and how many more there are. */
@Composable
fun NoticeStrip(notices: List<Notice>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val first = notices.firstOrNull() ?: return
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = first.kind.color.copy(alpha = 0.18f),
        contentColor = first.kind.color
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Filled.Warning, null)
            Text(
                first.title(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (notices.size > 1) {
                Text(
                    stringResource(Res.string.notice_more, notices.size - 1),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

/** Every notice, each with what to do about it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticesSheet(notices: List<Notice>, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(SheetValue.Hidden, FullSheet)
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                stringResource(Res.string.notices_title),
                style = MaterialTheme.typography.titleLarge
            )
            notices.forEach { notice ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Filled.Warning, null, tint = notice.kind.color)
                        Text(notice.title(), style = MaterialTheme.typography.titleMedium)
                    }
                    Hint(stringResource(notice.kind.help))
                    if (notice.kind == NoticeKind.OutOfDate) {
                        FilledTonalButton(onClick = onUpdate) {
                            Text(stringResource(Res.string.update_units))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
