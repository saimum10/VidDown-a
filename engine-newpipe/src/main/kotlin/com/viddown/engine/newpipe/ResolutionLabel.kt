package com.viddown.engine.newpipe

/** Reads labels such as "720p", "1080p60" or "2160p60 HDR" into height and frame rate. Anything else is "unknown", never guessed. */
internal object ResolutionLabel {
    data class Parsed(val height: Int?, val fps: Double?)

    private val pattern = Regex("^\\s*(\\d{3,4})p(\\d{2,3})?")

    fun parse(label: String?): Parsed {
        val m = pattern.find(label ?: return Parsed(null, null)) ?: return Parsed(null, null)
        val height = m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
        val fps = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it > 0 }
        return Parsed(height, fps)
    }
}
