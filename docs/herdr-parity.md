# Herdr parity

What Herdline does with each part of Herdr, and where it differs from Herdr's
own terminal client. "Herdr" means the daemon and its CLI on the host;
Herdline never changes either.

|Herdr|Herdr's terminal client|Herdline|
|---|---|---|
|Sessions|`herdr --session S`|a node names one session|
|Workspaces, tabs, panes|sidebar, tab bar, split layout|switcher (bottom sheet on a phone, sidebar on a tablet), tab strip, pane bar, the same split layout|
|Agent status|status dots, "needs you" list|status dots, attention chip, Needs you / Working filters, Android notifications|
|Pane zoom|shared with every client|the same; every zoom control says "(shared)" and the phone never zooms on its own; a short-pane notice offers Zoom (shared) to turn it on, never off|
|Splitting a pane|keys and mouse|"Split right (shared)" / "Split down (shared)" in the pane bar on wide windows, Split right / down in the session ⋮ menu on a phone; right and down only|
|Creating, renaming, closing|keys and mouse|new tab (+ in the tab strip, a labelled New tab on its own row under the one Tabs header that List, Two columns and Workspace rails share, a New tab tile at the end of each rail, the switcher's workspace ⋮, the session ⋮; with All workspaces and more than one, it asks which), new workspace (switcher), rename tab/workspace (⋮, long press, switcher); closing a pane, tab or workspace asks first, naming the tab or workspace; Close pane is offered only on a split tab (the only pane closes with its tab)|
|Typing into a pane|keyboard|raw terminal keyboard (IME mirrored line) or the composer (Insert / Send)|
|Popups and dialogs|drawn by Herdr|drawn by Herdr in the terminal; chat shows a card that opens the terminal and never answers for you|
|Agent conversation|the agent's own terminal UI|the same terminal, or **Chat** for OMP panes: a read-only view of the same agent's session file|
|Installing or updating Herdr|`herdr update`|only on request, through `herdr update --handoff` or Herdr's installer|

## Gaps

What Herdr's own client does that Herdline does not. The Herdr methods with
no control in the app are named:

- Renaming a pane (`pane.rename`).
- Swapping panes (`pane.swap`), resizing splits (`pane.resize`,
  `layout.set_split_ratio`) and changing a tab's layout. Herdline shows
  Herdr's layout as it is.
- Moving focus by direction (`pane.focus_direction`); panes are focused by
  their chip instead.
- Reordering tabs and workspaces (`tab.move`, `workspace.move`,
  `workspace.move_block`).
- Clearing a pane (`pane.clear`). It is destructive and would need a
  confirmation if ever added.
- Scrollback copy and editing (`pane.copy_motion`, `pane.copy_search`,
  `pane.edit_scrollback`), which are desktop editing.
- Splitting left or up: only right and down are offered.
- Opening Herdr popups or its command palette from the phone; popups that
  are open are drawn in the terminal and answered there.
- Keybindings of Herdr's client: there is no prefix-key mode; every action
  above has a visible control instead.
- Answering dialogs from chat, steering a running turn from the app, and
  diff review (see [decisions.md](decisions.md)).

## Chat mode

Chat is another view of the agent already running in the pane, not a second
client of it.

- **Which agent.** On every read the core asks Herdr on the host, over a side
  channel of the connection's own SSH session, for `pane get <pane>` (the
  pane's terminal and its `agent_session`, kind `path`) and
  `pane process-info --pane <pane>` (the agent's PID). The identity is
  (pane, terminal, PID, session file). When it differs from the one the
  history was read for (session switch, fork, a new process in the pane, a
  replaced pane), chat starts over from the new file's end.
- **Which file.** The path must resolve (`realpath` on the host) to a
  `.jsonl` under `~/.omp/agent/sessions/`; anything else is refused. Only
  that file is read, by byte range, with `tail`/`head`.
- **Nothing else runs.** No OMP process is started, nothing is imported or
  resumed, and ACP is not used. Messages are sent with the shared composer,
  to the same pane, by the same Insert/Send rules.
- **Reading.** The newest 256 KiB first, then appends from the last complete
  line; a line still being written waits for the next read. Older history
  loads from the top of the list, keeping the message being read in place.
  A file shorter than what was read (rewritten or truncated) restarts from
  its end. Entries are identified by their byte offset, so overlapping reads
  never duplicate a turn; at most 2,000 entries stay loaded.
- **Branches.** OMP keeps abandoned branches in the file. Chat shows the
  ancestry of the newest entry.
- **Bounded.** Text is cut at 64 KiB, tool results at 16 KiB, arguments at
  8 KiB, with a note that the terminal has the rest; a single line over
  8 MiB is refused.

Not in chat: answering dialogs, images, steering a running turn, other agents'
transcripts (Claude, Codex, …), which have no verified session path.

---

Herdline documentation. Copyright (c) 2026 erikfrish. Licensed under the
Apache License, Version 2.0.
