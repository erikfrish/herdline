package io.github.erikfrish.herdline.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.ImageSource
import io.github.erikfrish.herdline.core.MAX_IMAGE_BYTES
import io.github.erikfrish.herdline.core.MediaAnswer
import io.github.erikfrish.herdline.core.MediaRefusal
import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.core.TranscriptImage
import io.github.erikfrish.herdline.ui.components.motionAllowed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Base64

/** The longest edge and the most pixels of a decoded thumbnail. */
internal const val THUMB_EDGE = 512
internal const val THUMB_PIXELS = 512L * 512

/** The longest edge and the most pixels of the bitmap the viewer decodes. */
internal const val FULL_EDGE = 2048
internal const val FULL_PIXELS = 2048L * 2048

/** A source image with more pixels is not decoded at all, sampled or not. */
internal const val MAX_SOURCE_PIXELS = 100_000_000L

/** The padded base64 length of [MAX_IMAGE_BYTES]: a longer answer is refused before decoding. */
internal const val MAX_IMAGE_BASE64 = (MAX_IMAGE_BYTES + 2) / 3 * 4

/** Thumbnails kept for one pane's chat, by decoded size. */
private const val THUMB_CACHE_BYTES = 16 * 1024 * 1024

/** An image as chat draws it: loading, decoded, or why not. */
internal sealed interface ImageLoad {
    data object Loading : ImageLoad
    data class Shown(val bitmap: Bitmap) : ImageLoad
    /** [refusal] null: the link or the reply failed, and another try may work. */
    data class Failed(val refusal: MediaRefusal?) : ImageLoad
}

/** Why [source] is not fetched at all: an unreadable reference, or a size over the cap. */
internal fun refusalBeforeFetch(source: ImageSource): MediaRefusal? = when (source) {
    is ImageSource.Inline -> MediaRefusal.TooLarge.takeIf { source.bytes > MAX_IMAGE_BYTES }
    is ImageSource.Blob -> null
    ImageSource.Unreadable -> MediaRefusal.Unsupported
}

/** Why a source of [width] by [height] pixels is not decoded: not an image, or too many pixels. */
internal fun sourceRefusal(width: Int, height: Int): MediaRefusal? = when {
    width <= 0 || height <= 0 -> MediaRefusal.Unsupported
    width.toLong() * height > MAX_SOURCE_PIXELS -> MediaRefusal.TooLarge
    else -> null
}

/**
 * The power-of-two `inSampleSize` that brings a [width] by [height] source
 * within [edge] on both sides and [maxPixels] in all, rounding the sampled
 * sides up as a decoder may.
 */
internal fun sampleSize(width: Int, height: Int, edge: Int, maxPixels: Long): Int {
    var sample = 1
    while (true) {
        val w = (width + sample - 1L) / sample
        val h = (height + sample - 1L) / sample
        if (w <= edge && h <= edge && w * h <= maxPixels) return sample
        sample *= 2
    }
}

/** [data], an answer's base64, decoded within [edge] and [maxPixels]. Call it off the main thread. */
internal fun decodeImage(data: String, edge: Int, maxPixels: Long): ImageLoad {
    if (data.length > MAX_IMAGE_BASE64) return ImageLoad.Failed(MediaRefusal.TooLarge)
    val bytes = try {
        Base64.getDecoder().decode(data)
    } catch (e: IllegalArgumentException) {
        return ImageLoad.Failed(MediaRefusal.Unsupported)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    sourceRefusal(bounds.outWidth, bounds.outHeight)?.let { return ImageLoad.Failed(it) }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, edge, maxPixels) }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return ImageLoad.Failed(MediaRefusal.Unsupported)
    if (bitmap.width.toLong() * bitmap.height > maxPixels) {
        bitmap.recycle()
        return ImageLoad.Failed(MediaRefusal.TooLarge)
    }
    return ImageLoad.Shown(bitmap)
}

/**
 * One pane's chat images. Reads run one at a time, so at most one answer's
 * base64 is in memory; only thumbnails are kept, in a cache bounded by
 * size and dropped whole when the transcript's instance changes. Keys are
 * the image's line and part ([TranscriptImage.key]), which hold for one
 * session file on any branch.
 */
internal class ChatMedia {
    private val thumbs = object : LruCache<String, Bitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var of: TranscriptIdentity? = null
    private val reads = Mutex()

    @Synchronized
    private fun thumbsOf(identity: TranscriptIdentity): LruCache<String, Bitmap> {
        if (identity != of) {
            thumbs.evictAll()
            of = identity
        }
        return thumbs
    }

    fun cached(identity: TranscriptIdentity, image: TranscriptImage): Bitmap? = thumbsOf(identity).get(image.key)

    /** [image] of [identity], read with [fetch] (blocking) and decoded as a thumbnail or for the viewer. */
    suspend fun load(identity: TranscriptIdentity, image: TranscriptImage, thumb: Boolean, fetch: (TranscriptImage) -> Result<MediaAnswer>): ImageLoad {
        if (thumb) cached(identity, image)?.let { return ImageLoad.Shown(it) }
        refusalBeforeFetch(image.source)?.let { return ImageLoad.Failed(it) }
        val load = reads.withLock {
            when (val answer = withContext(Dispatchers.IO) { fetch(image) }.getOrNull()) {
                null -> ImageLoad.Failed(null)
                is MediaAnswer.Refused -> ImageLoad.Failed(answer.refusal)
                is MediaAnswer.Image -> withContext(Dispatchers.Default) {
                    if (thumb) decodeImage(answer.data, THUMB_EDGE, THUMB_PIXELS) else decodeImage(answer.data, FULL_EDGE, FULL_PIXELS)
                }
            }
        }
        if (thumb && load is ImageLoad.Shown) thumbsOf(identity).put(image.key, load.bitmap)
        return load
    }
}

/** What chat's image rows read with: the pane's [media], the shown instance, and its media read. */
internal class ChatImages(
    private val media: ChatMedia,
    private val identity: TranscriptIdentity,
    private val fetch: (TranscriptImage) -> Result<MediaAnswer>,
) {
    fun cached(image: TranscriptImage): Bitmap? = media.cached(identity, image)
    suspend fun load(image: TranscriptImage, thumb: Boolean): ImageLoad = media.load(identity, image, thumb, fetch)
}

internal val LocalChatImages = staticCompositionLocalOf<ChatImages?> { null }

private val ThumbShape = RoundedCornerShape(12.dp)

/**
 * [image] as a fixed-size thumbnail, read when it is first drawn. A tap on
 * it opens the viewer; a tap on a failed read tries again. Under TalkBack
 * the thumbnail takes focus again when its viewer closes, so the reader is
 * back on the image they came from; without TalkBack focus is never moved,
 * so a tap here never takes the keyboard from the composer.
 */
@Composable
internal fun ChatImageThumb(image: TranscriptImage, colors: ChatColors, modifier: Modifier = Modifier) {
    val images = LocalChatImages.current
    var attempt by remember(image.key) { mutableIntStateOf(0) }
    var viewing by remember(image.key) { mutableStateOf(false) }
    var returning by remember(image.key) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    val load by produceState<ImageLoad>(ImageLoad.Loading, images, image.key, attempt) {
        value = images?.cached(image)?.let(ImageLoad::Shown) ?: run {
            value = ImageLoad.Loading
            images?.load(image, thumb = true) ?: ImageLoad.Failed(null)
        }
    }
    val described = when (val now = load) {
        ImageLoad.Loading -> stringResource(R.string.chat_image_loading)
        is ImageLoad.Shown -> stringResource(R.string.chat_image_open)
        is ImageLoad.Failed -> failureText(now.refusal)
    }
    LaunchedEffect(returning) {
        if (!returning) return@LaunchedEffect
        returning = false
        // One frame on, once the viewer's window is gone and this one has focus to give.
        withFrameNanos { }
        // Read now, not once at composition, so TalkBack turned on while in Chat counts.
        val talkBack = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        if (talkBack) focus.requestFocus()
    }
    Box(
        modifier
            .size(width = 200.dp, height = 150.dp)
            .clip(ThumbShape)
            .background(colors.codeBg)
            .border(1.dp, colors.border, ThumbShape)
            // clickable is the one focus target; under TalkBack touch mode is off, so it can take focus.
            .focusRequester(focus)
            .clickable(role = Role.Button, enabled = load != ImageLoad.Loading) {
                when (val now = load) {
                    is ImageLoad.Shown -> viewing = true
                    is ImageLoad.Failed -> if (now.refusal == null) attempt++
                    ImageLoad.Loading -> Unit
                }
            }
            .semantics { contentDescription = described }
            .testTag("chat:image"),
        contentAlignment = Alignment.Center,
    ) {
        when (val now = load) {
            ImageLoad.Loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
            is ImageLoad.Shown -> Image(now.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            is ImageLoad.Failed -> Text(described, style = ChatType.meta.copy(color = colors.muted), textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
        }
    }
    if (viewing && images != null) {
        ImageViewer(image, images, colors) {
            viewing = false
            returning = true
        }
    }
}

/** The viewer's own entrance and exit: a fade with a scale up from this, and back. */
private const val VIEWER_FROM_SCALE = 0.92f

/**
 * [image] read again at viewer size, over the whole screen, with its loading
 * and error states. It fades in while growing from [VIEWER_FROM_SCALE] (200 ms)
 * and leaves the same way in reverse (150 ms), drawn only, so nothing under it
 * moves; with motion off both are instant. Back and the close button start
 * the exit (the viewer fills the screen, so there is no outside to tap), and
 * [onClose] comes once it is over.
 */
@Composable
private fun ImageViewer(image: TranscriptImage, images: ChatImages, colors: ChatColors, onClose: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    val load by produceState<ImageLoad>(ImageLoad.Loading, attempt) {
        value = ImageLoad.Loading
        value = images.load(image, thumb = false)
    }
    val motion = motionAllowed()
    // With motion off it starts shown: the first frame is the final one.
    val shown = remember { MutableTransitionState(!motion).apply { targetState = true } }
    val closed by rememberUpdatedState(onClose)
    val close = { shown.targetState = false }
    LaunchedEffect(shown) {
        snapshotFlow { shown.isIdle && !shown.currentState }.first { it }
        closed()
    }
    val transition = rememberTransition(shown, label = "viewer")
    val spec: @Composable Transition.Segment<Boolean>.() -> FiniteAnimationSpec<Float> = {
        when {
            !motion -> snap()
            false isTransitioningTo true -> tween(200, easing = LinearOutSlowInEasing)
            else -> tween(150, easing = FastOutLinearInEasing)
        }
    }
    val alpha = transition.animateFloat(spec, label = "alpha") { if (it) 1f else 0f }
    val scale = transition.animateFloat(spec, label = "scale") { if (it) 1f else VIEWER_FROM_SCALE }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The window's own animation would run on top of this one, and even with motion off.
        // Dialog shows its window before this content first composes and offers no
        // animation setting, so this may only land after the first show (E-1 / E-3 frames decide).
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { window ->
            SideEffect { window.setWindowAnimations(0) }
        }
        Box(
            Modifier
                .fillMaxSize()
                // Read in the layer: the entrance and exit redraw without recomposing or laying out.
                .graphicsLayer {
                    this.alpha = alpha.value
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .background(Color.Black)
                .testTag("chat:image-viewer"),
            contentAlignment = Alignment.Center,
        ) {
            when (val now = load) {
                ImageLoad.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = colors.accent)
                is ImageLoad.Shown -> Image(
                    now.bitmap.asImageBitmap(),
                    stringResource(R.string.chat_image),
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                is ImageLoad.Failed -> Text(
                    failureText(now.refusal),
                    style = ChatType.body.copy(color = Color.White),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(24.dp)
                        .clickable(role = Role.Button, enabled = now.refusal == null) { attempt++ },
                )
            }
            IconButton(onClick = close, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Default.Close, stringResource(R.string.chat_image_close), tint = Color.White)
            }
        }
    }
}

@Composable
private fun failureText(refusal: MediaRefusal?): String = stringResource(
    when (refusal) {
        null -> R.string.chat_image_failed
        MediaRefusal.InstanceChanged, MediaRefusal.Moved -> R.string.chat_image_gone
        MediaRefusal.TooLarge -> R.string.chat_image_too_large
        MediaRefusal.Unsupported, MediaRefusal.Version -> R.string.chat_image_unsupported
        MediaRefusal.Missing, MediaRefusal.Unconfined, MediaRefusal.UnknownStore, MediaRefusal.Corrupt -> R.string.chat_image_unreadable
    },
)
