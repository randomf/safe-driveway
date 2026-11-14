package io.github.randomf.safedriveway.ui.start

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.about
import io.github.randomf.safedriveway.resources.about_driving_aid
import io.github.randomf.safedriveway.resources.about_libraries
import io.github.randomf.safedriveway.resources.about_licence
import io.github.randomf.safedriveway.resources.about_licence_text
import io.github.randomf.safedriveway.resources.about_privacy
import io.github.randomf.safedriveway.resources.about_privacy_text
import io.github.randomf.safedriveway.resources.about_source_code
import io.github.randomf.safedriveway.resources.about_version
import io.github.randomf.safedriveway.resources.app_name
import io.github.randomf.safedriveway.resources.library_androidx
import io.github.randomf.safedriveway.resources.library_compose
import io.github.randomf.safedriveway.resources.library_icons
import io.github.randomf.safedriveway.resources.library_kable
import io.github.randomf.safedriveway.resources.library_kotlin
import io.github.randomf.safedriveway.resources.notice_late
import io.github.randomf.safedriveway.resources.notice_responsible
import io.github.randomf.safedriveway.ui.Hint
import io.github.randomf.safedriveway.ui.ScreenScaffold
import io.github.randomf.safedriveway.ui.SectionTitle
import org.jetbrains.compose.resources.stringResource

/** Where the app's source code is, as the AGPL asks. */
private const val SOURCE_URL = "https://github.com/randomf/safe-driveway"

/** The app's version, its licence and libraries, the privacy note and the driving-aid notice. */
@Composable
fun AboutScreen(version: String, onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    ScreenScaffold(stringResource(Res.string.about), onBack) {
        Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Hint(stringResource(Res.string.about_version, version))

        SectionTitle(stringResource(Res.string.about_driving_aid))
        Text(stringResource(Res.string.notice_responsible))
        Text(stringResource(Res.string.notice_late))

        SectionTitle(stringResource(Res.string.about_privacy))
        Text(stringResource(Res.string.about_privacy_text))

        SectionTitle(stringResource(Res.string.about_licence))
        Text(stringResource(Res.string.about_licence_text))
        OutlinedButton(onClick = { uriHandler.openUri(SOURCE_URL) }) {
            Text(stringResource(Res.string.about_source_code))
        }

        SectionTitle(stringResource(Res.string.about_libraries))
        Column {
            listOf(
                Res.string.library_kotlin,
                Res.string.library_compose,
                Res.string.library_androidx,
                Res.string.library_kable,
                Res.string.library_icons
            ).forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
