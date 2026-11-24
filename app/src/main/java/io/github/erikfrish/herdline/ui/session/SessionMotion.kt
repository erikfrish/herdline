package io.github.erikfrish.herdline.ui.session

/** One segment's horizontal edges in its row, in px. */
internal data class Edges(val start: Float, val end: Float)

/** Where both mode segments sit: what the sliding pill moves between. */
internal data class SegmentGeometry(val chat: Edges, val terminal: Edges)

/**
 * The mode pill jumps instead of gliding unless the user's tap moved it
 * across an unchanged row: the first placement, a mode the harness or a
 * restore set, and any change of the segments' geometry (rotation, a merged
 * header, the font, the glyph form) are never animated.
 */
internal fun indicatorSnaps(before: SegmentGeometry?, now: SegmentGeometry, tapped: Boolean): Boolean =
    before == null || before != now || !tapped

/** Send pops only as it turns on: never on its first value, more typing, or turning off. */
internal fun popsOnEnable(before: Boolean?, now: Boolean): Boolean = before == false && now

/**
 * The Send count after a tap: one more only when the tap started a delivery
 * (nothing was in flight before and something is now). A refused tap, or one
 * that raced a delivery already going, leaves it as it was.
 */
internal fun sendsAfterTap(sends: Int, wasSending: Boolean, nowSending: Boolean): Int =
    if (!wasSending && nowSending) sends + 1 else sends

/** The Send icon flicks once per delivery started after it was first shown. */
internal fun flicksOnSend(before: Int?, now: Int): Boolean = before != null && now > before
