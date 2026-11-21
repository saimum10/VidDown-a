package com.viddown.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viddown.app.R
import com.viddown.app.ui.UiTags
import com.viddown.browser.Tab
import com.viddown.browser.TabsState
import com.viddown.browser.UrlPolicy
import com.viddown.core.ui.VidDownIcons
import com.viddown.core.ui.theme.spacing

/** All open tabs. Tapping a tab shows it; the active tab is outlined. */
@Composable
fun TabSwitcher(
    state: TabsState,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Surface(modifier = modifier.fillMaxSize().testTag(UiTags.TABS_SWITCHER), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = spacing.lg)) {
            Row(Modifier.fillMaxWidth().padding(vertical = spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tabs_title, state.tabs.size),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCloseAll, modifier = Modifier.testTag(UiTags.TABS_CLOSE_ALL)) { Text(stringResource(R.string.tabs_close_all)) }
                TextButton(onClick = onNewTab, modifier = Modifier.testTag(UiTags.TAB_NEW)) { Text(stringResource(R.string.tabs_new)) }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                items(state.tabs, key = { it.id }) { tab -> TabCard(tab, tab.id == state.activeId, onSelect, onClose) }
            }
        }
    }
}

@Composable
private fun TabCard(tab: Tab, active: Boolean, onSelect: (String) -> Unit, onClose: (String) -> Unit) {
    val spacing = MaterialTheme.spacing
    val title = tab.displayTitle ?: stringResource(R.string.tab_untitled)
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(tab.id) }.testTag(UiTags.tabCard(tab.id)),
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.defaultMinSize(minHeight = spacing.touchTarget).padding(start = spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = spacing.md)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    UrlPolicy.displayHost(tab.url) ?: stringResource(R.string.browser_blank_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.testTag(UiTags.tabClose(tab.id))) {
                Icon(painterResource(VidDownIcons.Close), contentDescription = stringResource(R.string.tab_close, title))
            }
        }
    }
}
