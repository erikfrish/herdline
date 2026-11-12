package io.github.erikfrish.herdline.ui.nodes

/** One host-card chip (A-22): a [key] that [count] panes share. */
internal data class ChipGroup<K>(val key: K, val count: Int)

/**
 * [keys] with each distinct key once and the number of times it appears, in
 * the order each key first appears, so two panes of one program in one state
 * read as one chip with a count, not as two identical chips.
 */
internal fun <K> chipGroups(keys: List<K>): List<ChipGroup<K>> {
    val counts = LinkedHashMap<K, Int>()
    for (key in keys) counts[key] = (counts[key] ?: 0) + 1
    return counts.map { (key, count) -> ChipGroup(key, count) }
}
