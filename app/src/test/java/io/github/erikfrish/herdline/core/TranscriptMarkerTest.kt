package io.github.erikfrish.herdline.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptMarkerTest {
    private fun parse(json: String) = TranscriptBlock.parse(JSONObject(json))

    @Test
    fun each_marker_kind_reads_with_its_item_text() {
        assertEquals(TranscriptBlock.Marker(MarkerKind.StoppedByYou, "", false), parse("""{"type":"marker","marker":"stopped_by_you","text":"","truncated":false}"""))
        assertEquals(TranscriptBlock.Marker(MarkerKind.Stopped, "", false), parse("""{"type":"marker","marker":"stopped","text":"","truncated":false}"""))
        assertEquals(TranscriptBlock.Marker(MarkerKind.Done, "read the log", false), parse("""{"type":"marker","marker":"done","text":"read the log","truncated":false}"""))
        assertEquals(TranscriptBlock.Marker(MarkerKind.ToDo, "ship", true), parse("""{"type":"marker","marker":"to_do","text":"ship","truncated":true}"""))
    }

    @Test
    fun a_kind_from_a_newer_core_keeps_its_text_or_shows_nothing() {
        assertEquals(TranscriptBlock.Text("paused here", false), parse("""{"type":"marker","marker":"paused","text":"paused here","truncated":false}"""))
        assertNull(parse("""{"type":"marker","marker":"paused","text":" ","truncated":false}"""))
        assertNull(parse("""{"type":"marker","text":""}"""))
    }
}
