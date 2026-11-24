package io.github.erikfrish.herdline.ui.components

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.spring
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test
    fun motionOffSnapsInsteadOfSpringing() {
        assertTrue(motionSpec(false, jelly<Float>()) is SnapSpec)
        assertTrue(motionSpec(false, glide<Float>()) is SnapSpec)
    }

    @Test
    fun motionOnKeepsTheGivenSpec() {
        val spec = spring<Float>(dampingRatio = 0.55f, stiffness = 700f)
        assertSame(spec, motionSpec(true, spec))
    }
}
