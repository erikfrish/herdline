"""One Codex app-server call for the live thread a pane runs (agent_chat.rs).

argv: codex binary, op, thread id, request id, deliver_as, text, images json.
Prints exactly one line: a word, a tab, and a JSON payload.

Only the running app-server daemon is spoken to, through
`codex app-server proxy`, which connects to its control socket and never
starts one. The socket is looked for first, so no daemon means nothing runs.
A thread the daemon has not loaded is never acted on: acting could load
(resume) it, which would be a second agent on that session.

Words: nodaemon, cli, notloaded (this route does not reach the pane's
agent); daemon, error, blocked, not_working (nothing was asked of the
thread); accepted, state, unknown, withdrawn (the daemon answered); doubt
(a change was asked and its fate is unread).
"""

import json
import os
import select
import subprocess
import sys
import time

WAIT = 20.0
MUTATIONS = ("turn/start", "turn/steer", "thread/queue/delete")
# Each queued text as the queue panel shows it, so a long queue fits the reply.
SHOWN = 2000

class Broke(Exception):
    """The link or the answer failed; what it means depends on what was sent."""


def out(word, payload=None):
    sys.stdout.write(word + "\t" + json.dumps(payload) + "\n")
    sys.stdout.flush()
    sys.exit(0)


class Daemon:
    def __init__(self, codex):
        self.proc = subprocess.Popen(
            [codex, "app-server", "proxy"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        self.buf = b""
        self.last = 0
        self.mutated = False

    def write(self, message):
        try:
            self.proc.stdin.write((json.dumps(message) + "\n").encode())
            self.proc.stdin.flush()
        except OSError as error:
            raise Broke() from error

    def call(self, method, params):
        self.last += 1
        mine = self.last
        if method in MUTATIONS:
            self.mutated = True
        self.write({"id": mine, "method": method, "params": params})
        end = time.monotonic() + WAIT
        while True:
            while b"\n" in self.buf:
                raw, self.buf = self.buf.split(b"\n", 1)
                try:
                    msg = json.loads(raw)
                except ValueError:
                    continue
                # Notifications and the server's own requests are not ours to answer.
                if isinstance(msg, dict) and msg.get("id") == mine and "method" not in msg:
                    return msg
            left = end - time.monotonic()
            if left <= 0:
                raise Broke()
            ready, _, _ = select.select([self.proc.stdout], [], [], left)
            if ready:
                chunk = os.read(self.proc.stdout.fileno(), 65536)
                if not chunk:
                    raise Broke()
                self.buf += chunk

    def result(self, method, params):
        msg = self.call(method, params)
        if "error" in msg or not isinstance(msg.get("result"), dict):
            raise Broke()
        return msg["result"]


def queued(daemon, thread):
    got = daemon.result("thread/queue/list", {"threadId": thread})
    return [e for e in got.get("data") or [] if isinstance(e, dict)]


def state(daemon, thread, idle):
    entries = []
    for e in queued(daemon, thread):
        words = [p.get("text", "") for p in e.get("input") or [] if isinstance(p, dict) and p.get("type") == "text"]
        entries.append({"requestId": e.get("clientUserMessageId"), "text": "\n".join(words)[:SHOWN], "queue": "followUp"})
    return {"idle": idle, "queued": entries}


def after(daemon, thread, idle):
    """The queue after an admitted change: shown when readable, never a doubt on it."""
    try:
        return state(daemon, thread, idle)
    except Broke:
        return None


def run(daemon, op, thread, rid, deliver, text, images):
    init = daemon.call(
        "initialize",
        {"clientInfo": {"name": "herdline", "version": "1"}, "capabilities": {"experimentalApi": True}},
    )
    if "error" in init:
        out("daemon")
    daemon.write({"method": "initialized"})

    read = daemon.call("thread/read", {"threadId": thread})
    # The pane's own session was just verified; a daemon that cannot read it
    # keeps its threads elsewhere, so it is not the pane's agent either.
    if "error" in read:
        out("notloaded")
    status = ((read.get("result") or {}).get("thread") or {}).get("status") or {}
    kind = status.get("type")
    if kind == "notLoaded":
        out("notloaded")
    if kind not in ("idle", "active"):
        out("error")
    idle = kind == "idle"

    if op == "state":
        out("state", state(daemon, thread, idle))

    if op == "status":
        if any(e.get("clientUserMessageId") == rid for e in queued(daemon, thread)):
            out("accepted", state(daemon, thread, idle))
        turns = daemon.result("thread/turns/list", {"threadId": thread, "limit": 5, "itemsView": "full"})
        for turn in turns.get("data") or []:
            for item in turn.get("items") or []:
                if isinstance(item, dict) and item.get("type") == "userMessage" and item.get("clientId") == rid:
                    out("accepted", state(daemon, thread, idle))
        out("unknown", state(daemon, thread, idle))

    if op == "withdraw":
        entry = next((e for e in queued(daemon, thread) if e.get("clientUserMessageId") == rid), None)
        if entry is None:
            out("withdrawn", {"withdrawn": False, "state": state(daemon, thread, idle)})
        got = daemon.result("thread/queue/delete", {"threadId": thread, "queuedSubmissionId": entry.get("id")})
        out("withdrawn", {"withdrawn": got.get("deleted") is True, "state": after(daemon, thread, idle)})

    if op != "send":
        out("error")
    # An approval or a question is open: answering it is the terminal's, not a send's.
    if status.get("activeFlags"):
        out("blocked")
    parts = [{"type": "text", "text": text, "text_elements": []}] if text else []
    parts += [{"type": "localImage", "path": path} for path in images]
    if idle:
        if deliver == "steer":
            out("not_working")
        got = daemon.result("turn/start", {"threadId": thread, "input": parts, "clientUserMessageId": rid})
        out("accepted" if isinstance(got.get("turn"), dict) else "doubt", after(daemon, thread, False))
    # Auto on a working agent steers it, as OMP's endpoint does: Send never
    # turns into a queued follow-up without saying so.
    turns = daemon.result("thread/turns/list", {"threadId": thread, "limit": 1, "sortDirection": "desc"})
    live = [t for t in turns.get("data") or [] if isinstance(t, dict) and t.get("status") == "inProgress"]
    if not live:
        out("not_working")
    turn = live[0].get("id")
    got = daemon.result(
        "turn/steer",
        {"threadId": thread, "expectedTurnId": turn, "input": parts, "clientUserMessageId": rid},
    )
    out("accepted" if got.get("turnId") == turn else "doubt", after(daemon, thread, False))


def main():
    codex, op, thread, rid, deliver, text, images = sys.argv[1:8]
    home = os.environ.get("CODEX_HOME") or os.path.join(os.path.expanduser("~"), ".codex")
    if not os.path.exists(os.path.join(home, "app-server-control", "app-server-control.sock")):
        out("nodaemon")
    try:
        daemon = Daemon(codex)
    except OSError:
        out("cli")
    try:
        run(daemon, op, thread, rid, deliver, text, json.loads(images))
    except Broke:
        # Past a change asked of the thread, any break may follow its admission.
        out("doubt" if daemon.mutated else "daemon")


main()
