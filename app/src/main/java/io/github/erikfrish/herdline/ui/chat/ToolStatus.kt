package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.TranscriptBlock

/** What a tool card says about its call. Denied (the person or a policy said no) is not Failed (it ran and broke). */
enum class ToolState { Running, Done, Failed, Denied }

/**
 * OMP's own wording for a call it refused, as of OMP 18.18.6: the approval
 * prompt (`extensibility/extensions/wrapper.ts`, `session/session-tools.ts`),
 * a deny policy (`tools/approval.ts` `denyError`) and the fallback reasons
 * of an extension, preflight rule or hook that blocks without one.
 *
 * Only OMP's fixed phrases count. An extension's own reason is arbitrary
 * prose and stays Failed, and so do `Command aborted`, `Operation aborted`,
 * a timeout and `Edit rejected for …`: an interrupt or a validation error
 * is not the person saying no.
 */
private val DENIED = Regex(
    "^(?:Tool call denied by user: " +
        "|Tool call rejected by user \\(" +
        "|Tool \"[^\"\\n]+\" is blocked by (?:tool|user) policy\\." +
        "|Tool execution was blocked by (?:an extension|a preflight rule|a hook)$)",
)

fun toolState(tool: ChatItem.Tool): ToolState {
    val result = tool.result ?: return ToolState.Running
    return when {
        !result.isError -> ToolState.Done
        DENIED.containsMatchIn(result.text) -> ToolState.Denied
        else -> ToolState.Failed
    }
}

/** The tools whose results OMP ends with `Command exited with code N` (`session/messages.ts`, `tools/eval.ts`). */
private val EXIT_TOOLS = setOf("bash", "eval")
private val EXIT_LINE = Regex("(?:^|\\n)Command exited with code (\\d+)\\s*$")

/**
 * The exit code [result] states, else null. OMP writes the line only for a
 * non-zero exit, so its absence is no code, never a guessed 0. A result cut
 * for chat lost its end, and with it the line.
 */
fun exitCode(result: TranscriptBlock.ToolResult): Int? {
    if (result.name !in EXIT_TOOLS || result.truncated) return null
    return EXIT_LINE.find(result.text)?.groupValues?.get(1)?.toIntOrNull()
}

/** Tool output shows its last this many lines before "Show all N lines". */
const val TOOL_TAIL_LINES = 10

/** The last [limit] lines of [text], and how many lines came before them. */
fun outputTail(text: String, limit: Int): Pair<String, Int> {
    val lines = text.trimEnd('\n').lines()
    return if (lines.size <= limit) lines.joinToString("\n") to 0 else lines.takeLast(limit).joinToString("\n") to lines.size - limit
}

/** The one-line summary of a tool call: its intent, else the first line of its arguments. */
fun ChatItem.Tool.summary(): String =
    call?.intent?.takeIf { it.isNotBlank() } ?: call?.args?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

val ChatItem.Tool.name: String get() = call?.name ?: result?.name.orEmpty()

/** One step of a run as a line: the tool's name and what it worked on. */
fun ChatItem.Tool.stepLine(): String = listOf(name, summary()).filter { it.isNotBlank() }.joinToString(" · ")
