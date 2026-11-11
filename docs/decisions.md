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
- **One grid owner.** Only the terminal view with Terminal on screen tells
  Herdr its grid (`TerminalView.sharesGrid`). Under Chat the view still
  measures and draws, but after its first grid nothing it fits reaches
  Herdr: a pane bar coming or going, a local read of a split tab or the
  keyboard never resizes the shared panes. The grid it keeps, and that its
  viewport carries, is the one Herdr was told, so `pane_sized_elsewhere` and
  the Send checks compare with that. A switch to Terminal fits the layout it
  brings and reports that grid once, even unchanged. Fit to this phone stays
  the only shared opt-in.
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
|Terminal without composer|Settings › Composer › Show message composer in Terminal, on by default. Off, Terminal shows the terminal and its extra keys under it, without Back to composer; a tap on the terminal brings its keyboard. Chat always has the composer. A delivery waiting on its receipt or steer, or left unconfirmed, keeps the composer, with a notice, until it settles or Unlock. Hidden, it takes no bottom or side slot, and its measured height, open space and held grid go, so the terminal gets the whole column. Drafts and files stay in the draft store; an upload under way is cancelled, as on leaving its pane, and its file reads back as not uploaded|the same|

The hidden Terminal composer keeps an unconfirmed delivery and its safety
notice visible. After Unlock, its warning remains visible until Dismiss or
an edit; then the OFF preference takes effect without a tab or host update.
The stored text and Unlocked record remain intact. Held badges observe the
draft store's unconfirmed keys, not a cached host snapshot.

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
- **Appearance selection.** Launcher variants are picture choices drawn from
  their actual adaptive icon resources; outline, check and radio semantics show
  selection. Wood keeps the Default alias; Ember, Signal, Pasture, Meadow and
  Dusk reuse the prepared crook geometry and colours. Mono and Contrast keep
  their stored names. Material You is first on supported devices, followed by
  the fixed Herdline Material 3 family, with explicit Material badges. Other
  palettes and saved ids remain unchanged.
- **Visual refinement from device captures.** The pinned Settings preview has
  a separator from scrolling controls; icon artwork uses an outline of its actual
  adaptive mask. Theme cards share growable height, not a fixed text-clipping
  height. Host counts use short separate chips; Agents keep status words outside
  truncated location text and status filters on one scrollable row. The session
  attention control keeps a compact count and a full spoken label.
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
    hides after 10 s but keeps the pin for the agent's next task; a lost link
    demotes at once.
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

### Images in Chat

User, assistant and tool-result images are read through the existing
`Native.transcript` call (no new JNI export). A request carrying `media`
(version 1) answers one image; pages carry only descriptors, never bytes.

- **Descriptors.** An image block is `{type:"image", part, source}` with
  `source` `{kind:"inline", bytes}` (decoded size from the base64 length),
  `{kind:"blob", hash}` for OMP's `blob:sha256:<64 lowercase hex>`, or
  `{kind:"unreadable"}` for anything else. A tool result's `images` is an
  array of the same. Entries carry `end`, so an image is named by its entry
  id, its line `[offset, end)` and its content index.
- **The entry is the authority, not the hash.** A media read re-reads exactly
  that line of the session file, confined as pages are, and serves an image
  only if the line is still that whole entry (same start, length, id) and
  the part is still an image. A blob is read only when that part's data is
  exactly `blob:sha256:<hash>` (the host checks before reading the store;
  the core checks again exactly), and its bytes must hash to that name, else
  `corrupt`. A hash the phone sends is never enough to read a blob.
- **One instance, one exec.** The pane, the line, the blob and the pane again
  are read in one exec. Both reports must name the instance the request was
  made for (pane, terminal, OMP pid, session file), else `instance_changed`.
- **The blob store is the OMP process's own.** It is resolved from that
  process's `/proc/<pid>/cmdline` and `environ` as OMP 18.18.8 does: the
  last `--profile` before `--`, else `OMP_PROFILE` when set, else
  `PI_PROFILE`; `~/.omp/profiles/<name>/agent`, or without a profile
  `PI_CODING_AGENT_DIR`, else `~/.omp/agent`; `$XDG_DATA_HOME/omp` (with a
  profile `…/profiles/<name>`) takes its place when it exists. What cannot be
  pinned down is refused as `unknown_store`, never guessed: no `/proc` (e.g.
  macOS hosts), another `HOME`, `PI_CONFIG_DIR`, relative paths, unusual
  profile names. The store must resolve inside `$HOME`, and a blob that
  links out of it is refused (`unconfined`). XDG existence is checked at read
  time, not when OMP started.
- **Bounds.** At most 20 MiB of decoded image; a larger blob or inline image
  is `too_large`. Pages grow up to 32 MiB, enough for one line holding a
  20 MiB inline image, so such a line never wedges the reader; a longer line
  is an explicit error. Reads get 20 s plus 1 s per 512 KiB.
- **The MIME comes from the bytes.** PNG, JPEG, GIF and WebP by their magic
  bytes; anything else, or invalid base64, is `unsupported`.
- **Refusals.** `instance_changed`, `moved` (the line no longer names that
  image), `missing`, `unconfined`, `unknown_store`, `corrupt`, `too_large`,
  `unsupported`, `version`. Link failures stay errors, not refusals.
- **Steering labels.** A user message OMP saved with `steering: true` reads
  "Steering message"; with `liveSteered: true`, "Steered into active
  response". Neither means it is still queued: the queue is the ingress
  panel's, below.

### Chat Send through the OMP endpoint

Chat's Send goes into OMP through the `herdline-ingress` extension
(`host/omp/herdline-ingress.ts`, a repository asset: installed only on the
user's own decision), using only OMP's public extension API (Slices O and
O2 of the herdline-omp `erikfrish/herdline-ingress` branch).

- **Route.** With a Live endpoint for exactly the draft's instance, Send and
  steer go through it and the PTY box checks do not gate them; `auto`
  steers a working agent and starts an idle one's turn. Insert stays on PTY.
- **Fallback.** Only `{"endpoint":"absent"}` (this instance publishes no
  endpoint, known before anything was sent) lets Send type into the pane.
  `unsupported` (extension installed, OMP lacks hooks; the sidecar lists
  them), `unusable` (discovery failed, stale, unreachable, permissions) and
  every refusal show an explicit error; nothing is typed. Once a request may
  have reached the socket, doubt is `uncertain`, and nothing is resent.
- **Discovery.** Over the link's exec: the pane report gives the OMP pid;
  `$XDG_RUNTIME_DIR/herdline-ingress/<sessionId>-<pid>.json` from that
  process's environment, the directory 0700 and the sidecar 0600, both this
  user's and no links, and its `sessionFile` exactly the instance's. The
  exchange re-checks the sidecar bytes and the 0600 socket, then talks
  through `socat` (20 s idle) without half-closing before the answer.
- **Wire.** Newline-delimited compact JSON:
  ```
  send     {v:1, id, instance:{sessionId, sessionFile, pid, nonce}, text, attachments?:[{path, mimeType}], deliverAs}
        -> {id, outcome: accepted|admitted|replayed|refused|uncertain, reason?, state:{idle, queued:[{requestId|null, text, queue}]}}
  status   {v:1, id, op:"status"}        -> stored outcome or uncertain; never sends
  withdraw {v:1, id, op:"withdraw"}      -> {id, withdrawn, state}
  dialogs  {v:1, id, op:"dialogs"}       -> {id, dialogs:[OpenDialog…]}
  dialog_submit {v:1, id, op:"dialog_submit", dialogId, answer} -> {id, dialogId, result}
  push     {queue:[…], liveSteered}  |  {dialogs:[…]}
  ```
  A send is refused in this order: `instance_changed`, then a known id is
  answered from its record, `dialog_open`, `collab` (`ctx.isCollabSession()`
  true) or `collab_state_unknown` (it throws or this OMP lacks it),
  `unsupported_type` (not PNG/JPEG/GIF/WebP, or not a regular file inside
  Herdline's staging root by real path), `too_large` (over 20 MiB),
  `empty`, and `dropped` when OMP drops it. Collab is asked per request,
  never published as `unsupported`.
- **Outcomes.** `accepted`: OMP's queue showed the request id; `admitted`:
  an idle send's user message reached `message_end` with it; `replayed`: a
  repeated id with a durable accepted/admitted record. A pending record or
  another digest under the same id is `uncertain`; a stored refusal
  repeats with its reason.
- **Persistence fence.** Every record is a `herdline-ingress` session
  entry: `appendEntry`, then `await sessionManager.ensureOnDisk()`, then
  `await sessionManager.flush()` (O2; an OMP without it gets `flush` in the
  sidecar's `unsupported`). The pending record is fenced before
  `sendUserMessage`, and a final accepted/admitted before its answer. A
  settled outcome counts in memory only once fenced (pending, which reads
  as `uncertain`, is held at once), so a failed final write stays pending
  and `status` says `uncertain`. This survives OMP crashing, not a
  power loss: nothing is fsynced, and no such durability is claimed. A
  restart rebuilds the records from the session.
- **One send per id.** A request id is reserved before the first await; a
  concurrent duplicate is `uncertain` (in progress), a later one is
  answered from its record. After the attachment reads and the pending
  fence, the exact session, path and nonce, `dialog_open` and collab are
  checked again right before `sendUserMessage`; a late refusal sends
  nothing.
- **Dialogs.** The snapshot and the submit pass OMP's O2 types through
  verbatim; a submit answers only its exact `dialogId` (a form's answer is
  `{data: …}`), never a PTY Enter, never a default or auto-approval. A
  submit with no result for that dialog is a failure, never `submitted`.
  Send still refuses `dialog_open` while any dialog is open. The forms UI is
  `core/ChatDialog.kt` and `ui/chat/ChatForms.kt`.
- **Native ↔ Kotlin.** `Native.ingress(session, json)`; the request is
  `{op: send|status|state|withdraw|dialogs|dialog_submit, node, session,
  boot, pane, agent, instance:{pane_id, terminal_id, pid, path}, id, text?,
  attachments?, deliverAs?: auto|steer, dialogId?, answer?}`. Replies:
  `{"endpoint":"absent"}`, `{"endpoint":"unsupported","missing":[…]}`,
  `{"endpoint":"unusable","reason"}`, `{"endpoint":"live", "id", "outcome",
  "reason"?, "state"?}` (send, status), `{"endpoint":"live","state"}`,
  `{"endpoint":"live","id","withdrawn"}`, `{"endpoint":"live","dialogs"}`,
  `{"endpoint":"live","dialogId","result"}`, and the core's own
  `{"outcome":"refused","reason"}` before anything left (`unsettled` while a
  PTY delivery holds the pane, attachment reasons). A send whose call fails
  after it may have left is uncertain; the other ops fail.
- **Pending queue.** Chat polls the endpoint's state at the instance poll
  interval, also while the queue is empty, and shows the waiting entries
  with Withdraw; a desktop-queued entry (no request id) reads "From the
  desktop". It never infers a pending entry from history or the terminal.
- **Recovery.** An endpoint send writes ahead a Queued record with receipt
  `ingress:<id>`; reloaded, it is Uncertain, and Check asks `status`.

### Questions and submitted answers

Chat reads open questions from the same OMP instance's ingress endpoint.
The host parses HTML once; Android renders its normalized sections, context,
radio/checkbox/select controls and text fields, preserving defaults and
hidden values. Only an explicit Submit sends `{data}` to the exact dialog
ID. OMP validates the answer and races it against the local host: first
answer wins. This is not a prompt, approval by default or terminal Enter.

A fieldset legend is the visible heading for an otherwise unlabelled choice
group; Android does not repeat the technical HTML field name underneath it.
Explicit field labels and names of ungrouped controls stay visible, and required
groups retain their required indicator and accessible group name.


An outer ask and its nested host dialog share one question. Android hides a
child while its `parentId` is open and submits only the visible outer dialog.
`submittable: false` disables answers and removes Submit; waits and unsupported
host surfaces are never turned into approvals or prompt sends.

A failed submit is unconfirmed and is not retried automatically. The user's
answers remain visible, with actions disabled until the dialog is resolved.
An instance or dialog change never inherits an earlier answer. Password
fields are masked and excluded from Android saved state; display-only
persisted answers are masked by OMP before export.

An answered ask shows its questions, every recorded option and the selected
values. A timed-out default is labelled as such, never as the user's choice.
A selected answer is not repeated below the options when its text is exactly
the selected option labels. Distinct summaries and Other text remain, and no
unselected variant is dropped. Ask tool heads use intent or the recorded question
instead of raw JSON/HTML arguments. Required controls use a visible asterisk with
spoken required semantics; the validation summary names the controls to check.
Old records without structured details retain their original output.
Inline bitmap form pictures are sampled and bounded. External pictures and
SVG receive an explicit media notice; no arbitrary file or network fetch
and no script-enabled HTML surface is opened by a form.

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
