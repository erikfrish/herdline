package io.github.erikfrish.herdline.service

/**
 * What each notification tag last carried, so a silent re-post of the same
 * content can be skipped. Android drops an app's notification updates past
 * about five a second, and in a burst of identical re-posts the one that
 * changes the words can be the one dropped (lab.7.3 J1).
 */
internal class Posted<T> {
    private val last = HashMap<String, T>()

    /** Records [value] as [tag]'s content; false when [tag] already carried it. */
    fun put(tag: String, value: T): Boolean = last.put(tag, value) != value

    operator fun get(tag: String): T? = last[tag]

    fun forget(tag: String) {
        last.remove(tag)
    }

    fun clear() = last.clear()
}
