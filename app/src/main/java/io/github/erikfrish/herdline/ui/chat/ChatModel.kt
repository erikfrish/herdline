package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.TranscriptBlock
import io.github.erikfrish.herdline.core.TranscriptEntry
import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.core.TranscriptKind
import io.github.erikfrish.herdline.core.TranscriptPage
import org.json.JSONException
import org.json.JSONObject

/** Appends beyond this many loaded entries drop the oldest; "Load older" brings them back. */
const val MAX_LOADED_ENTRIES = 2000

/**
 * The part of one agent's session file chat has loaded: complete lines in
 * bytes [start, end), each entry once, keyed by where its line starts.
 */
data class ChatHistory(
    val identity: TranscriptIdentity,
    val status: AgentStatus,
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
        return copy(status = page.status, start = start, end = maxOf(end, page.end), entries = merged)
    }

    companion object {
        fun of(page: TranscriptPage) = ChatHistory(page.identity, page.status, page.start, page.end, page.entries.sortedBy { it.offset })
    }
}

/** One row of the chat list; [key] stays the same across reads. */
sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String) : ChatItem
    data class Assistant(override val key: String, val text: String, val truncated: Boolean) : ChatItem
    data class Thinking(override val key: String, val text: String) : ChatItem
    /** A tool call and, once it arrived, its result; either may be outside what is loaded. */
    data class Tool(
        override val key: String,
        val call: TranscriptBlock.ToolCall?,
        val result: TranscriptBlock.ToolResult?,
    ) : ChatItem
    /** Text the agent's harness added, such as a developer or extension message. */
    data class Note(override val key: String, val role: String, val text: String) : ChatItem
    data class Compaction(override val key: String, val summary: String) : ChatItem
    data class Image(override val key: String) : ChatItem
}

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
        when (entry.kind) {
            TranscriptKind.Other -> Unit
            TranscriptKind.Compaction -> items += ChatItem.Compaction("$base:c", entry.blocks.text())
            TranscriptKind.Message -> entry.blocks.forEachIndexed { index, block ->
                val key = "$base:$index"
                when (block) {
                    is TranscriptBlock.Text -> items += when (entry.role) {
                        "user" -> ChatItem.User(key, block.text)
                        "assistant" -> ChatItem.Assistant(key, block.text, block.truncated)
                        else -> ChatItem.Note(key, entry.role.orEmpty(), block.text)
                    }
                    is TranscriptBlock.Thinking -> if (block.text.isNotBlank()) items += ChatItem.Thinking(key, block.text)
                    is TranscriptBlock.ToolCall -> {
                        calls[block.callId] = items.size
                        items += ChatItem.Tool(key, block, null)
                    }
                    is TranscriptBlock.ToolResult -> {
                        val at = calls[block.callId]
                        val tool = at?.let { items[it] as? ChatItem.Tool }
                        if (at != null && tool != null) items[at] = tool.copy(result = block) else items += ChatItem.Tool(key, null, block)
                    }
                    TranscriptBlock.Image -> items += ChatItem.Image(key)
                }
            }
        }
    }
    return items.filterNot { it is ChatItem.User && it.text.isBlank() || it is ChatItem.Assistant && it.text.isBlank() }
}

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

/** The question an `ask` call shows in the terminal, and the labels of its choices. */
data class WaitingQuestion(val question: String, val options: List<String>)

/**
 * The one question [call] asks, read from its arguments, for chat to show
 * read-only. Null unless it is a complete `ask` with exactly one question:
 * cut or unreadable arguments, or several questions (the terminal shows one
 * at a time, and which is open is unknown), leave only the tool's name.
 */
fun waitingQuestion(call: TranscriptBlock.ToolCall): WaitingQuestion? {
    if (call.name != ASK_TOOL || call.truncated) return null
    val args = try {
        JSONObject(call.args)
    } catch (e: JSONException) {
        return null
    }
    val one = args.optJSONArray("questions")?.let { if (it.length() == 1) it.optJSONObject(0) else null } ?: args
    val question = one.optString("question").trim().takeIf { it.isNotEmpty() } ?: return null
    val choices = one.optJSONArray("options")
    val options = (0 until (choices?.length() ?: 0)).mapNotNull { index ->
        val option = choices?.opt(index)
        ((option as? JSONObject)?.optString("label") ?: option as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }
    return WaitingQuestion(question, options)
}

/** OMP's tool for a question dialog in the terminal. */
internal const val ASK_TOOL = "ask"

private fun List<TranscriptBlock>.text(): String =
    filterIsInstance<TranscriptBlock.Text>().joinToString("\n\n") { it.text }
