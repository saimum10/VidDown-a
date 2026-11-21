package com.viddown.engine.newpipe

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lets a coroutine abort a blocking library call. The extractor offers no cancellation of its own, but every request it makes goes
 * through our HTTP downloader, which registers the in-flight call here. Cancelling the token cancels those calls, so a blocked read
 * ends with an IOException at once; calls registered after cancellation are cancelled immediately.
 */
class CancelToken {
    @Volatile
    var isCancelled: Boolean = false
        private set

    private val hooks = CopyOnWriteArrayList<() -> Unit>()

    /** Runs [hook] on cancel (immediately when already cancelled). Returns a function that removes the hook again. */
    fun onCancel(hook: () -> Unit): () -> Unit {
        if (isCancelled) {
            hook()
            return {}
        }
        hooks.add(hook)
        // cancel() may have run between the check and the add
        if (isCancelled && hooks.remove(hook)) hook()
        return { hooks.remove(hook) }
    }

    fun cancel() {
        if (isCancelled) return
        isCancelled = true
        for (hook in hooks.toList()) {
            if (hooks.remove(hook)) {
                try {
                    hook()
                } catch (_: Exception) {
                    // aborting must never throw into the cancelling coroutine
                }
            }
        }
    }
}
