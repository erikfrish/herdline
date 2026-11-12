"""Prompt files from Herdline staging only, for OpenCode or Kilo.

OpenCode 2.2.7 (fedc6f20) wire: packages/schema/src/prompt-input.ts:9-18
{uri, source?, name?}; packages/core/src/session/prompt.ts:97-151 materializes
a data URI and :240-260 requires canonical base64; MAX_ATTACHMENT_BYTES is
20 MiB. Kilo v1 takes {type, mime, filename, url}: kilo_ctl.py turns these
same verified data URIs into that shape, under the native limits it names.

Integration: embed this module in opencode_ctl.py's or kilo_ctl.py's host
command and call prepare(staged_records, bound_target). The native caller checks the same
pane/session identity before this and again before sending. The full existing
Attachment records must travel with the request; paths alone do not authorize
media. Returned bytes are verified snapshots, not file URIs the server could
reopen after a symlink/target change. No fetch, auth, process reads or logging.

The transport is bounded to 10 files, each at most 20 MiB decoded (including
non-images), matching the existing picker count and OpenCode's per-file cap.
No aggregate HTTP cap is imposed by the pinned standalone server: fedc6f20
packages/server/src/process.ts:164-166 uses node:http + NodeHttpServer.make.
In the pinned 4.1.0 dependencies, effect/src/http/HttpIncomingMessage.ts:143-146
defaults MaxBodySize to undefined; @effect/platform-node/src/
NodeHttpIncomingMessage.ts:139-144 passes it through to NodeStream.toArrayBuffer.
@effect/platform-node-shared/src/NodeStream.ts:427-434 checks only a defined cap.
Ten maximum-size files therefore mean 200 MiB decoded, about 267 MiB base64;
an external proxy or custom embedder can impose a different limit.
A refusal rejects the entire send; map MediaRefusal.code to a worded refusal.
"""
import base64
import hashlib
import mimetypes
import os
import re
import stat

MAX_FILES = 10
MAX_BYTES = 20 * 1024 * 1024
NAME = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,79}')
SHA = re.compile(r'[0-9a-f]{64}')
BOUND_KEYS = ('node', 'session', 'boot', 'pane', 'instance')


class MediaRefusal(ValueError):
    """Safe reason code only: no file bytes or personal path in the error."""
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def _directory(path):
    flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_DIRECTORY
    fd = os.open('/', flags)
    try:
        for part in path.split('/')[1:]:
            if not part or part in ('.', '..'):
                raise MediaRefusal('attachment_target')
            child = os.open(part, flags, dir_fd=fd)
            os.close(fd)
            fd = child
        return fd
    except BaseException:
        os.close(fd)
        raise


def _private(node, directory=False):
    kind = stat.S_ISDIR(node.st_mode) if directory else stat.S_ISREG(node.st_mode)
    if not kind or node.st_uid != os.getuid() or stat.S_IMODE(node.st_mode) != (0o700 if directory else 0o600):
        raise MediaRefusal('attachment_target')
    if not directory and node.st_nlink != 1:
        raise MediaRefusal('attachment_target')


def _bytes(record, root, limit):
    path, name = record.get('path'), record.get('name')
    if not isinstance(path, str) or not isinstance(name, str) or not NAME.fullmatch(name):
        raise MediaRefusal('attachment_target')
    if not os.path.isabs(path) or os.path.realpath(path) != path:
        raise MediaRefusal('attachment_target')
    relative = os.path.relpath(path, root).split('/')
    if len(relative) != 2 or relative[1] != name or not NAME.fullmatch(relative[0]):
        raise MediaRefusal('attachment_target')
    folder = _directory(os.path.join(root, relative[0]))
    try:
        _private(os.fstat(folder), directory=True)
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=folder)
    finally:
        os.close(folder)
    with os.fdopen(fd, 'rb') as handle:
        before = os.fstat(handle.fileno())
        _private(before)
        size = record.get('size')
        if type(size) is not int or size < 0 or before.st_size != size:
            raise MediaRefusal('attachment_missing')
        if size > limit:
            raise MediaRefusal('too_large')
        digest = record.get('sha256')
        if not isinstance(digest, str) or not SHA.fullmatch(digest):
            raise MediaRefusal('attachment_target')
        data = handle.read(limit + 1)
        after = os.fstat(handle.fileno())
        if len(data) != size or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
            raise MediaRefusal('attachment_missing')
        if hashlib.sha256(data).hexdigest() != digest:
            raise MediaRefusal('attachment_missing')
        return data


def _mime(data, record):
    # Types admitted by Herdline's image picker; OpenCode does its own decode.
    if data.startswith(b'\x89PNG\r\n\x1a\n'):
        mime = 'image/png'
    elif data.startswith(b'\xff\xd8\xff'):
        mime = 'image/jpeg'
    elif data.startswith((b'GIF87a', b'GIF89a')):
        mime = 'image/gif'
    elif data.startswith(b'RIFF') and data[8:12] == b'WEBP':
        mime = 'image/webp'
    else:
        mime = None
    if record['kind'] == 'image':
        if mime is None:
            raise MediaRefusal('unsupported_type')
        return mime
    return mime or mimetypes.guess_type(record['name'], strict=True)[0] or 'application/octet-stream'


def prepare(staged_records, bound_target, home=None, agent='opencode', limits=None):
    """Return complete PromptInput.files, or raise MediaRefusal before sending.

    bound_target is {node, session, boot, pane, instance}; instance is the exact
    existing TranscriptIdentity, with this `agent`. HOME is the exec shell's
    own, never the agent process's environment. `home` lets the integration
    supply that same verified shell root explicitly. `limits` is the most
    bytes per kind; OpenCode's own cap holds for both kinds by default.
    """
    limits = limits or {'image': MAX_BYTES, 'file': MAX_BYTES}
    if not isinstance(staged_records, list) or len(staged_records) > MAX_FILES:
        raise MediaRefusal('too_large')
    if not isinstance(bound_target, dict) or any(key not in bound_target for key in BOUND_KEYS):
        raise MediaRefusal('attachment_target')
    instance = bound_target['instance']
    if not isinstance(instance, dict) or instance.get('agent') != agent:
        raise MediaRefusal('attachment_needs_agent')
    if not staged_records:
        return []
    value = home if home is not None else os.environ.get('HOME')
    if not isinstance(value, str) or not os.path.isabs(value):
        raise MediaRefusal('attachment_target')
    home = os.path.realpath(value)
    root = os.path.join(home, '.cache', 'herdline', 'attachments')
    if os.path.realpath(root) != root:
        raise MediaRefusal('attachment_target')
    parts = []
    try:
        fd = _directory(root)
        try:
            _private(os.fstat(fd), directory=True)
        finally:
            os.close(fd)
        for record in staged_records:
            if not isinstance(record, dict) or any(record.get(key) != bound_target[key] for key in BOUND_KEYS):
                raise MediaRefusal('attachment_target')
            if record.get('kind') not in ('image', 'file'):
                raise MediaRefusal('unsupported_type')
            data = _bytes(record, root, limits[record['kind']])
            mime = _mime(data, record)
            parts.append({'uri': 'data:' + mime + ';base64,' + base64.b64encode(data).decode('ascii'),
                          'name': record['name'], 'source': {'type': 'inline'}})
        return parts
    except MediaRefusal:
        raise
    except FileNotFoundError:
        raise MediaRefusal('attachment_missing') from None
    except (OSError, ValueError):
        raise MediaRefusal('attachment_target') from None
