package io.github.erikfrish.herdline.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormsLeavingTest {
    @Test
    fun a_form_that_leaves_the_stack_loses_its_state() {
        // Add node closed by Back, Save or Discard: it opens empty next time.
        assertEquals(setOf("edit:"), formsLeaving(listOf("hosts", "edit:"), listOf("hosts")))
        assertEquals(setOf("import"), formsLeaving(listOf("hosts", "import"), listOf("hosts")))
        // A node's editor left by switching to a root destination.
        assertEquals(setOf("edit:n1"), formsLeaving(listOf("hosts", "edit:n1"), listOf("hosts", "settings")))
    }

    @Test
    fun a_form_still_in_the_next_stack_and_every_other_route_keep_their_state() {
        assertEquals(emptySet<String>(), formsLeaving(listOf("hosts", "edit:n1", "session-settings"), listOf("hosts", "edit:n1")))
        assertEquals(emptySet<String>(), formsLeaving(listOf("hosts", "session:n1", "session-settings"), listOf("hosts")))
    }
}
