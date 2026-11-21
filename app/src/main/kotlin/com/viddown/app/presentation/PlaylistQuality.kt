package com.viddown.app.presentation

import com.viddown.core.model.AppSettings
import com.viddown.core.model.QualityPreference

/**
 * The quality choices of a playlist. Every video of a playlist has its own formats, so the sheet cannot list one video's rows.
 * It offers the same kind of rows as the single-video panel, by key instead of by format: "best", "v1080"... and "a:fast", "a:high".
 * Each video then takes the row of its own panel for that key, or the closest one below it (see [QualityPanel.choiceFor]).
 *
 * A [QualityChoice] here has no size (unknown until each video is read) and its [QualityChoice.formatId] is the same as its key, so the
 * panel rows can be drawn and selected with the same composables as the single-video panel.
 */
object PlaylistQuality {
    const val BEST = "best"

    /** The resolutions offered, smallest first; a video without one takes the closest below. */
    private val HEIGHTS = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160, 4320)

    /** Rows before "More formats": the sharpest, and the common sizes. The selected row is always added. */
    private val MAIN_KEYS = listOf(BEST, "v480", "v720", "v1080")

    val audio: List<QualityChoice> = listOf(
        QualityChoice("a:fast", "a:fast", "Fast", "Plays on any phone, speaker or car", null, isAudio = true, height = null),
        QualityChoice("a:high", "a:high", "High quality", "Best sound for the size", null, isAudio = true, height = null),
    )

    /** "Best available" first, then every resolution, smallest first. */
    val video: List<QualityChoice> = listOf(
        QualityChoice(BEST, BEST, "Best available", "The sharpest quality each video has", null, isAudio = false, height = null),
    ) + HEIGHTS.map { h ->
        QualityChoice("v$h", "v$h", QualityPanelBuilder.videoTitle(h), QualityPanelBuilder.videoDescription(h), null, isAudio = false, height = h)
    }

    val all: List<QualityChoice> = audio + video

    /** The video rows shown before "More formats": the main ones, plus [selectedKey] so what is selected is always visible. */
    fun mainVideo(selectedKey: String?): List<QualityChoice> =
        video.filter { it.key in MAIN_KEYS || it.key == selectedKey }

    /** True for a key this panel offers. */
    fun isKnown(key: String?): Boolean = key != null && all.any { it.key == key }

    /**
     * What is selected when the sheet opens: the quality of the last download from a quality panel, else the default quality of the
     * settings (Best, 1080p, 720p, 480p, 360p, Audio only).
     */
    fun initialKey(settings: AppSettings): String {
        settings.lastQuality?.let { last ->
            if (isKnown(last)) return last
            // A resolution this sheet does not list (540p, say): the closest one below it.
            val height = if (last.startsWith("v")) last.drop(1).toIntOrNull() else null
            HEIGHTS.lastOrNull { height != null && it <= height }?.let { return "v$it" }
        }
        val preference = settings.defaultQuality
        return when (preference) {
            QualityPreference.BEST -> BEST
            QualityPreference.AUDIO_ONLY -> "a:high"
            else -> preference.maxHeight?.let { "v$it" } ?: BEST
        }
    }

    /**
     * The row of [panel] (one video's own panel) that stands for [key], or null when that video has no such row at all (an audio key
     * on a video without audio streams, say). The caller then reports that video as not fitting; nothing else is downloaded instead.
     */
    fun resolve(panel: QualityPanel, key: String): QualityChoice? = panel.choiceFor(key)
}
