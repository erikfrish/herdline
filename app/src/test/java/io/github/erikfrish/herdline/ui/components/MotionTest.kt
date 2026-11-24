package io.github.erikfrish.herdline.ui.components

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
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

    /**
     * The QA stand's NodeCard: 1080 px wide at 420 dpi, less its 16 dp margins, is 996 px.
     * Its release must show a frame at least 1 px wider than at rest and stop changing within
     * 300 ms, at 60 fps whatever the frame phase (motion spec 1, acceptance).
     */
    @Test
    fun nodeCardReleaseOvershootsByAPixelAndSettlesWithin300Ms() {
        val release = TargetBasedAnimation(jelly(pressThreshold(0.97f)), Float.VectorConverter, 0.97f, 1f)
        val endMs = release.durationNanos / 1_000_000.0
        assertTrue("settles in $endMs ms", endMs <= 300.0)
        for (phaseMs in 0 until 17) {
            val frames = generateSequence(phaseMs.toDouble()) { it + 1000.0 / 60 }.takeWhile { it <= endMs }
            val wider = frames.map { (release.getValueFromNanos((it * 1e6).toLong()) - 1f) * 996f }.max()
            assertTrue("phase $phaseMs ms: widest frame +$wider px", wider >= 1f)
        }
    }

    /** Every press depth springs through its overshoot and settles in the same time. */
    @Test
    fun everyPressDepthOvershootsAndSettlesWithin300Ms() {
        for (pressed in listOf(0.97f, 0.92f, 0.90f, 1f)) {
            val from = if (pressed == 1f) 0.97f else pressed
            val release = TargetBasedAnimation(jelly(pressThreshold(pressed)), Float.VectorConverter, from, 1f)
            assertTrue("$pressed settles in ${release.durationNanos / 1_000_000} ms", release.durationNanos <= 300_000_000L)
            val peak = (0..release.durationNanos / 1_000_000).maxOf { release.getValueFromNanos(it * 1_000_000) }
            assertTrue("$pressed peaks at $peak", peak - 1f >= (1f - from) * 0.12f)
        }
    }
}
