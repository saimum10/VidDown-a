package com.viddown.engine.direct

/**
 * Measures transfer speed over a sliding window. A speed is only reported after at least [minSpanMs] of samples: shorter
 * spans are noise, and "unknown" is better than a wrong number. ETA needs a known total and a positive speed.
 */
class SpeedMeter(private val windowMs: Long = 5_000, private val minSpanMs: Long = 2_000) {
    private val times = ArrayDeque<Long>()
    private val bytes = ArrayDeque<Long>()

    fun record(nowMs: Long, totalBytes: Long) {
        // The byte counter must never go backwards (a restarted transfer calls reset()); ignore bad samples.
        if (bytes.isNotEmpty() && (totalBytes < bytes.last() || nowMs < times.last())) return
        times.addLast(nowMs)
        bytes.addLast(totalBytes)
        while (times.size > 2 && nowMs - times.first() > windowMs) {
            times.removeFirst()
            bytes.removeFirst()
        }
    }

    /** Forget everything, for example after a reconnect: a gap must not be averaged into the speed. */
    fun reset() {
        times.clear()
        bytes.clear()
    }

    fun bytesPerSecond(): Long? {
        if (times.size < 2) return null
        val span = times.last() - times.first()
        if (span < minSpanMs) return null
        val delta = bytes.last() - bytes.first()
        if (delta <= 0) return null
        return delta * 1000 / span
    }

    fun etaSeconds(currentBytes: Long, totalBytes: Long?): Long? {
        val speed = bytesPerSecond() ?: return null
        if (totalBytes == null || totalBytes <= currentBytes) return null
        return (totalBytes - currentBytes + speed - 1) / speed
    }
}
