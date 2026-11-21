package com.viddown.engine.direct

import kotlin.random.Random

/**
 * Retry with exponential backoff for transient failures. Only consecutive attempts that made no progress count against the
 * limit (see StreamTransfer in engine-newpipe), so a long download over a flaky link is not abandoned while it keeps moving.
 */
class RetryPolicy(
    val maxRetries: Int = 3,
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 30_000,
    private val random: Random = Random.Default,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must not be negative" }
        require(baseDelayMs >= 0 && maxDelayMs >= baseDelayMs) { "invalid delays" }
    }

    /**
     * Delay before retry number [retryNumber] (1 = first retry): base * 2^(n-1) with +-20% jitter, never above the maximum.
     * A server-provided [retryAfterMs] (Retry-After) raises the delay but is also capped at the maximum.
     */
    fun delayMs(retryNumber: Int, retryAfterMs: Long? = null): Long {
        require(retryNumber >= 1) { "retryNumber starts at 1" }
        val exponent = (retryNumber - 1).coerceAtMost(20)
        val raw = (baseDelayMs.toDouble() * (1L shl exponent)).coerceAtMost(maxDelayMs.toDouble())
        val jittered = if (raw <= 0.0) 0.0 else raw * (0.8 + random.nextDouble() * 0.4)
        val backoff = jittered.toLong().coerceAtMost(maxDelayMs)
        val server = retryAfterMs?.coerceIn(0, maxDelayMs) ?: 0L
        return maxOf(backoff, server)
    }

    companion object {
        val DEFAULT = RetryPolicy()
        val NONE = RetryPolicy(maxRetries = 0)
    }
}
