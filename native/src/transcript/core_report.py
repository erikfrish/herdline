"""Report a herdr-core terminal as Herdr's pane and process reports would,
from the record a live `herdr-core get` just returned, and only while that
record still makes exactly the confirmed claim.

argv: pane, address, agent, session file, session id, pid, as native
confirmed them. stdin: the terminal record. Prints the pane report, a
`@@proc` line, then the process report. A record that is not that claim
(another terminal, finished, relabelled, another session or another
foreground process) prints nothing and exits 3, so the command stops
before it reads any file or endpoint; an unreadable record exits 1.
"""

import json
import sys

STATES = ("idle", "working", "blocked", "done")


def mapping(value):
    return value if isinstance(value, dict) else {}


def main():
    if len(sys.argv) != 7:
        return 1
    pane, address, agent, path, session_id, pid = sys.argv[1:]
    try:
        record = json.load(sys.stdin)
    except ValueError:
        return 1
    if not isinstance(record, dict):
        return 1
    labels = mapping(record.get("labels"))
    foreground = mapping(record.get("foreground"))
    state = mapping(record.get("status")).get("state")
    live_pid = foreground.get("pid")
    held = (
        record.get("address") == address
        and record.get("state") == "live"
        and labels.get("herdline.agent") == agent
        and labels.get("herdline.session_file") == path
        and labels.get("herdline.session_id") == session_id
        and labels.get("herdline.pid") == pid
        and type(live_pid) is int
        and str(live_pid) == pid
    )
    if not held:
        return 3
    report = {
        "pane_id": pane,
        "terminal_id": address,
        "agent": agent,
        "agent_status": state if state in STATES else "unknown",
        "agent_session": {
            "agent": agent,
            "kind": "path",
            "value": path,
            "source": "herdline-core:" + agent,
        },
    }
    processes = [{"name": agent, "pid": live_pid}]
    sys.stdout.write(json.dumps({"result": {"pane": report}}))
    sys.stdout.write("\n@@proc\n")
    sys.stdout.write(
        json.dumps({"result": {"process_info": {"foreground_processes": processes}}})
    )
    return 0


sys.exit(main())
