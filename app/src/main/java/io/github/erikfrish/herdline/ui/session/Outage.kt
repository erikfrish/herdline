package io.github.erikfrish.herdline.ui.session

/**
 * How long a loss must last before its strip shows, so a blip shows nothing
 * (Collie `DEAD_DEBOUNCE_MS`).
 */
internal const val OUTAGE_SHOW_AFTER_MS = 1_000L

/**
 * One surface's outage, Collie rows 4 and 5: the strip over the last screen
 * and the one "Reconnected" after it. Pure; the caller feeds it what it saw
 * and when.
 *
 * An outage is a loss after the surface was live: a first connect that never
 * got there is not one, so its first paint never says "Reconnected".
 * [since] is when the loss was seen, the time the saved screen dates from.
 * [shown] says the strip came up in this outage, [dismissed] that the reader
 * closed it; both end with the outage.
 */
internal data class Outage(
    val wasLive: Boolean = false,
    val since: Long? = null,
    val shown: Boolean = false,
    val dismissed: Boolean = false,
) {
    /** The strip is up: the loss outlasted a blip and the reader has not closed it. */
    val strip: Boolean get() = shown && !dismissed

    /**
     * The surface was seen [live] or not at [now]. [OutageStep.dropped] starts
     * a new outage, which cancels a "Reconnected" still up; [OutageStep.reconnected]
     * is true once, on the recovery from an outage whose strip was shown.
     */
    fun observe(live: Boolean, now: Long): OutageStep = when {
        live -> OutageStep(Outage(wasLive = true), dropped = false, reconnected = since != null && shown)
        wasLive && since == null -> OutageStep(copy(since = now), dropped = true, reconnected = false)
        else -> OutageStep(this, dropped = false, reconnected = false)
    }

    /** At [now], a loss that has lasted [OUTAGE_SHOW_AFTER_MS] shows its strip. */
    fun tick(now: Long): Outage =
        if (since != null && !shown && now - since >= OUTAGE_SHOW_AFTER_MS) copy(shown = true) else this

    /** The reader closes the strip; it stays closed until this outage ends. */
    fun dismiss(): Outage = if (since != null) copy(dismissed = true) else this
}

internal data class OutageStep(val outage: Outage, val dropped: Boolean, val reconnected: Boolean)
