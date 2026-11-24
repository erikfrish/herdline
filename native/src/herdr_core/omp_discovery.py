# Fixed installer for the Chat integration in OMP's own extension discovery,
# executed as the connected SSH account only when the user asks for it. No
# agent is launched and a running OMP is not changed: OMP loads extension
# modules when it starts, so the integration takes effect at its next start
# (or `/exit`, then `omp --resume <id>` in the same pane).
#
#   omp_discovery.py status    <core b64> <ingress b64> <subagents b64>
#   omp_discovery.py install   <core b64> <ingress b64> <subagents b64>
#   omp_discovery.py uninstall
#
# Run as a file from a checkout with no payloads, `status` and `install`
# read the three files from host/omp/ instead.
#
# One private folder is written, ~/.omp/agent/extensions/herdline-chat/
# (0700, files 0600): the three runtime files, a package.json that names the
# two entry points (OMP's documented `omp.extensions` manifest) and an
# ownership record with each file's digest. No OMP config file is read or
# changed. A folder of that name that this installer did not write, or whose
# files were changed since, is never replaced or removed.
#
# Prints one JSON line, {"state": "absent"|"installed"|"outdated"|"error",
# "path": ..., "files": [...], "message": null or the exact error}, and
# exits 0 unless that line could not be printed.
import base64
import hashlib
import json
import os
import secrets
import shutil
import stat
import sys

FILES = ('herdline-core.ts', 'herdline-ingress.ts', 'live-subagents.ts')
NAME = 'herdline-chat'
RECORD = '.herdline-install.json'
OWNER = 'herdline'
PACKAGE = (json.dumps({
    'name': NAME,
    'private': True,
    'description': 'Herdline Chat: identity hook and ingress endpoint for this OMP.',
    'omp': {'extensions': ['./herdline-core.ts', './herdline-ingress.ts']},
}, indent=2) + '\n').encode()


class Refused(Exception):
    pass


def payloads(arguments):
    if arguments:
        if len(arguments) != len(FILES):
            raise Refused('The installer takes the three bundled runtime files.')
        return [base64.b64decode(argument, validate=True) for argument in arguments]
    source = os.path.join(os.path.dirname(os.path.realpath(__file__)), '..', '..', '..', 'host', 'omp')
    contents = []
    for file_name in FILES:
        with open(os.path.join(source, file_name), 'rb') as handle:
            contents.append(handle.read())
    return contents


def digest(payload):
    return hashlib.sha256(payload).hexdigest()


def private_dir(path):
    """[path] as a real directory of this account's, made 0700 when missing."""
    try:
        os.mkdir(path, 0o700)
    except FileExistsError:
        pass
    info = os.lstat(path)
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid():
        raise Refused('%s must be a directory owned by the SSH account.' % path)


def agent_dir(home):
    """Refuses unless OMP's default agent folder is there and this account's."""
    for variable in ('PI_CODING_AGENT_DIR', 'PI_CONFIG_DIR'):
        if os.environ.get(variable):
            raise Refused('%s is set; only the default OMP agent folder is supported.' % variable)
    agent = os.path.join(home, '.omp', 'agent')
    try:
        info = os.stat(agent)
    except FileNotFoundError:
        raise Refused('OMP has not been set up for this account: %s is missing.' % agent)
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid():
        raise Refused('%s must be a directory owned by the SSH account.' % agent)


def read_regular(folder, file_name, limit):
    fd = os.open(os.path.join(folder, file_name), os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as handle:
        info = os.fstat(handle.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid():
            raise OSError('not a regular file of this account')
        return handle.read(limit + 1)


def owned(target):
    """The digests this installer recorded in [target] when every file there
    is one it wrote and is unchanged; None otherwise."""
    try:
        info = os.lstat(target)
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid():
            return None
        record = json.loads(read_regular(target, RECORD, 64 * 1024))
        files = record.get('files') if record.get('owner') == OWNER else None
        if not isinstance(files, dict) or set(os.listdir(target)) != set(files) | {RECORD}:
            return None
        for file_name, expected in files.items():
            if '/' in file_name or digest(read_regular(target, file_name, 32 * 1024 * 1024)) != expected:
                return None
        return files
    except (OSError, ValueError, AttributeError):
        return None


FOREIGN = '%s was not written by Herdline or was changed since.'


def retire(staging, target):
    """Move [target] out of OMP's discovery in one rename, then delete it."""
    retired = os.path.join(staging, '.retired-' + secrets.token_hex(8))
    os.rename(target, retired)
    shutil.rmtree(retired)


def write(staging, target, files):
    """[files] into a fresh private folder, renamed into place in one step."""
    fresh = os.path.join(staging, '.discovery-' + secrets.token_hex(8))
    os.mkdir(fresh, 0o700)
    try:
        record = {'owner': OWNER, 'files': {name: digest(data) for name, data in files.items()}}
        for file_name, data in list(files.items()) + [(RECORD, (json.dumps(record) + '\n').encode())]:
            fd = os.open(os.path.join(fresh, file_name), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(fd, 'wb') as output:
                output.write(data)
        if os.path.lexists(target):
            retire(staging, target)
        os.rename(fresh, target)
    except BaseException:
        shutil.rmtree(fresh, ignore_errors=True)
        raise


def run(action, arguments, home, target):
    """The state of the integration at [target] after [action], and the files there."""
    agent_dir(home)
    extensions = os.path.dirname(target)
    # Retired and fresh copies live in the app's own private folder, outside
    # OMP's discovery, on the same file system as the home folder.
    staging = os.path.join(home, '.herdline-chat')
    present = os.path.lexists(target)
    recorded = owned(target) if present else None
    if present and recorded is None:
        raise Refused(FOREIGN % target + {
            'status': '', 'install': ' It was not overwritten.', 'uninstall': ' It was not removed.',
        }[action])
    if action == 'uninstall':
        if arguments:
            raise Refused('uninstall takes no payloads.')
        if present:
            private_dir(staging)
            retire(staging, target)
        return 'absent', []
    files = dict(zip(FILES, payloads(arguments)))
    files['package.json'] = PACKAGE
    want = {name: digest(data) for name, data in files.items()}
    if action == 'install' and recorded != want:
        private_dir(extensions)
        private_dir(staging)
        write(staging, target, files)
        recorded = want
    if recorded is None:
        return 'absent', []
    return 'installed' if recorded == want else 'outdated', sorted(recorded)


def main(argv):
    path = None
    try:
        if not argv or argv[0] not in ('status', 'install', 'uninstall'):
            raise Refused('Usage: omp_discovery.py status|install|uninstall [payloads]')
        home = os.path.realpath(os.environ['HOME'])
        if any(ord(c) < 32 or ord(c) == 127 for c in home):
            raise Refused('The SSH home path contains unsupported control characters.')
        path = os.path.join(home, '.omp', 'agent', 'extensions', NAME)
        state, files = run(argv[0], argv[1:], home, path)
        message = None
    except Refused as error:
        state, files, message = 'error', [], error.args[0]
    except (OSError, ValueError, KeyError) as error:
        state, files, message = 'error', [], '%s: %s' % (type(error).__name__, error)
    print(json.dumps({'state': state, 'path': path, 'files': files, 'message': message}))


main(sys.argv[1:])
