package io.github.erikfrish.herdline.ui.components

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext

/** Presses, pops and bounces: a visible overshoot, about 12.6% of the move. */
fun <T> jelly(): SpringSpec<T> = spring(dampingRatio = 0.55f, stiffness = 700f)

/** Movement and colour: Jelly's speed, barely any overshoot (about 1.5%). */
fun <T> glide(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 700f)

/**
 * The still switch every animated value goes through: [spec] while motion is
 * [allowed] ([motionAllowed]), else a snap to the target. Impulses (a pop, a
 * bounce) skip their call instead, since snapping to where they started is nothing.
 */
fun <T> motionSpec(allowed: Boolean, spec: AnimationSpec<T>): AnimationSpec<T> = if (allowed) spec else snap()

/**
 * Squashes what this modifier and everything after it draws to [pressed] while
 * [interaction] is pressed, and springs back with [jelly] on release or cancel.
 * The scale is a draw transform: layout, touch area and semantic bounds stay at
 * rest size (a graphicsLayer would scale the bounds of the semantics after it).
 * Share the source given to the clickable, and put this before its clip and
 * background so the ripple scales with the shape. With motion off the scale
 * stays 1 and the ripple alone answers the press. A [pressed] of 1 releases.
 */
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float): Modifier = composed {
    val allowed = motionAllowed()
    val down by interaction.collectIsPressedAsState()
    val squash = animateFloatAsState(if (down && allowed) pressed else 1f, motionSpec(allowed, jelly()), label = "pressScale")
    drawWithContent {
        val s = squash.value
        if (s == 1f) drawContent() else scale(s) { this@drawWithContent.drawContent() }
    }
}

/**
 * Whether decorative motion may run: false while the system animator duration
 * scale is 0 ("Remove animations", or a test rig). Asked explicitly because
 * Compose's own handling is not a contract: an infinite transition at scale 0
 * holds its end value, which says nothing about the state it stood for. Every
 * moving signal needs a still form that says the same.
 */
@Composable
fun motionAllowed(): Boolean {
    val context = LocalContext.current
    remember(Unit) { AnimatorScale.watch(context.applicationContext) }
    return AnimatorScale.allowed.value
}

/** One observer for the whole process, however many glyphs ask; composition and its callbacks run on the main thread. */
private object AnimatorScale {
    val allowed = mutableStateOf(true)
    private var watching = false

    fun watch(context: Context) {
        if (watching) return
        watching = true
        val resolver = context.contentResolver
        fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        allowed.value = read()
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    allowed.value = read()
                }
            },
        )
    }
}
