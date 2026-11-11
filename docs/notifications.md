# Notifications and the live update

How Herdline turns Herdr's own agent notifications into Android ones, and
what the live update of pinned agents does and does not claim. Code: the
`service/` package of the app's Kotlin sources under `app/src/main/java/`.

## Delivery path and its limit

Notifications exist only while Herdline holds an SSH link to the host: the
foreground `ConnectionService` keeps attached connections alive in the
background and forwards Herdr's semantic notifications. There is no push.
Once the link or the app process is gone, nothing arrives until the user
reconnects. Agent notifications are posted only while no Herdline activity is
showing.

## Channels

| Channel ID | Name | Importance | Source |
|---|---|---|---|
| `agent_attention` | Agent needs input | high | Herdr `NeedsAttention` notification |
| `agent_done` | Agent finished and other Herdr messages | default | every other Herdr notification (see gaps) |
| `connection_status` | Connection status | low | an attached link dropped while Herdline was in the background |
| `live_card` | Attached sessions and live card | low | the foreground notification: attachment, or the live update |

Older channel IDs `agents` and `connection` are deleted on start, so the
user's per-channel choices start fresh on the channels above.

There is no error channel, because nothing reports agent errors. Herdr has no
error notification kind (`NeedsAttention`, `Finished`, `UpdateInstalled`,
`Custom`). Request failures (`Connection.Event.Problem`) answer something the
user just did in the app, and the app shows them there.

## One notification per pane

Each agent notification is posted with tag `agent\n<node id>\n<pane id>`.
Its tap intent is `MainActivity.intent(context, nodeId, pane)` with that same
tag as `Intent.setIdentifier`. As a result:

- A notification for `w1:p1` on one host never replaces the one for `w1:p1`
  on another host.
- A newer notification never rewrites the pane an older one opens.

Herdr messages that name no pane share one slot per host. The lost-link
notice (`lost\n<node id>`) and the live update (`live`) have their own
identities. Agent notifications are grouped per host under a silent summary.

A pane's text is its Herdr path, "workspace › tab", plus the pane's title
when that says more than the tab's name. Herdr's own body numbers tabs, so
two agents with the same task read the same in it.

Herdr follows a pane's semantic notification with a plain `Notify` that names
no pane, about a second later. A pane-less notification within 5 s of a pane
notification of the same host is treated as that echo (`HerdrEcho`): dropped
if it comes second, withdrawn if it came first. A pane-less one with no pane
notification near it, such as an agent's own terminal notification from the
pane Herdr has focused, still shows and opens the session.

## Alert policy

- A new Needs input or Done for a pane alerts on its channel.
- The same state again, while its notification still shows, updates it
  silently.
- The same state again after the user swiped the notification away, tapped
  it, or the app called `AgentNotifications.clear`, is not re-posted.
- The same state alerts again only after Herdline saw the pane leave it in
  Herdr's view, for example a new turn started.
- A different state (Needs input, then Done) alerts even while the earlier
  one shows.

## Resolution and dismissal

- When the pane's status in Herdr's view changes away from what a
  notification announced, that notification is withdrawn: the question was
  answered or a new turn began, perhaps on the desktop. Only a change counts,
  so a view that has not yet caught up with the event withdraws nothing.
- `AgentNotifications.clear(context, nodeId, pane)` withdraws the pane's
  notification when the user views that pane in the app.
- A host's group summary goes away with its last agent notification.

## Stale and disconnect

When the link to a host drops, or the user detaches it, the agent
notifications still showing for that host are re-posted silently. Their
subtext then reads "Not connected · as of ‹time›", the last time the link
was known up. They stay because they record something that happened and
open the right pane on tap. They do not claim to be current. When the link
comes back, the marking is removed, and the next view withdraws whatever was
resolved meanwhile.

A pinned agent whose link is lost is shown as Not connected, with no time,
and is not offered for promotion.

## Live update

The live update *is* the foreground notification of `ConnectionService`:
one item, never a second status card (`service/LiveFields.kt` maps state to
fields; `ConnectionService.build` posts them).

- **Pinned only.** The user pins an agent pane with "Pin live update", from
  its ⋮ in the switcher or the session menu. Each pin opens a sheet with the
  four lock levels, a preview of the locked card and a disclosure; nothing is
  saved until Pin. The sheet preselects the live level chosen before, else
  the notification lock detail the user ever saved (Hidden included), else
  State only, marked "Suggested".
- **States.** Shown only while a pinned agent is Working (promoted) or just
  finished. Done is shown once with the run's length, then the pin unpins
  itself after 10 s. Blocked hands off: the live update drops it and one
  attention notification is posted per Blocked episode, on the same slot
  and alert policy as Herdr's own, so the two never alert twice. Idle or
  Unknown shows nothing but keeps the pin. A lost link demotes at once.
- **Glyphs.** The small icon is a mask, so only its shape carries the state:
  `ic_live` (Hidden), `ic_working`, `ic_needs_input`, `ic_done`,
  `ic_disconnected`.
- **Times.** Herdr reports no status start time; Herdline records it when it
  sees the status change. A status first seen on attach, or after the link
  dropped, has no time and none is shown, never "0 min". While Working with
  a known start, `when` is a chronometer; a Done run shows "N min total"
  only when Herdline saw it start and end.
- **Style.** API 37+ uses `MetricStyle` under Full (elapsed, working count,
  waiting count; elapsed critical). Below that, `BigTextStyle` with a line
  per pinned row. There is no progress bar or percentage; the category is
  `progress`.
- **Actions.** Tap opens the top row's pane. Unpin unpins every row shown,
  except on Done. The Attached content keeps Disconnect.
- **Unpin, swipe and the service.** Swipe-dismiss (Android 14+) unpins the
  shown rows like Unpin. The notification is never cancelled while a host is
  attached: it is updated in place to the unpromoted Attached content, and
  promotion returns only after a new pin. A swiped notification is not
  raised again; Android may keep it out of the shade until its next update.

## Promotion reporting

Herdline asks for promotion (`setRequestPromotedOngoing`) only while a pinned
agent is Working or just finished. The OS, the OEM and the user decide
whether it is shown as a Live Update or on the Now Bar. Settings ›
Notifications › "Live Update status" reports, re-read whenever Settings
resumes:

| State | Meaning |
|---|---|
| off | live updates setting off |
| no card | no pinned agent is working or just finished |
| stale | link down; promotion not requested |
| ordinary, unsupported | Android < 16 |
| ordinary, not allowed | `NotificationManager.canPostPromotedNotifications()` is false |
| ordinary, not qualifying | the posted card fails `Notification.hasPromotableCharacteristics()` |
| requested | allowed and qualifying; the system has not set `FLAG_PROMOTED_ONGOING` |
| promoted | the posted card carries `FLAG_PROMOTED_ONGOING` |

"Open Android settings" opens the app's promotion page on Android 16
(`ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`) and the app's notification
settings before that.

## Lock-screen privacy

Default: Hidden. Agent, done, connection and Attached notifications use the
notification lock detail (Hidden, Names, Full). The live update uses its
own level, chosen in the pin sheet and stored apart (`live_lock_detail`);
until a first Pin it follows the notification lock detail unchanged. No
default, reset or migration writes it, and pinning never changes the
notification lock detail.

| Mode | Agent notification on a locked screen | Live update on a locked screen |
|---|---|---|
| Hidden | "An agent needs you" / "An agent finished"; summary "Agent notifications" | `ic_live`, the app name, "Unlock for details": no state, time, count or name |
| State only | live update only | the state glyph, "‹app name› · ‹state›", "‹state› · N min" when the start was seen; no names or counts |
| Names | agent name and state, host | agent names and states; no times or counts |
| Full | everything, including Herdr's title and message | place, states, times and counts; never task titles, prompts or commands |

The same words go to every surface: icon, chip, title, text, style, the
public version and the ticker TalkBack reads. Task titles appear only on an
unlocked screen.

Herdr's notification title and body, which may quote the task, prompt or
answer, appear only in Full.

Android uses a notification's public version only when it redacts. With
Android set to show all notification content, the public version would
never be used, so while the device is locked Herdline posts the content the
mode permits as the notification itself. The full content is re-posted
silently on unlock, and redacted on the lock edge for notifications already
showing. A new notification while locked still alerts.

## Known gaps

- The native layer maps every Herdr notification kind other than
  `NeedsAttention` (`Finished`, `UpdateInstalled`, `Custom`, plain `Notify`)
  to "not attention". All of them reach `agent_done`, labelled to say so.
  Splitting them needs the kind in the native event (`native/src/session.rs`).
- Elapsed time exists only for status changes Herdline saw while attached.
- Whether `FLAG_PROMOTED_ONGOING` matches what Samsung One UI shows on the
  Now Bar is unverified. The four lock levels have not been checked on a
  device for this release.
- The promotion state in Settings is read when Settings resumes, not live.

---

Herdline documentation. Copyright (c) 2026 erikfrish. Licensed under the
Apache License, Version 2.0.
