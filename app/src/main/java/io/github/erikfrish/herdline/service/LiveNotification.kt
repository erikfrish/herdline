package io.github.erikfrish.herdline.service

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.data.IconVariant
import io.github.erikfrish.herdline.data.IconVariants

/** Selected app artwork in the content area; Android still owns the system header. */
internal fun NotificationCompat.Builder.liveArtwork(
    context: Context,
    variant: IconVariant,
    title: String,
    text: String?,
): NotificationCompat.Builder {
    val content = RemoteViews(context.packageName, R.layout.notification_live).apply {
        setImageViewBitmap(R.id.live_icon, IconVariants.icon(context, variant))
        setTextViewText(R.id.live_title, title)
        setTextViewText(R.id.live_text, text)
        setViewVisibility(R.id.live_text, if (text.isNullOrBlank()) View.GONE else View.VISIBLE)
    }
    return setLargeIcon(null as android.graphics.Bitmap?)
        .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        .setCustomContentView(content)
        .setCustomBigContentView(content)
        .setRequestPromotedOngoing(false)
}
