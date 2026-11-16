"""Read one terminal from an EXISTING core. Never executes a core binary."""
import json
import os
import socket
import sys
from pathlib import Path


def endpoint(session):
    explicit = os.environ.get("HC_SOCKET")
    if explicit:
        return explicit
    home = Path.home()
    config = Path(os.environ.get("XDG_CONFIG_HOME") or home / ".config") / "herdr-core/config.toml"
    if config.exists():
        import tomllib
        configured = tomllib.loads(config.read_text()).get("socket")
        if configured:
            return configured
    if os.name == "nt":
        user = "".join(c for c in os.environ.get("USERNAME", "") if c.isascii() and c.isalnum())
        return "\\\\.\\pipe\\herdr-core-" + user + "-" + session + ".sock"
    runtime = os.environ.get("XDG_RUNTIME_DIR")
    if not runtime and Path("/run/user/" + str(os.getuid())).is_dir():
        runtime = "/run/user/" + str(os.getuid())
    if runtime:
        return str(Path(runtime) / "herdr-core" / (session + ".sock"))
    state = Path(os.environ.get("XDG_STATE_HOME") or home / ".local/state")
    return str(state / "herdr-core" / session / (session + ".sock"))


def main():
    session, terminal = sys.argv[1:]
    address = endpoint(session.strip() or "default")
    if os.name == "nt":
        # Opening an existing named pipe does not launch its server.
        stream = open(address, "r+b", buffering=0)
        close = stream.close
    else:
        connection = socket.socket(socket.AF_UNIX)
        connection.settimeout(10)
        connection.connect(address)
        stream = connection.makefile("rwb")
        close = connection.close
    try:
        request = {"jsonrpc": "2.0", "id": 1, "method": "terminal.get", "params": {"terminal": terminal}}
        stream.write((json.dumps(request) + "\n").encode())
        stream.flush()
        line = stream.readline(1024 * 1024 + 1)
        if len(line) > 1024 * 1024:
            raise ValueError("core reply too large")
        reply = json.loads(line)
        if reply.get("id") != 1 or "error" in reply or not isinstance(reply.get("result"), dict):
            raise ValueError("core refused terminal")
        print(json.dumps(reply["result"]))
    finally:
        stream.close()
        close()


try:
    main()
except (OSError, ValueError, KeyError, ImportError):
    sys.exit(3)
