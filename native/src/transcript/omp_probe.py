"""Collect what binds a hookless OMP in a herdr-core terminal to one
session, for native to judge (`omp_probe.rs`). Read only: nothing is
started, signalled, written or typed, and no other process is executed.

argv: pane, address, and optionally the session file the caller reads next.
stdin: the terminal record a live `herdr-core get` just returned.
Prints one JSON object: the record's relevant fields, this shell's HOME,
and the facts of the record's foreground process from /proc (its parent,
start time, uid, argv, profile environment, fd0 tty, the shell's fd0 tty,
the OMP leases it holds in either known form, OMP's breadcrumb for that
tty and the header of the file it names).

Exit 0 after printing. With a session file, exit 14 after printing when
the breadcrumb does not name exactly that file, so the command stops
before it reads any byte of it. Exit 1 on an unreadable record.
"""

import json
import os
import sys

PROFILE_KEYS = ('HOME', 'OMP_PROFILE', 'PI_PROFILE', 'PI_CODING_AGENT_DIR', 'PI_CONFIG_DIR',
                'XDG_STATE_HOME', 'XDG_DATA_HOME')
# OMP 18.x names its lease an abstract unix socket with this prefix.
SOCKET_PREFIX = '@omp-file-lock-'
EXIT_OTHER_FILE = 14


def read(path, limit):
    with open(path, 'rb') as handle:
        return handle.read(limit)


def text(raw):
    return raw.decode('utf-8', 'replace')


def mapping(value):
    return value if isinstance(value, dict) else {}


def header(path, home):
    """The session header's id and cwd of [path], past OMP's title slot,
    read only inside OMP's default session folder."""
    root = os.path.join(home, '.omp', 'agent', 'sessions') + '/'
    if not path.startswith(root) or not path.endswith('.jsonl'):
        return None
    try:
        prefix = text(read(path, 64 * 1024))
    except OSError:
        return None
    for line in prefix.split('\n')[:2]:
        try:
            entry = json.loads(line)
        except ValueError:
            return None
        if not isinstance(entry, dict):
            return None
        if entry.get('type') == 'title':
            continue
        if entry.get('type') == 'session':
            return {'id': entry.get('id'), 'cwd': entry.get('cwd')}
        return None
    return None


def leases(base, home):
    """The OMP leases [base]'s process holds: 19.x WRITE locks on fds naming
    $HOME/.omp/run/session-owners/<id>.lock, 18.x abstract socket names, and
    what could not be read to tell."""
    owners = os.path.join(home, '.omp', 'run', 'session-owners') + '/'
    inodes = set()
    locks = set()
    names = set()
    unreadable = []
    for fd in os.listdir(base + '/fd'):
        try:
            target = os.readlink(base + '/fd/' + fd)
        except OSError:
            continue
        if target.startswith('socket:[') and target.endswith(']'):
            inodes.add(target[8:-1])
        elif home.startswith('/') and target.startswith(owners) and target.endswith('.lock'):
            try:
                info = text(read(base + '/fdinfo/' + fd, 64 * 1024))
            except OSError:
                # A descriptor closed meanwhile holds nothing; any other is unknown.
                if os.path.lexists(base + '/fd/' + fd):
                    unreadable.append('fdinfo')
                continue
            if any(line.startswith('lock:') and ' WRITE ' in line for line in info.splitlines()):
                locks.add(target[len(owners):-len('.lock')])
    try:
        table = text(read(base + '/net/unix', 32 << 20)).splitlines()[1:]
    except OSError:
        table = []
        unreadable.append('net/unix')
    for line in table:
        parts = line.split()
        if len(parts) >= 8 and parts[6] in inodes and parts[7].startswith(SOCKET_PREFIX):
            names.add(parts[7][len(SOCKET_PREFIX):])
    return sorted(locks), sorted(names), unreadable


def probe(pid, program):
    base = '/proc/%d' % pid
    try:
        stat = text(read(base + '/stat', 4096))
        fields = stat[stat.rindex(')') + 2:].split()
        own = os.stat(base).st_uid == os.getuid()
    except (OSError, ValueError):
        return {'gone': True}
    out = {'pid': pid, 'ppid': int(fields[1]), 'start': int(fields[19]), 'own': own}
    try:
        argv = read(base + '/cmdline', 64 * 1024).split(b'\0')[:-1]
        environ = read(base + '/environ', 1 << 20).split(b'\0')
    except OSError:
        return out
    out['argv'] = [text(arg) for arg in argv[:64]]
    env = {}
    for item in environ:
        key, sep, value = item.partition(b'=')
        if sep and text(key) in PROFILE_KEYS:
            env[text(key)] = text(value)
    out['env'] = env
    for key, path in (('tty', base + '/fd/0'), ('shell_tty', '/proc/%d/fd/0' % program)):
        try:
            out[key] = os.readlink(path)
        except OSError:
            out[key] = None
    home = env.get('HOME', '')
    if not home.startswith('/'):
        return out
    out['home_real'] = os.path.realpath(home)
    try:
        out['locks'], out['names'], out['unreadable'] = leases(base, home)
    except OSError:
        out['unreadable'] = ['fd']
    tty = out['tty'] or ''
    if not tty.startswith('/dev/'):
        return out
    crumb = os.path.join(home, '.omp', 'agent', 'terminal-sessions', tty[5:].replace('/', '-'))
    try:
        out['crumb'] = text(read(crumb, 64 * 1024))
    except OSError:
        return out
    lines = out['crumb'].split('\n')
    if len(lines) > 1 and lines[1].startswith('/'):
        out['file_real'] = os.path.realpath(lines[1])
        out['header'] = header(out['file_real'], out['home_real'])
    return out


def main():
    if len(sys.argv) not in (3, 4):
        return 1
    address = sys.argv[2]
    wanted = sys.argv[3] if len(sys.argv) == 4 else None
    try:
        record = json.load(sys.stdin)
    except ValueError:
        return 1
    if not isinstance(record, dict):
        return 1
    foreground = mapping(record.get('foreground'))
    facts = {
        'record': {
            'address': record.get('address'),
            'state': record.get('state'),
            'pid': record.get('pid'),
            'foreground': {'pid': foreground.get('pid'), 'name': foreground.get('name')},
            'labels': sorted(key for key in mapping(record.get('labels')) if isinstance(key, str)),
            'status': mapping(record.get('status')).get('state'),
        },
        'home': os.path.realpath(os.environ.get('HOME', '')),
        'proc': None,
    }
    pid, program = foreground.get('pid'), record.get('pid')
    if (record.get('address') == address and type(pid) is int and type(program) is int
            and pid > 0 and program > 0 and pid != program):
        facts['proc'] = probe(pid, program)
    sys.stdout.write(json.dumps(facts))
    if wanted is not None:
        crumb = mapping(facts['proc']).get('crumb')
        lines = crumb.split('\n') if isinstance(crumb, str) else []
        if len(lines) < 2 or lines[1] != wanted:
            return EXIT_OTHER_FILE
    return 0


sys.exit(main())
