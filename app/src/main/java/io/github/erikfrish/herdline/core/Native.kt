/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.core

/**
 * The Rust core (native/). Sessions are opaque IDs; every call is cheap
 * and non-blocking except [nextEvent], which waits up to its timeout and must
 * run off the main thread.
 */
object Native {
    init {
        System.loadLibrary("herdline")
        init()
    }

    private external fun init()

    /** A new Ed25519 identity, as OpenSSH private key text. */
    external fun generateKey(comment: String): String

    /** The `authorized_keys` line for a stored private key. */
    external fun publicKey(privateKey: String): String

    /**
     * Starts connecting with a route document (see `native/src/config.rs`);
     * progress arrives through [nextEvent]. The document holds secrets.
     */
    external fun connect(configJson: String): Long

    /**
     * Installs or updates Herdr on the route document's last host and returns
     * `{"exit_status": …, "output": …}`. Blocks for up to ten minutes; throws
     * when the host cannot be reached.
     */
    external fun setupHerdr(configJson: String): String

    /** `{"private": …, "info": {algorithm, fingerprint, comment, public}}`; throws when unreadable. */
    external fun importKey(text: String, passphrase: String?): String

    /** Certificate details as JSON; throws when it is not a certificate. */
    external fun certificateInfo(certificate: String, privateKey: String): String

    /** `{"entries": […], "skipped": […]}` from OpenSSH client configuration text. */
    external fun parseSshConfig(text: String): String

    /** The next event as JSON, or null after [timeoutMs] without one. */
    external fun nextEvent(id: Long, timeoutMs: Int): String?

    external fun takeFrame(id: Long): ByteArray?

    external fun takeView(id: Long): String?

    /** True when the key was sent; false leaves it to text input. */
    external fun key(id: Long, pane: String?, keyCode: Int, metaState: Int, unicode: Int, repeat: Boolean): Boolean

    external fun chord(id: Long, pane: String?, codepoint: Int, metaState: Int)

    external fun text(id: Long, pane: String?, text: String)

    external fun paste(id: Long, pane: String?, text: String)

    external fun touch(id: Long, pane: String, kind: Int, column: Int, row: Int, lines: Int)

    external fun resize(id: Long, cols: Int, rows: Int, cellWidth: Int, cellHeight: Int)

    /** Invokes an endpoint method; returns the request ID. */
    external fun request(id: Long, method: String, paramsJson: String): String

    /**
     * The composer's checks for one pane, as JSON (see `composer.rs`), against
     * [viewportJson] (`viewportJson` in Viewport.kt), null when the view drew no frame.
     */
    external fun composerCheck(id: Long, pane: String, viewportJson: String?): String

    /**
     * Delivers composer text to the exact pane it names; returns the outcome as JSON.
     * May read the pane's agent instance on the host first: never call it on the main thread.
     */
    external fun composerDeliver(id: Long, requestJson: String): String

    /** The pane's OMP instance now, as JSON `{"instance": …}`; blocks on the host. */
    external fun composerInstance(id: Long, pane: String): String

    /** Holds a composer target (`{"node","session","pane"}`) until [composerUnlock]; process-wide. */
    external fun composerHold(targetJson: String)

    /** Releases a target held after an unconfirmed delivery. */
    external fun composerUnlock(targetJson: String)

    /**
     * A steer's first step: its paste alone, to the exact pane and agent
     * instance [requestJson] names; returns the outcome as JSON. Blocks on
     * the host: never call it on the main thread.
     */
    external fun composerSteer(id: Long, requestJson: String): String

    /**
     * A staged steer's one Enter, after its paste [receipt] was answered, with
     * the [viewportJson] captured at Enter (null: none drawn, refused); the
     * outcome as JSON. Blocks on the host: never call it on the main thread.
     */
    external fun composerSteerEnter(id: Long, targetJson: String, receipt: String, viewportJson: String?): String

    /** Ends the steer staged on a target without Enter; its paste stays in the pane. */
    external fun composerSteerCancel(targetJson: String)

    /** Reads a pane's agent transcript on the host; blocks, so never call it on the main thread. */
    external fun transcript(id: Long, requestJson: String): String

    /**
     * Opens one attachment upload over an SSH exec with stdin into the
     * host's private staging directory (see docs/attachments.md). Answers
     * `{"outcome":"open","upload":N}` or a refusal; blocks.
     */
    external fun attachmentBegin(id: Long, requestJson: String): String

    /** Streams [length] bytes of [bytes] to upload [upload]; blocks for backpressure, throws once it failed. */
    external fun attachmentWrite(upload: Long, bytes: ByteArray, length: Int)

    /** Ends upload [upload]: the host checks size and [sha256]. Answers staged or a refusal; blocks. */
    external fun attachmentFinish(upload: Long, sha256: String): String

    /** Abandons upload [upload]; the host removes its directory. Idempotent, never blocks. */
    external fun attachmentCancel(upload: Long)

    /** Removes a staged attachment's directory on the host; blocks. */
    external fun attachmentDiscard(id: Long, attachmentJson: String)

    external fun setTheme(id: Long, themeJson: String)

    external fun selectionText(id: Long, startColumn: Int, startRow: Int, endColumn: Int, endRow: Int): String

    external fun disconnect(id: Long)

    const val TOUCH_TAP = 0
    const val TOUCH_SCROLL_UP = 1
    const val TOUCH_SCROLL_DOWN = 2

    // KeyEvent meta-state bits the extra-keys row synthesises.
    const val META_SHIFT = 0x1
    const val META_ALT = 0x2
    const val META_CTRL = 0x1000
}
