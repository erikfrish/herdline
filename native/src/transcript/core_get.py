"""Read one terminal from an EXISTING core. Never executes a core binary."""
import json
import os
import socket
import struct
import sys
from pathlib import Path
from uuid import UUID


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


def process_start(pid):
    # Do not interpret a core's PID using a procfs or PID namespace other
    # than the one the process belongs to (e.g. a socket mounted from a container).
    if type(pid) is not int or pid <= 0 or os.readlink('/proc/self') != str(os.getpid()):
        raise ValueError('process outside procfs')
    own_ns = os.stat('/proc/self/ns/pid').st_ino
    if os.stat(f'/proc/{pid}/ns/pid').st_ino != own_ns:
        raise ValueError('process outside PID namespace')
    with open(f'/proc/{pid}/stat', 'rb') as stat:
        fields = stat.read(4096).rsplit(b')', 1)[1].split()
    start = int(fields[19])  # field 22, after the parenthesized comm
    if fields[0] in (b'Z', b'X', b'x') or start <= 0:
        raise ValueError('process not live')
    return own_ns, start


def core_run(connection):
    pid, uid, _ = struct.unpack('3i', connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
    if uid != os.getuid():
        raise ValueError('core belongs to another user')
    ns, start = process_start(pid)
    boot = UUID(Path('/proc/sys/kernel/random/boot_id').read_text().strip())
    if not boot.int:
        raise ValueError('missing host boot')
    return pid, f'{boot.hex}:{ns}:{start}'


def main():
    session, target, *proof = sys.argv[1:]
    starting = target == '--run'
    address = endpoint(session.strip() or 'default')
    if os.name == "nt":
        # Opening an existing named pipe does not launch its server.
        stream = open(address, "r+b", buffering=0)
        connection = None
        close = stream.close
    else:
        connection = socket.socket(socket.AF_UNIX)
        connection.settimeout(10)
        connection.connect(address)
        stream = connection.makefile("rwb")
        close = connection.close
    try:
        peer = core_run(connection) if starting or proof else None
        if starting:
            method, params = 'server.status', {}
        else:
            method, params = 'terminal.get', {'terminal': target}
        request = {'jsonrpc': '2.0', 'id': 1, 'method': method, 'params': params}
        stream.write((json.dumps(request) + "\n").encode())
        stream.flush()
        line = stream.readline(1024 * 1024 + 1)
        if len(line) > 1024 * 1024:
            raise ValueError("core reply too large")
        reply = json.loads(line)
        if reply.get("id") != 1 or "error" in reply or not isinstance(reply.get("result"), dict):
            raise ValueError("core refused terminal")
        result = reply['result']
        if starting:
            core_id, pid = proof
            if result['core']['id'] != core_id or result['pid'] != int(pid) or peer[0] != int(pid):
                raise ValueError('not the linked core')
            if core_run(connection) != peer:
                raise ValueError('core changed during proof')
            print(peer[1])
        else:
            result.pop('_phone_start', None)
            if proof:
                if peer[1] != proof[0]:
                    raise ValueError('core run changed')
                # Never trust a field a terminal record claims for its own process start.
                result['_phone_start'] = process_start(result['foreground']['pid'])[1]
                if core_run(connection) != peer:
                    raise ValueError('core changed during report')
            print(json.dumps(result))
    finally:
        stream.close()
        close()


try:
    main()
except (OSError, ValueError, LookupError, AttributeError, TypeError, ImportError, struct.error):
    sys.exit(3)
