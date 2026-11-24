# Fixed installer, executed as the connected SSH account. No agent is launched.
# Stages the bundled OMP Chat runtime (identity hook + ingress + its subagents
# listing) into ~/.herdline-chat/<content hash>/ and reports the managed
# launch specification as one JSON line on stdout. Nothing outside that one
# private folder is written; no OMP configuration is read or changed.
import base64
import hashlib
import json
import os
import secrets
import stat
import sys

FILES = ('herdline-core.ts', 'herdline-ingress.ts', 'live-subagents.ts')
if len(sys.argv) != 4:
    sys.exit('The installer takes the three bundled runtime files.')
payloads = [base64.b64decode(argument, validate=True) for argument in sys.argv[1:]]
digest = hashlib.sha256()
for payload in payloads:
    digest.update(len(payload).to_bytes(8, 'big'))
    digest.update(payload)
name = digest.hexdigest()
home = os.path.realpath(os.environ['HOME'])
if any(ord(c) < 32 or ord(c) == 127 for c in home):
    sys.exit('The SSH home path contains unsupported control characters.')
folder = '.herdline-chat'
flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
with_home = os.open(home, flags)
try:
    os.mkdir(folder, 0o700, dir_fd=with_home)
except FileExistsError:
    pass
root = os.open(folder, flags, dir_fd=with_home)
info = os.fstat(root)
if info.st_uid != os.geteuid() or info.st_mode & 0o077:
    sys.exit('Herdline runtime folder must be private and owned by the SSH account.')

try:
    os.mkdir(name, 0o700, dir_fd=root)
except FileExistsError:
    pass
staged = os.open(name, flags, dir_fd=root)
info = os.fstat(staged)
if info.st_uid != os.geteuid() or info.st_mode & 0o077:
    sys.exit('The staged runtime folder must be private and owned by the SSH account.')

for file_name, payload in zip(FILES, payloads):
    try:
        tmp = '.install-' + secrets.token_hex(12)
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600, dir_fd=staged)
        try:
            with os.fdopen(fd, 'wb') as output:
                output.write(payload)
            os.link(tmp, file_name, src_dir_fd=staged, dst_dir_fd=staged, follow_symlinks=False)
        finally:
            os.unlink(tmp, dir_fd=staged)
    except FileExistsError:
        pass
    with os.fdopen(os.open(file_name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=staged), 'rb') as installed:
        info = os.fstat(installed.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022
                or installed.read(len(payload) + 1) != payload):
            sys.exit('Existing runtime files do not match the bundled version; they were not overwritten.')

rooted = os.path.join(home, folder, name)

# The canonical `omp`, searched as the app's launcher probe searches it. Only
# located here: it is neither installed nor replaced.
command = None
for folder_name in (os.environ.get('PATH') or '').split(':') + [os.path.join(home, '.bun', 'bin'), os.path.join(home, '.local', 'bin')]:
    if not folder_name.startswith('/'):
        continue
    candidate = os.path.join(folder_name, 'omp')
    try:
        info = os.stat(candidate)
    except OSError:
        continue
    if stat.S_ISREG(info.st_mode) and os.access(candidate, os.X_OK):
        command = candidate
        break
if command is None:
    sys.exit('No omp executable was found on this SSH account\'s PATH.')

# What the ingress extension publishes under and what the app's own
# discovery must then find: the exec shell's runtime directory, forwarded
# into the managed launch. Only forwarded when it is absolute and simple.
runtime = os.environ.get('XDG_RUNTIME_DIR') or '/run/user/%d' % os.getuid()
allowed = set('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._/-')
if (not runtime.startswith('/') or '//' in runtime or '/./' in runtime or '/../' in runtime
        or any(c not in allowed for c in runtime)):
    runtime = ''

print(json.dumps({'exe': command, 'entry': os.path.join(rooted, 'herdline-core.ts'),
                  'ingress': os.path.join(rooted, 'herdline-ingress.ts'), 'runtime': runtime}))
