package io.github.erikfrish.herdline.service

import android.os.SystemClock
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first

/** Android's enqueue-rate budget is per package, including agent and connection cards. */
internal object NotificationPostTime {
    private val posted = MutableStateFlow(0L)

    fun mark() {
        posted.value = SystemClock.elapsedRealtime()
    }

    /** Each new post restarts this finite quiet window; no notification is retried. */
    @OptIn(FlowPreview::class)
    suspend fun awaitQuiet() {
        posted.debounce { at -> (1_000 - (SystemClock.elapsedRealtime() - at)).coerceAtLeast(0) }.first()
    }
}
