# Top banner for Herdr notifications

How Herdline shows Herdr's own notifications ("needs input", "finished",
and problems the host reports unprompted) while the app is open. Code:
`ui/session/AgentBanner.kt` under the app's Kotlin package in
`app/src/main/java/`.

## 1. Routing: exactly one surface per event

| App state when the event arrives | Surface |
|---|---|
| Stopped, or the keyguard is locked | the system notification (see [notifications.md](../docs/notifications.md)) |
| Started, with this host's session screen resumed | **the top banner** |
| Started, on any other screen | the system notification |
| A Finished event for the pane on screen now (the same pane, in Chat or Terminal) | **nothing**: the user is looking at it, and the status chip already says Done |
| NeedsAttention for the pane on screen | the banner without "Open" |

- **The same toggles apply.** With *done* notifications off there is no
  Finished banner. With *attention* notifications off there is no
  NeedsAttention banner. One switch per kind, whatever the surface.
- The banner makes no sound or vibration. Bell haptics are unchanged.
- **Never both.** The surface is decided once per event, where the event is
  consumed.

## 2. The banner

```
 ┌ header (workspace · status · Chat|Terminal · ⋮) ─────────────────┐
 ├ tab row ── accent line ──────────────────────────────────────────┤  ← content slot starts here
 │ ┌──────────────────────────────────────────────────────────────┐ │  8 dp from the slot top
 │ │ ✓  omp finished                              [ Open ]   ✕   │ │  ✓ = status glyph 24 dp
 │ │    my-project › Discussion                                  │ │  location: one line, ellipsized
 │ └──────────────────────────────────────────────────────────────┘ │
 │   terminal / chat keeps its full bounds underneath               │
```

- **Placement.** The banner sits at the top of the content slot, drawn above
  the terminal or chat. Being inside the slot, it never covers the status
  bar, the header, the tab row, the composer, the side composer or the
  navigation bar.
- **Surface.** A Material 3 surface:
  - container `surfaceContainerHigh`, content `onSurface`;
  - 12 dp corners;
  - a 1 dp `outline` border, so its edge stays visible against the terminal
    behind it (at least 3:1);
  - 3 dp shadow elevation;
  - at most 560 dp wide, with 8 dp side margins.
- **Contents**, left to right:
  - **A status glyph**, 24 dp, whose shape carries the meaning (never colour
    alone): Finished is a check, NeedsAttention is `!`, and a host problem is
    a warning sign.
  - **A text column.**
    - The title: one line, ellipsized. It uses the same words as the system
      notification ("‹agent› finished" / "‹agent› needs you"), with Herdr's
      title as the fallback; never a pane ID.
    - The location: one line, ellipsized, "workspace › tab", with Herdr's
      body as the fallback.
  - **Open**, a text button at least 48 dp high. It is shown only when the
    event names a pane that still exists and is not on screen. In Chat it
    selects that pane as the local reading target; in Terminal it focuses
    that pane in Herdr's shared layout. Neither path guesses by title or tab.
    If the pane has closed before the tap, the banner says "That pane has
    closed" and does nothing else.
  - **Close**, a 48 dp ✕ button, announced as "Dismiss notification".
- **Dismissing:**
  - a horizontal swipe in either direction (at 40 % of the width, or on a
    fling);
  - Close or Open;
  - the pane becoming the one on screen;
  - a timeout: Finished closes after 8 s. NeedsAttention and problems stay
    until dismissed or until the pane leaves Blocked. The timer pauses while
    the banner is pressed. With TalkBack on, it uses Android's recommended
    accessibility timeout.
- **Several events.** One banner shows at a time. NeedsAttention and problems
  come before Finished, a newer note for a pane replaces the older one, and
  the rest wait.
- **Touch.** The banner takes touches only inside its own bounds. Everything
  outside it still goes to the terminal or chat, including scrolling and
  selection.

The text never shrinks: both lines ellipsize, and TalkBack gets the whole
text. In landscape the banner spans only the session column, not the side
composer.

## 3. Layout invariant

- **The banner is an overlay.** Showing, swiping or dismissing it never
  changes the terminal's measured size, the content slot, the composer's
  height or the terminal grid (cols × rows). It sends no resize to Herdr, so
  it cannot make a Send check report that the pane is resizing.
- **It never takes focus.** It does not request focus or show or hide the
  keyboard. With the keyboard up, the banner stays at the slot top, and the
  slot shrinks for the keyboard as usual.
- **On rotation** an open banner closes and does not re-open, and no system
  notification is posted for it afterwards.
- It is drawn above the composer's notices and strips, without pushing them
  down.

## 4. TalkBack

- The banner is one polite live region, announced once as "‹title›,
  ‹location›. Notification.", or "‹title›. Notification." without a location.
  Open and Dismiss are its children and custom actions ("Open ‹tab›", "Dismiss").
- Focus does not move to it. The announcement alone never sends a
  screen-reader user to a pane.
- Dismiss is always present, as the alternative to the swipe.

## 5. Other snackbars

Short feedback that answers something the user just did stays a bottom
snackbar, themed with the app's own colours (`surfaceContainerHighest`, an
`onSurface` text and a `primary` action, with the same 1 dp `outline`
border). That covers "Reconnected", "Chat can't read this pane" and the
key-copy or import results on other screens. These snackbars follow the same
no-resize rule as the banner.

## 6. Unchanged

The banner does not change the system notification's content, channels or
lock-screen rules, the live update, or the Agents screen. It changes no Send
rule either: nothing is ever resent automatically.

---

Herdline documentation. Copyright (c) 2026 erikfrish. Licensed under the
Apache License, Version 2.0.
