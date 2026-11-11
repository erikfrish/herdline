package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.Agent
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.IngressSubagent
import io.github.erikfrish.herdline.core.TranscriptBlock
import io.github.erikfrish.herdline.core.TranscriptEntry
import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.core.TranscriptImage
import io.github.erikfrish.herdline.core.TranscriptKind
import io.github.erikfrish.herdline.core.TranscriptPage
import io.github.erikfrish.herdline.core.TranscriptSteer
import io.github.erikfrish.herdline.core.isChatAgent

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
    val hasOlder: Boolean = start > 0,
) {

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
        var older = if (page.start <= this.start) page.hasOlder else hasOlder
        if (appended && merged.size > MAX_LOADED_ENTRIES) {
            merged = merged.takeLast(MAX_LOADED_ENTRIES)
            start = merged.first().offset
            older = true
        }
        return copy(start = start, end = maxOf(end, page.end), entries = merged, hasOlder = older)
    }

    companion object {
        fun of(page: TranscriptPage) = ChatHistory(page.identity, page.start, page.end, page.entries.sortedBy { it.offset }, page.hasOlder)
    }
}

/**
 * What chat says the supported agent in [pane] is doing, only while the
 * same pane is live on this connection. A lost link or unknown program is
 * [AgentStatus.Unknown]; stale history never claims a Working status.
 */
fun chatStatus(connected: Boolean, pane: String, agent: Agent?): AgentStatus = when {
    !connected || agent == null || agent.pane != pane || !isChatAgent(agent.agent) -> AgentStatus.Unknown
    else -> agent.status
}


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
        /**
         * The children a `task` call spawned: one row per exact child id,
         * merged from every snapshot and settled end that names it.
         */
        val subagents: List<Subagent> = emptyList(),
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
    if (entries.none { it.parentId != null }) return entries
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

/**
 * The chat rows for [entries], with each tool result paired to its call. A
 * `task` call's children are folded in per exact child id: a result's
 * `subagents` snapshot merges into the rows already there (an earlier
 * snapshot's children are kept, never discarded), and each entry's settled
 * `subagent_ends` closes exactly the rows whose id it names. There is one
 * row per id and a settled end is terminal sticky — no later snapshot
 * reopens it — and an end matching no row settles nothing at all.
 */
fun chatItems(entries: List<TranscriptEntry>): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    val calls = HashMap<String, Int>()
    // Every loaded entry's settled ends feed the pass below, not only the
    // ones the branch walk visits.
    val ends = entries.flatMapTo(mutableListOf()) { completionsOf(it.subagentEnds) }
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
                        val children = subagentsOf(block.subagents)
                        val call = calls[block.callId]
                        val tool = call?.let { items[it] as? ChatItem.Tool }
                        if (call != null && tool != null) {
                            items[call] = tool.copy(result = block, images = images, subagents = mergeSubagents(tool.subagents, children))
                        } else {
                            items += ChatItem.Tool(key, null, block, at, images, children)
                        }
                    }
                    is TranscriptBlock.Image -> items += ChatItem.Image(key, block.at(entry), entry.role == "user", at)
                }
            }
        }
    }
    // The settled ends close the exact child ids they name, wherever the rows live.
    if (ends.isNotEmpty()) {
        for (at in items.indices) {
            val tool = items[at] as? ChatItem.Tool ?: continue
            if (tool.subagents.isNotEmpty()) items[at] = tool.copy(subagents = mergeSubagents(tool.subagents, emptyList(), ends))
        }
    }
    return items.filterNot { it is ChatItem.User && it.text.isBlank() || it is ChatItem.Assistant && it.text.isBlank() }
}

/**
 * [items] with the ingress endpoint's live children [live] folded in: a
 * live child joins the exact tool call its `parentCallId` names, else the
 * row already holding its exact id. Live state never closes children and
 * never reopens a settled row; a live child outside every call is dropped,
 * never drawn from nothing.
 */
internal fun withLiveSubagents(items: List<ChatItem>, live: List<IngressSubagent>): List<ChatItem> {
    if (live.isEmpty()) return items
    return items.map { item ->
        if (item !is ChatItem.Tool || (item.call?.callId == null && item.subagents.isEmpty())) {
            item
        } else {
            val mine = live.filter {
                (it.parentCallId != null && it.parentCallId == item.call?.callId) ||
                    (it.parentCallId == null && item.subagents.any { held -> held.id == it.child.id })
            }
            if (mine.isEmpty()) {
                item
            } else {
                item.copy(subagents = mergeSubagents(item.subagents, subagentsOf(mine.map { it.child })))
            }
        }
    }
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
