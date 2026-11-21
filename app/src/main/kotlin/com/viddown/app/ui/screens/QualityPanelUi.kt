package com.viddown.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.viddown.app.R
import com.viddown.app.presentation.AdvancedAvailability
import com.viddown.app.presentation.AdvancedOptions
import com.viddown.app.presentation.QualityChoice
import com.viddown.app.presentation.QualityState
import com.viddown.app.ui.UiTags
import com.viddown.core.ui.theme.spacing

/**
 * The quality panel of the browser's download sheet: a few rows first (Music, Video), "More formats" for the rest, and an
 * "Advanced options" button beside Download for subtitle, codec and container. Everything shown comes from the engine's own answer.
 */
@Composable
internal fun QualityReadyContent(
    state: QualityState.Ready,
    onSelect: (String) -> Unit,
    onAdvanced: (AdvancedOptions) -> Unit,
    onConfirm: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val panel = remember(state.info, state.advanced) { state.panel }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showAll) { showAll = false }
    val backLabel = stringResource(R.string.quality_back)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg)
            .padding(bottom = spacing.xl),
    ) {
        if (showAll) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { showAll = false },
                    modifier = Modifier.size(spacing.touchTarget).testTag(UiTags.QUALITY_BACK).semantics { contentDescription = backLabel },
                    contentPadding = PaddingValues(0.dp),
                ) { BackArrowIcon(LocalContentColor.current) }
                Text(stringResource(R.string.quality_more), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = spacing.md))
            }
        } else {
            Text(stringResource(R.string.quality_download_as), style = MaterialTheme.typography.titleLarge)
            Text(state.info.title ?: state.target.name ?: state.target.url, style = MaterialTheme.typography.bodyMedium, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.notice?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = spacing.sm).testTag(UiTags.QUALITY_NOTICE))
        }

        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            when {
                // Nothing the panel can show (only separate video streams, say): the plain list keeps every option reachable.
                panel.all.isEmpty() && state.rows.isNotEmpty() -> FormatList(state.rows, state.selectedId, onSelect)
                showAll -> ChoiceSections(panel.audio, panel.video, state.selectedId, detailed = true, onSelect = onSelect)
                else -> {
                    ChoiceSections(panel.audio, panel.mainVideo(state.selectedId), state.selectedId, detailed = false, onSelect = onSelect)
                    if (panel.all.isNotEmpty()) MoreFormatsRow { showAll = true }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (panel.availability.any) {
                val label = stringResource(R.string.quality_advanced)
                OutlinedButton(
                    onClick = { showAdvanced = true },
                    modifier = Modifier.size(spacing.touchTarget).testTag(UiTags.QUALITY_ADVANCED).semantics { contentDescription = label },
                    contentPadding = PaddingValues(0.dp),
                ) { TuneIcon(LocalContentColor.current) }
            }
            Button(
                onClick = onConfirm,
                enabled = state.canConfirm,
                modifier = Modifier.weight(1f).defaultMinSize(minHeight = spacing.touchTarget).testTag(UiTags.QUALITY_CONFIRM),
            ) {
                Text(stringResource(if (state.rows.isEmpty()) R.string.quality_confirm_original else R.string.quality_confirm))
            }
        }
    }

    if (showAdvanced) {
        AdvancedDialog(panel.availability, state.advanced, onAdvanced) { showAdvanced = false }
    }
}

// ---------------------------------------------------------------------------------------------------- rows

@Composable
internal fun ChoiceSections(
    audio: List<QualityChoice>,
    video: List<QualityChoice>,
    selectedId: String?,
    detailed: Boolean,
    onSelect: (String) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    if (audio.isNotEmpty()) {
        SectionLabel(stringResource(R.string.quality_music))
        audio.forEach { ChoiceRow(it, it.formatId == selectedId, detailed, onSelect) }
    }
    if (video.isNotEmpty()) {
        Text(
            stringResource(R.string.quality_video),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.md),
        )
        video.forEach { ChoiceRow(it, it.formatId == selectedId, detailed, onSelect) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = MaterialTheme.spacing.sm))
}

@Composable
private fun ChoiceRow(choice: QualityChoice, selected: Boolean, detailed: Boolean, onSelect: (String) -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = spacing.touchTarget)
            .selectable(selected = selected, onClick = { onSelect(choice.formatId) }, role = Role.RadioButton)
            .testTag(UiTags.qualityRow(choice.formatId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(choice.title, style = MaterialTheme.typography.bodyLarge)
            if (detailed) {
                Text(choice.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        choice.sizeText?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = spacing.md))
        }
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
internal fun MoreFormatsRow(onClick: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.sm)
            .defaultMinSize(minHeight = spacing.touchTarget)
            .clickable(onClick = onClick)
            .testTag(UiTags.QUALITY_MORE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.quality_more), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.quality_more_all), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("\u203A", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = spacing.sm))
    }
}

// ---------------------------------------------------------------------------------------------------- advanced options

@Composable
private fun AdvancedDialog(
    availability: AdvancedAvailability,
    advanced: AdvancedOptions,
    onChange: (AdvancedOptions) -> Unit,
    onDone: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.quality_advanced)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                if (availability.subtitles.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.quality_advanced_subtitle))
                    OptionRow(stringResource(R.string.quality_advanced_none), advanced.subtitleLang == null) { onChange(advanced.copy(subtitleLang = null)) }
                    availability.subtitles.forEach { sub ->
                        OptionRow(sub.label, advanced.subtitleLang == sub.lang) { onChange(advanced.copy(subtitleLang = sub.lang)) }
                    }
                    Text(
                        stringResource(R.string.quality_advanced_subtitle_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (availability.codecs.size > 1) {
                    SectionLabel(stringResource(R.string.quality_advanced_codec))
                    OptionRow(stringResource(R.string.quality_advanced_auto), advanced.codec == null) { onChange(advanced.copy(codec = null)) }
                    availability.codecs.forEach { codec ->
                        OptionRow(AdvancedAvailability.codecLabel(codec), advanced.codec == codec) { onChange(advanced.copy(codec = codec)) }
                    }
                }
                if (availability.containers.size > 1) {
                    SectionLabel(stringResource(R.string.quality_advanced_container))
                    OptionRow(stringResource(R.string.quality_advanced_auto), advanced.container == null) { onChange(advanced.copy(container = null)) }
                    availability.containers.forEach { container ->
                        OptionRow(AdvancedAvailability.containerLabel(container), advanced.container == container) { onChange(advanced.copy(container = container)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDone, modifier = Modifier.testTag(UiTags.QUALITY_ADVANCED_DONE)) { Text(stringResource(R.string.quality_advanced_done)) }
        },
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MaterialTheme.spacing.touchTarget)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = MaterialTheme.spacing.md))
    }
}

// ---------------------------------------------------------------------------------------------------- icons (drawn: the app has no icon library)

/** Three sliders: the "advanced options" icon. */
@Composable
internal fun TuneIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        val knob = 3.dp.toPx()
        val lines = listOf(0.2f to 0.68f, 0.5f to 0.30f, 0.8f to 0.58f)
        for ((y, x) in lines) {
            drawLine(color, Offset(0f, size.height * y), Offset(size.width, size.height * y), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(color, radius = knob, center = Offset(size.width * x, size.height * y))
        }
    }
}

/** A left arrow: back from "More formats". */
@Composable
internal fun BackArrowIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        val mid = size.height / 2f
        drawLine(color, Offset(size.width * 0.1f, mid), Offset(size.width * 0.9f, mid), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.1f, mid), Offset(size.width * 0.45f, mid - size.height * 0.35f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.1f, mid), Offset(size.width * 0.45f, mid + size.height * 0.35f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}
