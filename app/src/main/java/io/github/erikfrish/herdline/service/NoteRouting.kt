package io.github.erikfrish.herdline.service

/** Where one Herdr notification is shown (BN-1 §2): exactly one place, or nowhere. */
internal enum class NoteSurface { System, Banner, None }

/**
 * The one decision both [ConnectionService] and the session screen make for
 * a notification, so it never shows twice and is never lost. [viewing] is
 * whether this host's session screen is resumed, [locked] the keyguard, and
 * [enabled] the user's switch for its kind (notifyDone or notifyAttention).
 * Off Hosts, Settings or another host's session it is the system's.
 */
internal fun routeNote(viewing: Boolean, locked: Boolean, enabled: Boolean): NoteSurface = when {
    !enabled -> NoteSurface.None
    viewing && !locked -> NoteSurface.Banner
    else -> NoteSurface.System
}
