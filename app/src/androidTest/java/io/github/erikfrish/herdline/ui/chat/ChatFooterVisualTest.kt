package io.github.erikfrish.herdline.ui.chat

import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.TranscriptEntry
import io.github.erikfrish.herdline.ui.theme.HerdlineTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class ChatFooterVisualTest {
companion object {
/** One capture: theme × width × font scale, plus the dense and cached extras. */
data class Shot(val name: String, val dark: Boolean, val narrow: Boolean, val fontScale: Float, val dense: Boolean = false, val cached: Boolean = false)

/** 8 matrix shots + dense (footer inside the list) + cached=true = 10. */
val SHOTS: List<Shot> = buildList {
    for (dark in listOf(false, true)) for (narrow in listOf(false, true)) for (scale in listOf(1.0f, 1.3f)) {
        add(Shot("${if (dark) "dark" else "light"}-${if (narrow) "narrow320" else "normal"}-font${if (scale == 1.0f) "100" else "130"}", dark, narrow, scale))
    }
    add(Shot("light-normal-font100-dense", dark = false, narrow = false, fontScale = 1.0f, dense = true))
    add(Shot("dark-normal-font100-cached", dark = true, narrow = false, fontScale = 1.0f, cached = true))
}

/** Where the PNGs go: the app's own files dir (debuggable .qa), pulled with `run-as … cat`. */
const val SHOT_DIR = "chat-footer"
}

/**
 * A SAMPLE Chat surface: messages through the real [Markdown], the real [ChatFooter] built by
 * [chatFooterData]. Normal: the footer is a fixed strip after the list, above where the composer
 * sits. Dense: the footer is the list's last item, no extra fixed row.
 * Narrow width and font scale are applied HERE (no `wm size`, no system font scale).
 */
@Composable
private fun SampleChatSurface(shot: Shot, entries: List<TranscriptEntry>, cwd: String?, layer: GraphicsLayer? = null) {
    HerdlineTheme(dark = shot.dark) {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = shot.fontScale)) {
            // Fixture choice, not parent API: the session accent stand-in is the theme's primary.
            val colors = chatColors(MaterialTheme.colorScheme.primary)
            val footer = remember(entries) { chatFooterData(entries) }
            val strip = @Composable { ChatFooter(footer, cwd, shot.cached, colors) }
            // Unbounded height so the WHOLE sample is recorded, not only the window (see capture()).
            Box(Modifier.verticalScroll(rememberScrollState())) {
                Column(
                    Modifier
                        .then(if (shot.narrow) Modifier.width(320.dp) else Modifier.fillMaxWidth())
                        .then(if (layer != null) Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) } else Modifier)
                        .background(colors.bg),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (e in currentBranch(entries)) {
                            val body = e.blocks.filterIsInstance<io.github.erikfrish.herdline.core.TranscriptBlock.Text>().joinToString("\n") { it.text }
                            if (e.role == "user") {
                                // Stand-in bubble: the real user row is private in ChatView.kt and is not under test.
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                    Text(body, style = ChatType.body.copy(color = colors.text), modifier = Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(16.dp)).background(colors.userBubble).padding(12.dp))
                                }
                            } else {
                                Markdown(body, colors, dense = shot.dense)
                            }
                        }
                        if (shot.dense) strip()
                    }
                    if (!shot.dense) strip()
                }
            }
        }
    }
}

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun save(name: String, bitmap: Bitmap) {
        val dir = File(context.filesDir, SHOT_DIR).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    /** Captures only: no logic asserts here. One setContent, the shot swapped through state. */
    @Test
    fun capturesTenShots() {
        var shot by mutableStateOf(SHOTS.first())
        lateinit var layer: GraphicsLayer
        compose.setContent {
            layer = rememberGraphicsLayer()
            SampleChatSurface(shot, SAMPLE_ENTRIES, Sample.CWD, layer)
        }
        for (s in SHOTS) {
            compose.runOnIdle { shot = s }
            compose.waitForIdle()
            val image = runBlocking { layer.toImageBitmap() }
            assertTrue("${s.name}: empty capture", image.width > 0 && image.height > 0)
            save(s.name, image.asAndroidBitmap())
        }
        File(context.filesDir, "$SHOT_DIR/MANIFEST.txt").writeText(SHOTS.joinToString("\n") { "${it.name}.png dark=${it.dark} narrow=${it.narrow} fontScale=${it.fontScale} dense=${it.dense} cached=${it.cached}" } + "\n")
    }

    /** Assert 1: Copy puts the exact fence body on the clipboard, for every fence. */
    @Test
    fun copyPutsTheExactFenceBytesOnTheClipboard() {
        compose.setContent { SampleChatSurface(SHOTS.first(), SAMPLE_ENTRIES, Sample.CWD) }
        compose.onAllNodesWithTag("chat:code").assertCountEquals(Sample.FENCES.size)
        Sample.FENCES.forEachIndexed { i, (_, body) ->
            compose.onAllNodesWithTag("chat:code:copy")[i].performScrollTo().performClick()
            val clip = compose.runOnIdle {
                context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
            }
            assertEquals("fence $i", body, clip)
        }
    }

    /** Assert 2: Details shows the full latest values, labelled per reply, with tokens; never the earlier reply's. */
    @Test
    fun detailsShowTheLatestReplyOnly() {
        compose.setContent { SampleChatSurface(SHOTS.first(), SAMPLE_ENTRIES, Sample.CWD) }
        compose.onNodeWithTag("chat:footer").performScrollTo().performClick()
        val r = context.resources
        compose.onNodeWithText(r.getString(R.string.chat_footer_details)).assertExists()
        compose.onNodeWithText(r.getString(R.string.chat_footer_provenance)).assertExists()
        compose.onNodeWithText(r.getString(R.string.chat_footer_provider, Sample.PROVIDER)).assertExists()
        compose.onNodeWithText(r.getString(R.string.chat_footer_model, Sample.MODEL)).assertExists()
        compose.onNodeWithText(r.getString(R.string.chat_footer_directory, Sample.CWD)).assertExists()
        val price = replyCost(Sample.COST, Locale.getDefault())!!
        compose.onNode(hasText(r.getString(R.string.chat_footer_reply_cost, price)) and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText(r.getString(R.string.chat_footer_tokens, Sample.USAGE.input, Sample.USAGE.output, Sample.USAGE.totalTokens)).assertExists()
        // Latest only: nothing of the earlier assistant's metadata, and no sum of the two costs.
        compose.onAllNodesWithText(Sample.EARLIER_MODEL, substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(Sample.EARLIER_PROVIDER, substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(replyCost(Sample.COST + 0.5, Locale.getDefault())!!, substring = true).assertCountEquals(0)
    }

    /** Assert 3: no recorded metadata and no cwd renders no footer, cached or not. */
    @Test
    fun emptyMetadataRendersNoFooter() {
        var cached by mutableStateOf(false)
        compose.setContent { SampleChatSurface(SHOTS.first().copy(cached = cached), SAMPLE_EMPTY_ENTRIES, cwd = null) }
        compose.onAllNodesWithTag("chat:footer").assertCountEquals(0)
        compose.runOnIdle { cached = true }
        compose.onAllNodesWithTag("chat:footer").assertCountEquals(0)
    }
}
