package com.viddown.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viddown.app.BuildConfig
import com.viddown.app.R
import com.viddown.app.ui.UiTags
import com.viddown.core.ui.theme.spacing

@Composable
fun AboutScreen(onOpenLicenses: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    val sourceUrl = BuildConfig.SOURCE_REPO_URL
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            Image(painter = painterResource(R.drawable.viddown_logo), contentDescription = null, modifier = Modifier.height(64.dp))
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(UiTags.ABOUT_VERSION),
                )
            }
        }

        Heading(stringResource(R.string.about_build))
        val abis = BuildConfig.NATIVE_ABIS.ifBlank { stringResource(R.string.about_none) }
        Text(
            stringResource(R.string.about_build_details, BuildConfig.NATIVE_BUNDLE_TAG, abis, BuildConfig.CPYTHON_VERSION, BuildConfig.YTDLP_VERSION, BuildConfig.FFMPEG_VERSION),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.about_newpipe_version, BuildConfig.NEWPIPE_EXTRACTOR_TAG, BuildConfig.NEWPIPE_EXTRACTOR_COMMIT),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(R.string.about_build_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Heading(stringResource(R.string.about_privacy))
        Text(stringResource(R.string.about_privacy_text), style = MaterialTheme.typography.bodyMedium)

        Heading(stringResource(R.string.about_source))
        if (sourceUrl.isBlank()) {
            Text(stringResource(R.string.about_source_unset), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                sourceUrl,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl)))
                    } catch (e: ActivityNotFoundException) {
                        // no browser installed: nothing to open
                    }
                },
            )
        }

        Heading(stringResource(R.string.about_licenses))
        Text(
            stringResource(R.string.about_licenses_open),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLicenses).padding(vertical = spacing.md).testTag(UiTags.ABOUT_LICENSES),
        )

        Heading(stringResource(R.string.about_thanks))
        Text(stringResource(R.string.about_thanks_text), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = MaterialTheme.spacing.md))
}
