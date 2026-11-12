package io.github.erikfrish.herdline.ui.nodes

import org.junit.Assert.assertEquals
import org.junit.Test

class HostChipsTest {
    @Test
    fun two_identical_chips_read_as_one_with_a_count() {
        assertEquals(listOf(ChipGroup("pi" to "blocked", 2)), chipGroups(listOf("pi" to "blocked", "pi" to "blocked")))
    }

    @Test
    fun another_program_or_another_state_stays_its_own_chip() {
        assertEquals(
            listOf(ChipGroup("pi" to "blocked", 1), ChipGroup("omp" to "blocked", 1), ChipGroup("pi" to "working", 1)),
            chipGroups(listOf("pi" to "blocked", "omp" to "blocked", "pi" to "working")),
        )
    }

    @Test
    fun groups_keep_the_order_each_key_first_appears() {
        assertEquals(
            listOf(ChipGroup("omp", 2), ChipGroup("pi", 3), ChipGroup("kilo", 1)),
            chipGroups(listOf("omp", "pi", "omp", "pi", "kilo", "pi")),
        )
    }
}
