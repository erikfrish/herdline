package io.github.erikfrish.herdline.core

import org.json.JSONObject

/**
 * What the terminal view last drew of the frame, as `TerminalView.viewport()`
 * reports it: the native core admits a composer delivery only when the
 * target's input box (OMP) or cursor row (any other pane) lies inside
 * [first]..[last] of a frame of this size (HG-VIS).
 */
data class Viewport(
    val frameRows: Int,
    val frameCols: Int,
    /** The rows and columns the view last sent in `Native.resize`. */
    val gridRows: Int,
    val gridCols: Int,
    /** Frame rows wholly drawn, inclusive; a partly drawn row never counts. */
    val first: Int,
    val last: Int,
    /** The drawable area in view pixels; y = 0 at the view's top. */
    val rect: Rect,
    /** The view's own offsetY, unrounded. */
    val offsetYPx: Float,
    /** The view's cell size as it draws, not the rounded grid values. */
    val cellWidthPx: Float,
    val cellHeightPx: Float,
) {
    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)
}

/**
 * [viewport] for the native core, bound to the target it was captured for;
 * JSON null when the view has drawn no frame yet, which the core refuses.
 */
internal fun viewportJson(
    nodeId: String,
    session: String,
    boot: String?,
    pane: String,
    instance: TranscriptIdentity?,
    viewport: Viewport?,
): Any {
    val v = viewport ?: return JSONObject.NULL
    return JSONObject()
        .put("node", nodeId)
        .put("session", session)
        .put("boot", boot ?: JSONObject.NULL)
        .put("pane", pane)
        .put("instance", instance?.toJson() ?: JSONObject.NULL)
        .put("frame_rows", v.frameRows)
        .put("frame_cols", v.frameCols)
        .put("grid_rows", v.gridRows)
        .put("grid_cols", v.gridCols)
        .put("first", v.first)
        .put("last", v.last)
        .put(
            "rect",
            JSONObject().put("x", v.rect.x).put("y", v.rect.y).put("width", v.rect.width).put("height", v.rect.height),
        )
        .put("offset_y_px", v.offsetYPx.toDouble())
        // Wire contract: the native core still takes a hidden-row count and a bottom clip; the app hides and clips nothing.
        .put("hidden_rows", 0)
        .put("clip_bottom_px", 0)
        .put("cell_width_px", v.cellWidthPx.toDouble())
        .put("cell_height_px", v.cellHeightPx.toDouble())
}
