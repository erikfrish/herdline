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

/**
 * What a tool card says about its call: a `task` call's children still
 * running shows Running whatever its own result said — nothing claims Done
 * while a child is alive. Denied (the person or a policy said no) is not
 * Failed (it ran and broke).
 */
fun toolState(tool: ChatItem.Tool): ToolState {
    val result = tool.result ?: return ToolState.Running
    return when {
        !result.isError -> if (tool.subagents.any { it.alive }) ToolState.Running else ToolState.Done
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

/**
 * What a tool worked on, for its row and TalkBack: the intent, an ask's
 * recorded question, else the main argument. Never a raw JSON/HTML line:
 * the full arguments stay behind their own disclosure.
 */
fun ChatItem.Tool.summary(): String {
    call?.intent?.takeIf(String::isNotBlank)?.let { return it }
    if (name == ASK_TOOL) return result?.answers?.firstOrNull()?.question.orEmpty()
    return call?.args?.let(::mainArgument).orEmpty()
}

/** The argument a tool row names, in this order; else the first string the call has. */
private val MAIN_ARGUMENTS = listOf("command", "cmd", "file_path", "path", "filePath", "pattern", "query", "url", "description", "prompt")

/**
 * [args] as one line a person reads. Every format's arguments arrive as the
 * agent wrote them, mostly a JSON object, so one rule serves them all: an
 * array names its first string. JSON that does not parse (cut for chat)
 * says nothing rather than braces. A string is shown as it is, even one
 * holding JSON of its own.
 */
internal fun mainArgument(args: String): String {
    val text = args.trim()
    val value = when {
        text.startsWith("{") -> runCatching { org.json.JSONObject(text) }.getOrNull()?.let { json ->
            MAIN_ARGUMENTS.firstNotNullOfOrNull { key -> (json.opt(key) as? String)?.takeIf(String::isNotBlank) }
                ?: json.keys().asSequence().firstNotNullOfOrNull { key -> (json.opt(key) as? String)?.takeIf(String::isNotBlank) }
        }
        text.startsWith("[") -> runCatching { org.json.JSONArray(text) }.getOrNull()?.let { json ->
            (0 until json.length()).firstNotNullOfOrNull { (json.opt(it) as? String)?.takeIf(String::isNotBlank) }
        }
        else -> text
    }
    return value?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

val ChatItem.Tool.name: String get() = call?.name ?: result?.name.orEmpty()

/** One step of a run as a line: the tool's name and what it worked on. */
fun ChatItem.Tool.stepLine(): String = listOf(name, summary()).filter { it.isNotBlank() }.joinToString(" · ")
