package io.github.erikfrish.herdline.ui.components

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

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
