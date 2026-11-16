package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.TranscriptBlock
import io.github.erikfrish.herdline.core.TranscriptEntry
import io.github.erikfrish.herdline.core.TranscriptKind
import io.github.erikfrish.herdline.core.TranscriptUsage

/** SAMPLE values; the Details assert reads them back verbatim. */
object Sample {
    const val PROVIDER = "sample-provider"
    const val MODEL = "sample-model-with-a-very-long-identifier-2026-11"
    const val CWD = "/home/sample/projects/a/very/long/working/directory/path"
    const val COST = 0.0123
    val USAGE = TranscriptUsage(input = 1234, output = 567, totalTokens = 1801, cost = COST)

    /** The EARLIER assistant's metadata: must never reach the footer (latest only). */
    const val EARLIER_PROVIDER = "sample-earlier-provider"
    const val EARLIER_MODEL = "sample-earlier-model"
    val EARLIER_USAGE = TranscriptUsage(input = 9000, output = 9000, totalTokens = 18000, cost = 0.5)

    // Fence bodies, exactly as the Copy button must put them on the clipboard.
    const val BASH = "# Sample bash\nset -eu\nprintf 'sample %s\\n' \"\$HOME\"\nls -la /home/sample"
    const val JSON = "{\n  \"sample\": true,\n  \"count\": 3,\n  \"name\": \"Sample JSON\",\n  \"items\": [1, 2.5, null]\n}"
    const val PYTHON = "# Sample python\ndef sample(n: int) -> str:\n    \"\"\"Sample docstring.\"\"\"\n    return f\"sample {n}\"\n\nprint(sample(42))"
    const val PLAIN = "Sample unlabelled fence\n  keeps its indent and no colours"

    /** (language label as written after ```, body) in render order. */
    val FENCES = listOf("bash" to BASH, "json" to JSON, "python" to PYTHON, "" to PLAIN)

    const val USER_1 = "Sample user message: show me the footer and some code."
    const val USER_2 = "Sample user follow-up: and one more reply."
    const val EARLIER_REPLY = "Sample earlier reply. Its metadata belongs to a different model."
    const val NO_META_REPLY = "Sample reply without metadata: no model, provider or usage was recorded."
    val LATEST_REPLY = buildString {
        append("Sample latest reply with `inline code` and a `sample_identifier()` call.\n\n")
        FENCES.forEach { (lang, body) -> append("```").append(lang).append('\n').append(body).append("\n```\n\n") }
        append("Sample closing paragraph.")
    }
}

private fun text(s: String) = listOf(TranscriptBlock.Text(s, truncated = false))

private fun entry(
    id: String, parent: String?, n: Long, role: String, body: String,
    usage: TranscriptUsage? = null, model: String? = null, provider: String? = null,
) = TranscriptEntry(
    id = id, parentId = parent, offset = n * 1000, end = n * 1000 + 999,
    kind = TranscriptKind.Message, role = role, blocks = text(body),
    timestamp = 1_794_900_000_000 + n * 60_000, usage = usage,
    model = model, provider = provider,
)

/**
 * One branch, oldest first: an earlier assistant WITH different metadata,
 * a metadata-less assistant, then the latest assistant with the SAMPLE metadata.
 */
val SAMPLE_ENTRIES: List<TranscriptEntry> = listOf(
    entry("s1", null, 1, "user", Sample.USER_1),
    entry("s2", "s1", 2, "assistant", Sample.EARLIER_REPLY, Sample.EARLIER_USAGE, Sample.EARLIER_MODEL, Sample.EARLIER_PROVIDER),
    entry("s3", "s2", 3, "assistant", Sample.NO_META_REPLY),
    entry("s4", "s3", 4, "user", Sample.USER_2),
    entry("s5", "s4", 5, "assistant", Sample.LATEST_REPLY, Sample.USAGE, Sample.MODEL, Sample.PROVIDER),
)

/** Only a metadata-less assistant: with cwd null the footer must not render. */
val SAMPLE_EMPTY_ENTRIES: List<TranscriptEntry> = listOf(
    entry("e1", null, 1, "user", Sample.USER_1),
    entry("e2", "e1", 2, "assistant", Sample.NO_META_REPLY),
)
