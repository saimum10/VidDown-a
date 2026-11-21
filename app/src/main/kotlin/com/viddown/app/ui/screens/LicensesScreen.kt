package com.viddown.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.viddown.app.R
import com.viddown.app.ui.UiTags
import com.viddown.core.ui.components.LoadingState
import com.viddown.core.ui.theme.spacing
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LegalDocument(val labelRes: Int, val asset: String)

private val documents = listOf(
    LegalDocument(R.string.licenses_notices, "legal/THIRD_PARTY_NOTICES.md"),
    LegalDocument(R.string.licenses_dependencies, "legal/dependencies.md"),
    LegalDocument(R.string.licenses_app, "legal/LICENSE.txt"),
)

/** Shows the legal documents that ship inside the app (copies kept in sync with the repository by scripts/sync_legal_assets.py). */
@Composable
fun LicensesScreen() {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val text by produceState<String?>(initialValue = null, selected) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open(documents[selected].asset).bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                "The document could not be loaded: ${e.message}"
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            documents.forEachIndexed { index, doc ->
                FilterChip(selected = selected == index, onClick = { selected = index }, label = { Text(stringResource(doc.labelRes)) })
            }
        }
        val shown = text
        if (shown == null) {
            LoadingState(label = stringResource(R.string.loading))
        } else {
            Text(
                text = shown,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(UiTags.LICENSES_TEXT),
            )
        }
    }
}
