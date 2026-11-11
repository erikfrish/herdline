package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.Agent
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.TranscriptBlock
import io.github.erikfrish.herdline.core.TranscriptEntry
import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.core.TranscriptImage
import io.github.erikfrish.herdline.core.TranscriptKind
import io.github.erikfrish.herdline.core.TranscriptPage
import io.github.erikfrish.herdline.core.TranscriptSteer

/** Appends beyond this many loaded entries drop the oldest; "Load older" brings them back. */
const val MAX_LOADED_ENTRIES = 2000

/**
 * The part of one agent's session file chat has loaded: complete lines in
 * bytes [start, end), each entry once, keyed by where its line starts.
 */
data class ChatHistory(
    val identity: TranscriptIdentity,
    val start: Long,
    val end: Long,
    val entries: List<TranscriptEntry>,
) {
    val hasOlder: Boolean get() = start > 0

    /**
     * This history with [page] read into it. A reset page replaces it; a page
     * of another agent's transcript that is not a reset is a stale answer
     * from before a switch, and is ignored.
     */
    fun merge(page: TranscriptPage, appended: Boolean): ChatHistory {
        if (page.reset) return of(page)
        if (page.identity != identity) return this
        val byOffset = LinkedHashMap<Long, TranscriptEntry>(entries.size + page.entries.size)
        (entries + page.entries).sortedBy { it.offset }.forEach { byOffset[it.offset] = it }
        var merged = byOffset.values.toList()
        var start = minOf(start, page.start)
        if (appended && merged.size > MAX_LOADED_ENTRIES) {
            merged = merged.takeLast(MAX_LOADED_ENTRIES)
            start = merged.first().offset
        }
        return copy(start = start, end = maxOf(end, page.end), entries = merged)
    }

    companion object {
        fun of(page: TranscriptPage) = ChatHistory(page.identity, page.start, page.end, page.entries.sortedBy { it.offset })
    }
}

/**
 * What chat says the agent in [pane] is doing: the status of the agent the
 * live view has in that pane, and only while the link is up and that agent
 * is OMP. A lost link, no agent, another program, or a view of another pane
 * is [AgentStatus.Unknown]: chat never keeps a Working it can no longer see.
 */
fun chatStatus(connected: Boolean, pane: String, agent: Agent?): AgentStatus = when {
    !connected || agent == null || agent.pane != pane || !agent.agent.equals(OMP_AGENT, ignoreCase = true) -> AgentStatus.Unknown
    else -> agent.status
}

/** The Herdr agent kind chat reads transcripts of. */
internal const val OMP_AGENT = "omp"

/**
 * One row of the chat list; [key] stays the same across reads. [timestamp]
 * is the entry's own time (epoch ms) when the transcript records one.
 */
sealed interface ChatItem {
    val key: String
    val timestamp: Long?

    /** [steer]: how OMP recorded a message that reached a running agent, null for an ordinary one. */
    data class User(override val key: String, val text: String, override val timestamp: Long? = null, val steer: TranscriptSteer? = null) : ChatItem
    data class Assistant(override val key: String, val text: String, val truncated: Boolean, override val timestamp: Long? = null) : ChatItem
    data class Thinking(override val key: String, val text: String, override val timestamp: Long? = null) : ChatItem
    /**
     * A tool call and, once it arrived, its result; either may be outside what is loaded. [timestamp] is the call's.
     * [images] are the result's, located on its line.
     */
    data class Tool(
        override val key: String,
        val call: TranscriptBlock.ToolCall?,
        val result: TranscriptBlock.ToolResult?,
        override val timestamp: Long? = null,
        val images: List<TranscriptImage> = emptyList(),
    ) : ChatItem
    /** Text the agent's harness added, such as a developer or extension message. */
    data class Note(override val key: String, val role: String, val text: String, override val timestamp: Long? = null) : ChatItem
    data class Compaction(override val key: String, val summary: String, override val timestamp: Long? = null) : ChatItem
    /** An image of a message; [fromUser] draws it in the user's turn. */
    data class Image(override val key: String, val image: TranscriptImage, val fromUser: Boolean, override val timestamp: Long? = null) : ChatItem
}

/** A row of the user's turn: their text or an image they sent. */
val ChatItem.inUserTurn: Boolean get() = this is ChatItem.User || this is ChatItem.Image && fromUser

/**
 * The entries on the session's current branch. OMP appends every entry with
 * its parent's ID, and a branch made from an earlier message leaves the old
 * one in the file, so the branch is the ancestry of the newest entry. When
 * that walk leaves what is loaded, the older loaded entries are kept, since
 * which branch they are on is not known yet.
 */
fun currentBranch(entries: List<TranscriptEntry>): List<TranscriptEntry> {
    val byId = entries.filter { it.id != null }.associateBy { it.id }
    var entry = entries.lastOrNull { it.id != null } ?: return entries
    val branch = mutableSetOf<String>()
    while (true) {
        entry.id?.let(branch::add)
        val parent = entry.parentId?.let(byId::get) ?: break
        // A cycle in a corrupt file ends the walk instead of looping.
        if (parent.id in branch) break
        entry = parent
    }
    val reachedRoot = entry.parentId == null
    val oldest = entry.offset
    return entries.filter { it.id == null || it.id in branch || (!reachedRoot && it.offset < oldest) }
}

/** The chat rows for [entries], with each tool result paired to its call. */
fun chatItems(entries: List<TranscriptEntry>): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    val calls = HashMap<String, Int>()
    for (entry in currentBranch(entries)) {
        val base = entry.id ?: "@${entry.offset}"
        val at = entry.timestamp
        when (entry.kind) {
            TranscriptKind.Other -> Unit
            TranscriptKind.Compaction -> items += ChatItem.Compaction("$base:c", entry.blocks.text(), at)
            TranscriptKind.Message -> entry.blocks.forEachIndexed { index, block ->
                val key = "$base:$index"
                when (block) {
                    is TranscriptBlock.Text -> items += when (entry.role) {
                        "user" -> ChatItem.User(key, block.text, at, entry.steer)
                        "assistant" -> ChatItem.Assistant(key, block.text, block.truncated, at)
                        else -> ChatItem.Note(key, entry.role.orEmpty(), block.text, at)
                    }
                    is TranscriptBlock.Thinking -> if (block.text.isNotBlank()) items += ChatItem.Thinking(key, block.text, at)
                    is TranscriptBlock.ToolCall -> {
                        calls[block.callId] = items.size
                        items += ChatItem.Tool(key, block, null, at)
                    }
                    is TranscriptBlock.ToolResult -> {
                        val images = block.images.map { it.at(entry) }
                        val call = calls[block.callId]
                        val tool = call?.let { items[it] as? ChatItem.Tool }
                        if (call != null && tool != null) items[call] = tool.copy(result = block, images = images) else items += ChatItem.Tool(key, null, block, at, images)
                    }
                    is TranscriptBlock.Image -> items += ChatItem.Image(key, block.at(entry), entry.role == "user", at)
                }
            }
        }
    }
    return items.filterNot { it is ChatItem.User && it.text.isBlank() || it is ChatItem.Assistant && it.text.isBlank() }
}

private fun TranscriptBlock.Image.at(entry: TranscriptEntry) = TranscriptImage(entry.id, entry.offset, entry.end, part, source)

/**
 * The tool call a blocked agent is asking about, joined fail-closed: the
 * newest tool must be the one call in [items] still waiting for its result.
 * With none, or with more than one unanswered (either could be the open
 * dialog), it is null and chat names no tool.
 */
fun pendingTool(items: List<ChatItem>): TranscriptBlock.ToolCall? {
    val open = items.filter { it is ChatItem.Tool && it.call != null && it.result == null }
    val newest = items.lastOrNull { it is ChatItem.Tool }
    return (open.singleOrNull()?.takeIf { it === newest } as? ChatItem.Tool)?.call
}

/** OMP's tool for a question dialog in the terminal. */
internal const val ASK_TOOL = "ask"

private fun List<TranscriptBlock>.text(): String =
    filterIsInstance<TranscriptBlock.Text>().joinToString("\n\n") { it.text }
