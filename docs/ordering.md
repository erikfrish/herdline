# Session order

The switcher lists places in one of two orders. The order is chosen with
the **Order** selector (Workspace | Last activity) under the search field of
the Tabs sheet and the tablet sidebar, or in Settings › Layout › Tab order.
Both write the same persisted setting (`target_order` in the `settings`
preferences); the default is Workspace.

| Order | What it shows |
|---|---|
| Workspace (default) | Herdr's own order: each workspace as a section, its tabs by number, and panes under a split tab. |
| Last activity | Every place in one list, most recent agent state change first. |

The old *Agent order* setting (`agent_sort`: Needs you first, Herdr's order)
is gone, and no alias for it remains. Both of its stored values become
Workspace: the key is not read, and it is removed on the next settings save.
"Needs you first" was never last activity: it was a status bucket. That
projection is still available as the Needs you, Working and Ready filters
(`SwitcherFilter`), which work in either order. Ready lists idle and done
agents: what is finished or quiet, not a claim that anything is safe to send.

## What "activity" means

Activity is **the agent's last state change**, as Herdr reports it: for
example idle → working, working → blocked, or blocked → done.

Herdr sends no activity time. Every agent in the shell snapshot instead
carries `state_change_seq` (`herdr-protocol` `ClientShellAgent`), which
Herdline exposes as `Agent.stateSeq: Long?`:

- **It orders, it does not time.** A higher seq changed later than a lower one
  on the same daemon. The gap between two seqs means nothing: there is no
  wall-clock time and no duration. A time is shown only when Herdline saw the
  change itself (see [Observed times](#observed-times)), never from the seq.
- **It is per daemon run.** The counter belongs to one daemon boot
  (`ShellView.boot`). Seqs from two hosts, or from before and after a daemon
  restart, do not compare. `lastActivityOrder` only compares rows that share a
  boot. Rows from different boots stay grouped by boot, in the order the boots
  first appear. The switcher shows one host at a time, so in practice each host
  is ordered on its own. Herdline defines no merge across hosts, because no
  common clock exists to merge on.
- **It can be absent.** `stateSeq` is null when an older core or daemon omits
  it, and a place without an agent (a bare shell tab) has none. These places
  go after every place that has a seq, in Herdr's order, under the section
  label **NO STATE CHANGE YET · HERDR ORDER**.
- **Ties use Herdr's order.** Equal seqs, and the null-seq tail, keep
  Workspace order. The sort is stable, so the same input always gives the same
  list.

The evidence that the seq is one daemon-wide, monotonic counter comes from
behaviour, not from a written guarantee in the protocol crate:

- The live test `daemon_completion_status_live` asserts that the seq strictly
  increases across successive state reports for a pane within one boot.
- Herdr's own agent view fixture sorts by `state_change_seq` descending across
  agents.
- In one live `herdr agent list`, 75 agents had 75 distinct values.

If Herdr ever scopes the counter differently, only `lastActivityOrder` needs
to change.

## What is listed

Last activity lists *places*, the rows the user goes to: a one-pane tab as
its tab row, and each pane of a split tab as a pane row. A place's seq is its
agent's seq. One muted line above the list states both limits: "Ordered by
Herdr's last state change. Times show only changes Herdline saw itself." (a
shorter wording on the tablet sidebar). A flat row's second line reads
`workspace › tab · status`, because no section header says where it lives.
The Herdr-focused place keeps the same single highlight as in Workspace
order. Search, the Needs you / Working / Ready filters, pins and recents
behave exactly as in Workspace. Status filters reveal rows that the hide
toggles would take (marked "hidden"), so a narrowed list is never silently
empty; a plain search finds them the same way.

## The Tabs view

The phone Tabs sheet opens as the list (`TabsView.List`, the default), or as
the same rows in a compact two-column layout (`TabsView.TwoColumns`) chosen
in Settings › Layout › Tabs view. At most two columns; large accessibility
fonts fall back to one, where two would make a title illegible. A stored
"Grid" (`tabs_view`) is the removed tile overview and is migrated to List
once; the value is written back so it never reappears. The old default is
migrated too, never an explicit List choice.

## No jump: the order is held while the switcher is shown

A live update never moves a row the user may be looking at, pressing or
holding a menu on. Herdline takes the order at the moment the switcher shows
it and holds that order, each row with the seq it had then (`heldOrder`):

- **Live seq changes** do not reorder held rows, and a place keeps its held
  section even when its first seq arrives. When updating would move
  anything, a **N changed · Update order** pill appears centred near the
  bottom (`changedSince` counts places with a new seq or that are new), and
  the rows move only when the user taps it.
- **A place that closes** drops out. Rows around it close up, but none swaps
  position.
- **A new place** joins at the end of its section, and the pill offers to
  sort it in.
- **On a phone** the switcher is a sheet that is created on each open, so
  every open shows the fresh order ("apply on next open"). **On a tablet** the
  sidebar is always shown, so the pill is how the fresh order arrives. The
  sidebar does not apply it by itself after an idle delay.
- **A new daemon run**, a different host or a change of order mode starts over
  with the fresh order. A new run's pane IDs and seqs share nothing with the
  old one.

This never changes the current or focused pane: the order is presentation
only, and it sends nothing to Herdr.

## Observed times

A flat row may add "· changed now" or "· changed 12 min ago" only when
Herdline itself saw its agent's current status begin. The switcher keeps a
`StatusClock` (`service/AgentAlerts.kt`) of its own while it is shown and
feeds it every snapshot. A status that was already there when the switcher
appeared has no known start and shows no time; there is never an "output"
time and never one derived from the seq. The connection service's own clock
sees more, but the switcher receives no handle to it; passing one in would
let the sheet show times for changes seen while it was closed.

---

Herdline documentation. Copyright (c) 2026 erikfrish. Licensed under the
Apache License, Version 2.0.
