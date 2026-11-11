package io.github.erikfrish.herdline.data

/** An entry of the composer's + menu. [id] is saved in settings: never rename one. */
enum class ComposerAction(val id: String) {
    Insert("insert"),
    LargerEditor("larger_editor"),
    TerminalKeyboard("terminal_keyboard"),
    Paste("paste"),
}

/**
 * A Tabs sheet and sidebar section that may be hidden. Their order is fixed
 * (Search → Order → Filters → Places → list); Search and Order never hide.
 */
enum class SheetSection(val id: String) {
    Places("places"),
    Filters("filters"),
}

/**
 * The + menu's shown entries, as ids in their order. Send and the mode
 * control are not entries and never hide.
 *
 * Insert without Enter must keep a labelled route whenever Send is
 * unavailable (parent Q-2): the + menu entry itself, or the larger editor,
 * which has its own Insert button. The larger editor's inline arrow only
 * shows from the second line, so the + menu entry is what keeps it
 * reachable. Hence one of the two always stays in the menu. The hold notice's
 * Insert and Append are notices, never menu entries, and never hide.
 */
object ComposerMenu {
    val DEFAULT: List<String> = ComposerAction.entries.map { it.id }
    private val insert = ComposerAction.Insert.id
    private val editor = ComposerAction.LargerEditor.id

    /** The saved [ids] as actions, in their order; unknown ids are skipped. */
    fun actions(ids: List<String>): List<ComposerAction> =
        ids.mapNotNull { id -> ComposerAction.entries.firstOrNull { it.id == id } }

    /** Whether [ids] still reach Insert without Enter: from the menu, or through the larger editor. */
    fun keepsInsert(ids: List<String>): Boolean = insert in ids || editor in ids

    /** Whether the shown entry [id] may be hidden without losing Insert's route. */
    fun mayHide(ids: List<String>, id: String): Boolean = id in ids && keepsInsert(ids - id)

    /** [ids] with [id] shown at the end, or hidden; a hide that would lose Insert's route changes nothing. */
    fun toggled(ids: List<String>, id: String): List<String> = when {
        id !in ids -> ids + id
        mayHide(ids, id) -> ids - id
        else -> ids
    }

    /** [ids] with Insert shown again if neither route is left; for saved lists from any source. */
    fun repaired(ids: List<String>): List<String> = if (keepsInsert(ids)) ids else ids + insert
}

/** The Tabs sheet and sidebar's shown sections, as ids. */
object SheetSections {
    val DEFAULT: List<String> = SheetSection.entries.map { it.id }

    fun shows(ids: List<String>, section: SheetSection): Boolean = section.id in ids
}

/**
 * A saved list of shown controls read back against today's [catalogue]:
 * unknown ids are dropped, and a control the saved [known] list has never
 * seen (one added in a later version) is appended shown, so an update never
 * hides a control silently. Nothing saved yet means [catalogue] in order.
 */
fun savedControls(shown: List<String>?, known: List<String>?, catalogue: List<String>): List<String> {
    if (shown == null) return catalogue
    val kept = shown.filter { it in catalogue }.distinct()
    val seen = known.orEmpty().toSet()
    return kept + catalogue.filter { it !in seen && it !in kept }
}

/** How many of [catalogue] are hidden from [shown]. */
fun hiddenCount(shown: List<String>, catalogue: List<String>): Int = catalogue.count { it !in shown }
