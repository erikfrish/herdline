package io.github.erikfrish.herdline.data

/** Supported live/connection card fields; unavailable values are never invented. */
enum class NotificationField(val id: String) { Agent("agent"), Tab("tab"), Host("host"), State("state"), Elapsed("elapsed"), Counts("counts") }

object NotificationFields {
    val DEFAULT = listOf("state", "agent", "tab", "elapsed", "counts")
    val ALL = NotificationField.entries.map { it.id }

    fun allowed(id: String, detail: LockDetail): Boolean = when (detail) {
        LockDetail.Hidden -> false
        LockDetail.StateOnly -> id == "state" || id == "elapsed"
        LockDetail.NamesOnly -> id == "state" || id == "agent"
        LockDetail.Full -> id in ALL
    }
    fun normalized(ids: List<String>): List<String> = ids.filter { it in ALL }.distinct()
    fun toggle(ids: List<String>, id: String): List<String> =
        if (id in ids) ids.filterNot { it == id } else normalized(ids + id)
    fun moveUp(ids: List<String>, id: String): List<String> {
        val at = ids.indexOf(id)
        if (at <= 0) return ids
        return ids.toMutableList().apply { this[at] = this[at - 1]; this[at - 1] = id }
    }
}
