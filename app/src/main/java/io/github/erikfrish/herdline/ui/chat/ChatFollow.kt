package io.github.erikfrish.herdline.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.dp

/**
 * How far above the list's end the reader may stop and still follow new
 * messages: a fling that ends a few pixels short is still at the bottom.
 */
internal val FollowBand = 24.dp

/**
 * Whether the list shows its end: the last of [totalItems] is laid out
 * ([lastVisibleIndex]) and ends ([lastVisibleEnd]) at most [band] px past
 * where the content ends ([contentEnd], the viewport's end less its bottom
 * padding). An empty list is at its end.
 */
internal fun atListEnd(lastVisibleIndex: Int, lastVisibleEnd: Int, totalItems: Int, contentEnd: Int, band: Int): Boolean =
    totalItems == 0 || lastVisibleIndex == totalItems - 1 && lastVisibleEnd <= contentEnd + band

/** [atListEnd] for this layout, within [band] px. */
internal fun LazyListLayoutInfo.atEnd(band: Int): Boolean {
    val last = visibleItemsInfo.lastOrNull() ?: return totalItemsCount == 0
    return atListEnd(last.index, last.offset + last.size, totalItemsCount, viewportEndOffset - afterContentPadding, band)
}

/**
 * How far the last laid-out item ends past where the content ends, in px:
 * the scroll that brings the list exactly to its end, as [atEnd] measures
 * it. In a list shorter than its own padding, as a list squeezed to 0 px for
 * a frame of a configuration change is, the content ends above the
 * viewport's start, so the last item's own size falls short of it. Then
 * every pin stops short of the end and asks for the next one, a layout
 * loop on the main thread (lab.9 S2 ANR).
 */
internal fun LazyListLayoutInfo.pastEnd(): Int {
    val last = visibleItemsInfo.lastOrNull() ?: return 0
    return (last.offset + last.size - (viewportEndOffset - afterContentPadding)).coerceAtLeast(0)
}

/** To the very end of the list, also when its last item is taller than the list ([atEnd] at 0 px after it). */
internal suspend fun LazyListState.scrollToEnd() {
    val count = layoutInfo.totalItemsCount
    if (count == 0) return
    scrollToItem(count - 1)
    scrollBy(layoutInfo.pastEnd().toFloat())
}

/** What the list does after one layout or scroll change: whether it now follows, and whether to pin to the end. */
internal data class FollowStep(val follow: Boolean, val pin: Boolean)

/**
 * Only the reader's own drag, settled ([dragged], no longer [scrolling]),
 * decides [follow], from whether it stopped within the band ([atBand]).
 * Anything else that moves the end away while following, such as a
 * rotation, a recreated screen restoring an old position, a keyboard or a
 * re-wrap, pins back to the very end ([atEnd], 0 px); nothing moves a list
 * the reader left, and nothing moves one mid-scroll.
 */
internal fun followStep(follow: Boolean, scrolling: Boolean, dragged: Boolean, atBand: Boolean, atEnd: Boolean): FollowStep = when {
    scrolling -> FollowStep(follow, pin = false)
    dragged -> FollowStep(atBand, pin = false)
    else -> FollowStep(follow, pin = follow && !atEnd)
}
