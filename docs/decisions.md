# Herdline: decisions

The design record: what was decided, why, and what stays open. Code paths
are relative to the app's Kotlin package under `app/src/main/java/` unless
they start with `native/`.

## Identity

- Application ID `io.github.erikfrish.herdline`, name "Herdline". Releases are
  signed with Herdline's own key, which is kept outside the repository
  (`HERDLINE_KEYSTORE*` variables). The app installs beside Bellterm and
  never replaces it.
- Updates come only from Herdline's own fixed manifest on GitHub (see the
  README). It has a stable and a candidate release, and a candidate is marked
  *not tested*.

## Composer

- **Destination identity** is (node, session, daemon boot, pane, program,
  agent instance). Pane IDs are unique only within one boot, so a draft
  written for one boot or program waits for the user to confirm the pane
  again (`Draft.binding`). For OMP, the instance is the pane's agent process
  and session file (`pane_id`, `terminal_id`, `pid`, session path). It is
  read on the host with `pane get` and `pane process-info`, because Herdr's
  snapshot names only the program. A written draft records it. Another
  process or session in the pane (`/exit` then `omp`, or a resumed session in
  a new process) shows *restarted in this pane* with *Use for this pane*, and
  nothing goes until the user takes it. While the instance cannot be read, a
  draft that recorded one is held (fail closed). The native core compares it
  again at send time. A draft just moved to another pane has no instance: it
  stays bound and takes the first instance read while bound. Plain terminals
  have none.
- **Draft addressing.** The composer writes to one pane at a time. While its
  draft is empty, it follows the pane the user views: in Chat the pane read
  here (see [Reading here and the desktop](#reading-here-and-the-desktop)),
  in Terminal Herdr's focused pane. Once it holds text, it stays with its
  pane however the view changes (`composerDestination`). Viewing another
  pane says so, with two explicit actions:
  - *Open* goes to the draft's pane: in Chat it reads it here, in Terminal
    it focuses it.
  - *Move here* moves the text to the viewed pane, bound to that pane as it
    is now. If that pane holds a draft of its own, that draft is shown
    instead and never overwritten (`Draft.movedOnto`).

  A delivery in flight cannot be moved. Nothing is cleared, sent or
  relocated on its own.
- **Delivery bypasses popup routing.** `composer_deliver` writes with
  `send_input` to the named pane. It never follows `Session::input`'s
  redirect into an open popup, and it refuses while one is open.
- **Insert** = one bracketed paste. Multiline text stays one paste, never a
  series of Enters. It is allowed while an agent works (OMP keeps typed
  text). It is refused while the agent is blocked, because pasted text could
  answer an approval.
- **Send** = paste + one Enter, only where submitting is understood. That
  means OMP with status idle or done, the pane on screen and not scrolled
  back, and its input box read from the rendered rows in the exact empty
  shape: `╭──` directly above `╰─`, nothing below, blank or a right-aligned
  hint. Everything else fails closed to Insert only. Other agents are not
  enabled until their editors are verified the same way.
- **Short panes refuse Send.** At 7 rows or fewer, OMP 18.18.3 draws only the
  cursor line of its box, so the draft `abc⏎` renders as the empty shape. So
  Send needs the pane's terminal to be at least 12 rows (`viewport_rows`, the
  size the program lays out for). While the composer's field has the
  keyboard, the terminal keeps the grid's rows (`TerminalView.holdGrid`) and
  shows its bottom rows. The daemon's pane stays tall, so Send stays allowed
  with the keyboard up; the rule itself is unchanged. Columns still follow the
  view's width, so the side composer never hides a column the grid kept.
- **Output focus.** In landscape, while typing in the side column, the user
  can arm a window over the terminal from the composer's + menu or the
  session ⋮ (phone). It scrolls the grid's bottom rows past the view, by
  whole rows. Nothing is removed and Herdr is not told: the grid stays the
  one reported for the whole view. A 48 dp strip under it counts the hidden
  rows and shows them all in one tap.
  - The count comes from a best-effort match (`ui/terminal/OutputFocus.kt`):
    the nearest full-width top border at most 3 rows above the cursor in the
    bottom-most pane. It is kept only if it hides 1 to min(8, rows − 3)
    rows, and no string says what the rows hold.
  - Detection only ever shows more, never hides more, and stops once the user
    drags or selects.
  - A delivery disarms it. A receipt notice, raw keys or Chat show the full
    terminal, and a reconnect starts with it off.
- **Landscape keeps the rule.** A phone held sideways with the keyboard up is
  for reading, and for Insert where its own checks pass. A refused Send names
  the reason and the pane's actual rows. Zoom (shared) is offered, never
  applied for the user, and Send is rechecked from Herdr's next frame. "Hide
  keyboard" and "Rotate to portrait" are offered only when an estimate from
  the measured geometry (`ui/session/PaneRows.kt`) gives the pane 12 rows.
  The estimate counts the pane's share of the grid, the tallest regular
  composer at the font scale, and a generous allowance for portrait's chrome.
- **Shared pane size.** Herdr sizes a pane's terminal for one client. With
  Herdr 0.9.5, after another client connected and left, later resizes from
  this app were not applied until it reconnected. The short-pane rule reads
  the daemon's size, not the phone's, so it stays safe either way. Once the
  grid this client asked for is drawn and the pane is laid out for another
  client, the core refuses Send with `pane_sized_elsewhere` instead of a
  "resizing" that never ends. The notice offers **Fit to this phone**, which
  focuses the pane so Herdr sizes it for the phone; the desktop then shows
  it cropped. In Chat on a pane the desktop isn't on, the action reads **Fit
  here (moves the desktop)**, because focusing moves the desktop too. It runs
  only on that tap, and nothing is resent.
- **Receipt.** Herdr has no delivery acknowledgement for input. The core
  queues a read-only request (`integration.list`, else `worktree.list`) right
  behind the input on the same ordered writer. Its response means the daemon
  read the input. These settle the delivery as *uncertain*, never as an
  error:
  - a rejection or a lost link;
  - input queued when its request cannot be.

  In those cases `composerDeliver` and both Steer steps answer outcome
  `uncertain` with reason `unconfirmed`. Nothing is ever resent
  automatically, and the text stays in the editor until confirmation.
- **Unconfirmed holds the pane.** From the paste until its receipt is
  answered on the same link, the native core refuses any other delivery to
  that (node, session, pane), process-wide. An uncertain delivery keeps the
  pane held across reconnects, tab switches and restarts: the composer
  re-arms the hold from the stored record. Send and Insert stay off in the
  band, the + menu and the larger editor. *Check* only opens Terminal, and
  only *Unlock* releases the pane. Unlock never resends.
- A held pane refuses Send, Insert, Append, Move and composer Paste until
  Unlock. The terminal keyboard and the terminal's own paste are manual and
  are not held.
- **Steer is its own action.** It exists in the native core only; there is no
  UI yet. Send while the agent works stays refused and never becomes a steer.
  A steer is two checked steps (`native/src/steer.rs`).
  - Its paste goes alone, into OMP's empty box, while the same agent instance
    works.
  - Its one Enter follows only after the paste's receipt is answered on the
    same link, while the agent still works, and only when OMP's box shows
    exactly that text.
  - Anything else ends the steer without Enter and leaves the paste in the
    box, so a reconnect or a retry never presses Enter twice. A steer staged
    on a link that was lost has expired: the next Insert, Send or Steer to
    that pane drops it instead of being refused.
  - It was verified once, against OMP: paste and Enter while working joined
    the running turn ("Steering · 1"). Nothing more is assumed.
- **Not done:** queueing, a slash palette and native approvals. They need
  daemon semantics that were not verified. Attachments are described in
  [attachments.md](attachments.md).

## Session screen map

Every action has a visible control; long press is only a shortcut to the
same menu. Nothing floats over the terminal's bottom rows: the composer, the
extra keys and the keyboard sit below it and shrink it.

One window class drives the whole app (`ui/WindowClass.kt`):
- compact below 600 dp wide *or* below 480 dp tall;
- medium from 600 dp;
- expanded from 840 dp.

A sideways phone (915 × 412 dp) is therefore compact. While the keyboard is
up, the class never drops to a narrower one, so typing cannot swap the
layout; it settles when the keyboard closes. The Session layout setting
(Auto, Drawer, Sidebar) still overrides the session's choice.

|Area|Phone and medium|Tablet (expanded)|
|---|---|---|
|Header|← Hosts; the workspace name, with a connection dot, host and tab count below; a "N need you" chip only while an agent is blocked; one Chat \| Terminal segmented control, always in the same place; ⋮|the same; Settings is also on the rail|
|Tab row|the workspace's tabs by their Herdr names, the active one centred and underlined, with ‹ › to step and an n/N button that opens the Tabs sheet; a long press offers Rename and Close tab|the same; n/N is left out while the sidebar is open (the sidebar is the switcher) and reopens it while folded|
|Sidebar|none; the switcher is the Tabs sheet|Herdr's switcher as a 320 dp list pane, folded by its ‹ button to a 56 dp rail. The rail has one 48 dp item per workspace, its number with a status dot, spoken as "name, status", with Herdr's focused workspace marked current. A rail tap focuses that workspace in Herdr. Open or folded is kept across restarts|
|Panes|a pane bar when the tab has more than one pane: a chip per pane|a pane toolbar on every tab: chips plus Split right / Split down / Zoom / Close pane (shared)|
|⋮ menu|New tab, Rename tab, Close tab…; then Split right / Split down / Zoom / Close pane… (shared; phones only); then Settings, Reconnect (only while a live link is lost) and Disconnect…, which asks first. Close pane… is offered only on a split tab, as in the pane toolbar: the only pane closes with its tab, through Close tab…|the same without the pane group|
|Composer|one band: +, a 1–5 line field "Message ‹title›", Send; a larger editor on request|the same, centred|
|Raw terminal|the composer's keyboard button: terminal keyboard plus extra keys, and Back to composer|the same|

A window shorter than 480 dp (a phone held sideways) merges the header and
the tab row into one 48 dp row. It holds ←, the connection dot, the
workspace name (at most 160 dp), the tab row with ‹ › and n/N, the need-you
chip, Chat \| Terminal and ⋮. The host and tab count line is left out there.
Height and width come from the session window, not the device, so split
screen follows the same rules.

While the keyboard is up in such a window, it leaves about 138 dp. The
status bar, the merged row and the composer would fill that, and no content
row would show. So while the keyboard is up there:
- The merged row, the pane bar and the status bar step aside; the 1 dp
  accent line stays.
- The mode stays as chosen, and the composer still names its target
  ("Message ‹title›", the To line, a draft's notice).
- The composer moves beside the content, as a column at the bottom right.
  Stacked, it would take a third of the height left. The column is 36 % of
  the session's width, clamped to 280–420 dp, and only where the content
  keeps 360 dp beside it (`sideComposerWidthDp`); otherwise the composer
  stays below. Chat and Terminal then keep the full height above the
  keyboard, at an unchanged font.

Even so, a Terminal at font scale 1.3 or more shows fewer than 3 output rows
over OMP's own input box and status line. That limit is physical, and it
stays. Closing the keyboard brings the rows back. In the same windows the ⋮
menu lists Settings and Disconnect… first, so both are reachable without
scrolling; other widths keep the order above.

Titles: a tab is named by its Herdr name, which stays put while agents
retitle. A pane is named by its agent's task title, else its label, its
directory, or "Tab N"; never a pane ID or a bare agent kind. One sanitizer
drops OMP's "π >" prompt, icon glyphs and spinner frames
(`ReadableTitle.kt`, `Titles.kt`).

Chat or Terminal is remembered per pane, keyed by the Herdr boot and the
pane's program; that stands in for a terminal ID, which the shell view does
not carry. Chat is the default for a recognised agent, and a stored Terminal
choice wins until the process is replaced. Tapping Chat on a pane it cannot
read explains why (a shell, or another program).

Outage (`Outage.kt`, `OutageStrip.kt`):
- A link lost after the screen was live keeps the last screen and lays a
  thin M3 strip over its top, under the header. The strip shows "Showing what
  was saved at ‹time›" and the link's own report, with Retry and ✕.
- The time is when the loss was seen. A link that wedged before that may have
  been stale earlier.
- The strip comes up only after the loss lasts 1 s, so a blip shows nothing.
  ✕ hides it until the outage ends, and ⋮ → Reconnect stays.
- A retry starts a new connection, whose screen starts empty, so the strip
  then says "Reconnecting…, last live at ‹time›" with a spinner on Retry.
- Recovering from an outage whose strip was shown says "Reconnected" once. A
  blip, a first connect, or a new loss before that say nothing.
- The copy names no cause: the app does not read the device's network state,
  so it cannot tell offline from unreachable and says neither.
- A first connect that fails, and every state that needs a decision (host
  key, sign-in, Herdr missing or incompatible, setup), keep the centred card.
- Chat uses the same strip, gate and copy for a transcript read that fails
  while the link is up. A lost link shows only the session's strip.

Switcher:
- Search requires every word to match a workspace, branch, tab, pane,
  directory, agent or title.
- Filters: All / Needs you (n) / Working (n).
- Recent and Pinned share one chip row.
- Then each workspace is a heading with its tabs in Herdr's order: a one-pane
  tab is one row, and a split tab lists its panes.
- Only the current target is highlighted, and each row has a ⋮ button.

## Navigation

- **Destinations.** Hosts, Agents and Settings. A compact window shows them
  as a bottom bar, medium and expanded ones as a rail (the window class
  above), so a sideways phone keeps the bar. A session is a level pushed
  above Hosts; Back and ← return. Settings opened from a session (⋮ ›
  Settings) returns to it with its pane, mode and draft. A notification opens
  Hosts → Session, so Back lands on Hosts.
- **Settings sections.** Appearance, Terminal, Composer, Layout,
  Notifications & privacy, Security & hosts, About. Keys & certificates and
  Known hosts live in Security & hosts only, and the Hosts screen keeps
  Import SSH config. Opened from a session, the index says where Back
  returns.
- **Why Send is off.** When Herdr's precheck refuses Send for a written
  draft, its reason shows as the composer notice before any tap, with the
  same actions a refusal offers.
- **Agents** lists the agents on every connected host, filtered by status and
  by one host's workspace. Tapping one opens that exact pane.
- **Attention ordering** is a projection: the Needs you filter, the header
  chip and Agents. Herdr's topology rows themselves never reorder.
- **Shared state.** Pane zoom and splits are Herdr's, and every client sees
  them, so each control says "(shared)" and the phone never zooms on its
  own. A short-pane notice offers Zoom (shared) to turn zoom on, never off.
- **Forward scroll stops at the live bottom of an agent's pane**
  (`input::touch`). Swipes reach Herdr as wheel steps. A forward swipe at the
  bottom once recalled an old prompt into OMP's input box. So for a pane that
  runs an agent, forward steps go only as far as Herdr's reported offset back
  to the bottom, and none go when there is no offset.
  - Backward scroll is unchanged.
  - Panes whose program reads the mouse or uses the alternate screen get
    every step, as do shells and an open popup.
- **Swipe between tabs** is not offered: the tab row scrolls sideways, and
  tabs are switched by tapping them or with ‹ ›.
- **Lock-screen words** (`service/NotificationContent.kt`):
  - There is one pure choice per notification (attached card, connection
    lost, agent note, group summary), made from the privacy setting and the
    lock state.
  - While locked, Hidden names no host, agent, task, status or link state, in
    the main content or the publicVersion. Names shows agent, status and
    host, never Herdr's title or body. Full shows everything.
  - Android shows publicVersion only when it redacts by itself, so the locked
    words are also the main content. Every notification is re-posted on the
    lock edges, except the connection-lost note, whose words are the same
    locked or not.
  - A setting change re-posts all of them. Every notification also carries a
    publicVersion with its locked words.
- **Live update of pinned agents** (`service/LiveFields.kt`; see
  [notifications.md](notifications.md)). The foreground notification is the
  one live item; nothing posts a second status card.
  - It shows only agents the user pinned, through a pin sheet with four lock
    levels: Hidden (a neutral glyph; no state, time or count), State only
    (the state glyph and a time Herdline saw begin; no names), Names and
    Full. The same words reach the icon, chip, title, text, style,
    publicVersion and ticker.
  - The live level is stored apart from `Settings` (`live_lock_detail`) and
    written only by Pin, so no default, reset or migration changes it. Until
    the first Pin it follows the notification lock detail.
  - Blocked hands off to one deduplicated attention note per episode. Done
    unpins itself after 10 s, and a lost link demotes at once.
  - Swipe-dismiss is Unpin: the notification is updated in place to the
    unpromoted Attached content, and promotion returns only after a new pin.
- **Screen off counts as locked.** With a lock delay, the keyguard engages on
  an alarm that sends no broadcast. So the words are redacted when the screen
  goes off, and the full words return on screen-on without a keyguard, or on
  unlock.
  - Without a connection service nothing keeps the process alive. So before
    the last connection closes (on Disconnect, and again when the service
    stops), the app holds the notes in the shade at their locked words, and
    they keep them until a connection starts again.
  - A privacy change re-words them at app scope, with or without a service.
  - Identical re-posts are skipped to stay under Android's update rate limit.
    1.5 s after a burst, the posted words are compared and re-posted if
    Android dropped one.
  - Notifications of an earlier process are removed at process start.
  - A user loses unread agent notes on a process death; the lock screen shows
    nothing that Hidden forbids.

## Chat

- **Same agent, read-only.** Chat is a second view of the OMP agent already
  in the pane. It reads that agent's session file and nothing else: no ACP,
  no import or resume, no second OMP. Prompts go through the composer to the
  same pane, under the same Insert/Send rules. Dialogs are answered in the
  terminal, from a card that opens it.
- **Identity from Herdr's CLI.** The client endpoint the app speaks has no
  `pane.get` or process info. So each read runs `herdr --session S pane get P`
  and `pane process-info --pane P` on the host, over an exec channel of the
  live connection's SSH session (no new login per poll). The identity is
  (pane, terminal, OMP PID, `agent_session` path). Any change restarts the
  history from the new file's end. A reconnect is a new connection and starts
  over too.
- **Confinement.** The path is checked on the phone: absolute, `.jsonl`,
  under `/.omp/agent/sessions/`, no `.`/`..`, no control characters. It is
  checked again on the host after `realpath`, so a symlink cannot point chat
  elsewhere.
- **Incremental and bounded.** Chat reads byte ranges by offset: the newest
  256 KiB, then appends from the last complete line (a partial line waits),
  and older pages on request.
  - OMP lines reach 723 KB (tool results, compactions), so a window that cuts
    one line doubles, up to 8 MiB.
  - A shrunken or rewritten file restarts from its end.
  - Entries are keyed by offset, so overlaps never repeat a turn.
  - Text, results and arguments are cut at 64/16/8 KiB.
- **Branches.** The current branch is the parent chain of the newest entry.
  Older loaded entries that the chain has not reached yet stay visible.
- **Scrolling.** The view follows new messages only while at the bottom:
  within 24 dp of the list's end, so a fling that stops just short still
  follows.
  - Only the reader's own drag (and its fling) decides. Anything else that
    moves the end away while following (the keyboard, the composer, a
    rotation, a recreated screen restoring an old position) re-pins it to the
    end instead of unfollowing, and the follow choice survives a recreation.
  - Scrolled up, the view stays put and offers "Latest".
  - "Load older" is the list's first row, never a control over the newest
    messages. The message being read keeps its place on screen when older
    ones go in above.
- **A waiting question.** While the agent is blocked, chat names what waits
  only when the newest tool is the one call without a result; with two or
  more unanswered calls it names nothing. When that call is a complete `ask`
  with one question, the question and its choice labels show as text. Chat
  never answers: its only action is Answer in Terminal, and nothing is sent.
- **Tool runs fold.** Three or more steps in a row (tools and the Thinking
  between them, at least one tool) fold into one 48 dp two-line row. The row
  shows the tool count and names with a failed and a denied badge, then the
  last step.
  - An `ask`, a `task` (delegated agent) and the call a blocked agent waits
    on stand alone and split the run.
  - While a step runs, the run shows its last 6 steps under an "N earlier
    steps" row. A run seen live stays that way once it finishes; one seen
    only finished starts folded.
- **Tool cards.** A collapsed card stays one 36 dp row: a state glyph (each
  with a TalkBack label), the name, the intent, a chip for failed, `exit N` or
  denied, and a chevron.
  - Denied means OMP's own refusal wording: an approval prompt, a deny
    policy, or an extension, preflight rule or hook blocking without a
    reason. An abort, a timeout, an edit rejection or an extension's own
    reason is failed.
  - The exit code is read only from OMP's last line `Command exited with
    code N` on `bash` and `eval`. So it is unknown when the result was cut
    for chat (the core keeps a result's head).
  - Open, the card shows the output's last 10 lines on a container surface,
    with Show all N.
- **Reading width.** Prose (paragraphs, headings, lists, quotes, rules and
  Thinking) is at most 560 dp, about 80 characters of body text a line. It
  starts at the same left edge as code and tools.
  - A user bubble's row is capped at 560 dp too, so bubbles end on the prose
    edge; a bubble is at most 85 % of it.
  - Code blocks, tables and tool cards take the list's full width and still
    scroll sideways.
  - The caps are in dp, so font scaling never shrinks text. Phones in
    portrait are narrower than the cap.
- **The tab's title once.** Each turn's agent label reads "omp". Only the
  first labelled line in the list adds " · ‹tab›", and after older history
  loads, the title moves to the new first turn. Only the label text changes:
  list keys, the Load older anchor and every row stay as they were.
- **Send in chat mode** still reads the terminal under the chat. The pane's
  size is Herdr's layout: in a split tab with the composer open, an OMP pane
  can be shorter than 12 rows, and Send is then refused as in the terminal.
  Zoom (shared) or a single-pane tab gives it room. The rule is unchanged.
- **Chat or Terminal is remembered per pane** (`data/PaneModes.kt`).
  - It is keyed by node, Herdr session and pane, and stored with the daemon
    run (`boot`) and the program it was chosen for.
  - It survives tab switches, Settings, reconnects and relaunches.
  - Chat is the default where Chat can read the agent, and either choice is
    stored.
  - Whenever the view shows another run, a missing pane or a different
    program in the pane (a shell included), the choice is dropped, so a
    replaced process starts in the default again.
  - Limit: OMP replaced by another OMP while the app is not connected looks
    the same to the view, which has no PID. Chat itself then restarts from
    the new session file, as above.
- **Activity order.** Herdr sends no activity time. Each agent carries
  Herdr's `state_change_seq` (`Agent.stateSeq`, from `native/src/view.rs`),
  which orders its last state change against the others. It is not output
  activity and has no clock; it is null when an older core does not send it.
  See [ordering.md](ordering.md).

### Reading here and the desktop

Herdr has one shared focus per session: the pane the desktop and the
Terminal view show. Chat keeps its own target, the pane **read here**
(`ReadingTarget` in `ui/session/ChatTarget.kt`), so reading on the phone
never moves the desktop.

- **Only a choice on the phone sets it:** a tab, a pane, a switcher row, an
  Agents row, a banner, a notification, or a tap on a pane in the terminal.
  A desktop focus change never moves it. Choosing a pane in Chat asks Herdr
  to focus, zoom or resize nothing.
- **Adopted once.** On the first view of a daemon run it takes the pane a
  notification or Agents row opened, else the desktop's pane, read once and
  not followed. A new run drops it, because pane IDs are unique only within
  one run. A new OMP instance in the same pane keeps it; the transcript's own
  identity restarts what Chat shows.
- **Tabs and workspaces.** Choosing a tab reads the pane last read there on
  this phone, else the one Herdr reports focused in it, else its first OMP
  pane, else its first pane. A Terminal choice of a tab or workspace lands
  where Herdr puts the focus, and that pane becomes the one read here.
- **Saying where the desktop is.** When the desktop is on another pane, Chat
  shows "Desktop: ‹tab›" (spoken "Reading ‹pane› here. The desktop is on
  ‹tab›.") and the composer says "Sends into ‹pane›'s terminal".
- **Terminal shows the desktop.** Switching from Chat to Terminal shows
  Herdr's focused pane as it is and, when it isn't the one read here, says
  "Terminal shows the desktop's pane, ‹tab›. You were reading ‹pane›." It
  does not focus the read pane on its own.
- **Send stays a terminal action.** Send still pastes and presses Enter in
  the read pane's terminal; there is no semantic channel. Its checks read
  that terminal, so when the desktop is on another pane they cannot see it:
  Send is refused ("Not sent: Send types into ‹pane›'s terminal, and that
  terminal can't be checked from here right now. Your text is kept."). The
  notice offers Insert without Enter where Insert's own check passes, and
  **Show ‹pane› (moves the desktop)**, the only action that moves the
  desktop. Nothing is resent.
- **A closed pane.** If the read pane closes, Chat says "‹pane› has closed.
  Your draft is kept." and never falls back to the desktop's pane by itself.

## Verified against

These were checked against OMP 18.18.3 in a test Herdr session:
- A two-line paste stays one draft.
- Paste plus Enter submits exactly once.
- The receipt answer arrives 40–60 ms after the input.

Chat was checked on an emulator against a test session:
- history;
- a blocked `ask` with its card;
- the call paired with its answer after answering in the terminal;
- live updates while following;
- one turn per message after reloads;
- loading older history to the start of a 1.4 MB file.

Gboard autocorrect and suggestion replacement were verified on the
emulator's Gboard only. Samsung's keyboard and voice input are not verified.

---

Herdline documentation. Copyright (c) 2026 erikfrish. Licensed under the
Apache License, Version 2.0.
