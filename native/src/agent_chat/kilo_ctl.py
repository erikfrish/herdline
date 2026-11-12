"""One call to the already-running local Kilo server, v1 routes only.

argv: op (send | status | state | withdraw | children), session id, payload JSON,
and `media` for a send with staged files: then opencode_media.py is part
of this script and {records, target} come on fd 3, as opencode_ctl.py
takes them. Prints one line: a word, or a word, a tab and a JSON payload.
Kilo's Stop, permissions and questions stay in opencode_ctl.py.

Discovery is opencode_ctl.py's Kilo rule, and no process environment is
read but the pane's root pid (HERDLINE_PANE_ROOT): exactly one of
$HOME/.local/state/kilo/daemon.json or
$HOME/.local/share/kilo/state/daemon.json (kilo core/src/global.ts:25-26,
kilocode/daemon/daemon.ts:97-99), this uid's, 0600, a loopback http url,
a live pid that is the pane's root or runs under it, the TUI's sibling
daemon included (else 'no_server': never another pane's server), asked
again just before the request that changes anything, Basic auth from its
username and password.

Routes, pinned to Kilo v7.8.27 (03a6f07),
packages/opencode/src/server/routes/instance/httpapi/groups:
- GET /session/:id -> Session.Info (session.ts:164), read first: the
  session's own directory routes the instance routes below.
- POST /session/:id/prompt_async {messageID, parts} -> 204 (session.ts:361);
  a bad attachment or an unknown session is a synchronous 400/404 before
  anything is admitted (handlers/session.ts:315-324, 354-359); for a file
  that is a data: raster Kilo cannot decode (kilocode/session/attachment.ts
  precheck, InvalidRequestError kind attachment, errors.ts:3-11). Parts are
  TextPartInput {type: text, text} and FilePartInput {type: file, mime,
  filename?, url} (schema/src/v1/session.ts:442-466); files go as data: URLs
  (session/prompt.ts:1039-1070), never a host path. messageID starts with
  msg (schema/src/v1/session.ts:17-20).
  On a session at work the message is stored at once and waits in Kilo's
  in-memory prompt queue (session/prompt.ts:1430-1436, 1505-1515;
  kilocode/session/prompt-queue.ts:213-288, the waiting FIFO :233-237);
  the running turn breaks for it at its next step boundary, closed as
  superseded (prompt-queue.ts:164-174, session/prompt.ts:1960-1975): a
  steer. Kilo has no next-turn queue, no promote and no route listing the
  waiting prompts (snapshot() serves only its cloud sender,
  kilo-sessions/remote-sender.ts:620-628). So a send with delivery steer
  goes only to a session at work, and one without only to an idle one.
- GET /session/status?directory= -> {sessionID: {type}} (session.ts:153);
  an absent session is idle (session/status.ts:72); types idle, retry,
  busy, offline, scheduled (schema/src/session-status-event.ts:11-47).
- GET /session/:id/message/:messageID -> {info, parts} (session.ts:223).
- DELETE /session/:id/message/:messageID?directory=&queued=true -> boolean
  (session.ts:133, 441-453, DeleteMessageQuery :50-53): true when the prompt
  was still waiting, then dropped and removed (handlers/session.ts:449-462,
  prompt-queue.ts:109-120); false when it waits no more (it started, or the
  id is unknown), and nothing is touched.
- GET /session/:id/children -> [Session.Info] (session.ts:176).
"""
import base64
import json
import os
import stat
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

op, session, payload = sys.argv[1], sys.argv[2], sys.argv[3]
MEDIA = sys.argv[4:] == ['media']
LIMIT = 65536
MAX_CHILDREN = 50
# Kilo v7.8.27 pins no cap on a data: file part it is sent: a raster is
# resized by Image.normalize (image/image.ts:172-215), text/plain is decoded
# whole (session/prompt.ts:1039-1057), anything else is kept as given. The
# native limits hold (attach::IMAGE_LIMIT, attach::FILE_LIMIT).
FILE_LIMITS = {'image': 20 * 1024 * 1024, 'file': 64 * 1024 * 1024}


class NoServer(Exception):
    pass


class BoundRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, target):
        return None


transport = urllib.request.build_opener(urllib.request.ProxyHandler({}), BoundRedirect())


def registration():
    home = os.path.realpath(os.environ['HOME'])
    files = []
    for root in (os.path.join(home, '.local', 'state', 'kilo'), os.path.join(home, '.local', 'share', 'kilo', 'state')):
        file = os.path.join(root, 'daemon.json')
        if os.path.lexists(file):
            if os.path.realpath(root) != root:
                raise ValueError('linked root')
            files.append(file)
    if not files:
        raise NoServer()
    if len(files) != 1:
        raise ValueError('two registrations')
    info = os.lstat(files[0])
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size > LIMIT:
        raise ValueError('loose registration')
    with open(files[0]) as handle:
        data = json.load(handle)
    url = data['url']
    address = urllib.parse.urlsplit(url)
    if address.scheme != 'http' or address.hostname not in ('127.0.0.1', 'localhost', '::1') or address.username or address.password or address.path not in ('', '/') or address.query or address.fragment:
        raise ValueError('not loopback')
    pid = data.get('pid')
    if not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0:
        raise ValueError('no pid')
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        raise NoServer()
    if not in_pane(pid):
        # Another pane's server on the same data root: never this pane's.
        raise NoServer()
    user, password = data['username'], data['password']
    if not isinstance(user, str) or not isinstance(password, str):
        raise ValueError('no credentials')
    headers = {
        'Content-Type': 'application/json',
        'Authorization': 'Basic ' + base64.b64encode((user + ':' + password).encode()).decode(),
    }
    return url.rstrip('/'), headers, pid


def in_pane(pid):
    """Whether `pid` is the pane's root or runs under it (as opencode_ctl.py,
    the nested herdr or tmux edge said there included)."""
    try:
        root = int(os.environ.get('HERDLINE_PANE_ROOT', ''))
    except ValueError:
        return False
    if root <= 1:
        return False
    if pid == root:
        return True
    for _ in range(64):
        pid = parent(pid)
        if pid == root:
            return True
        if pid is None or pid <= 1:
            return False
    return False


def ours(pid):
    """The registered daemon, still alive and still in the pane's tree."""
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    return in_pane(pid)


def parent(pid):
    """`pid`'s parent: /proc on Linux, else `ps`; None when unreadable."""
    try:
        with open('/proc/%d/stat' % pid) as handle:
            return int(handle.read().rsplit(')', 1)[1].split()[1])
    except (OSError, ValueError, IndexError):
        pass
    try:
        found = subprocess.run(['ps', '-o', 'ppid=', '-p', str(pid)], stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=5)
        return int(found.stdout.strip())
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def call(base, headers, method, path, body=None, timeout=10):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    with transport.open(request, timeout=timeout) as response:
        raw = response.read(LIMIT + 1)
    if len(raw) > LIMIT:
        raise ValueError('too large')
    return json.loads(raw) if raw.strip() else None


def quoted(value):
    return urllib.parse.quote(value, safe='')


def text(value):
    return value if isinstance(value, str) else ''


def directory_of(base, headers):
    found = call(base, headers, 'GET', '/session/' + quoted(session))
    directory = found['directory']
    if found['id'] != session or not isinstance(directory, str) or not os.path.isabs(directory):
        raise ValueError('another session')
    return directory


def status(base, headers, directory):
    found = call(base, headers, 'GET', '/session/status?directory=' + quoted(directory))
    if not isinstance(found, dict):
        raise ValueError('not a map')
    entry = found.get(session, {'type': 'idle'})
    kind = entry.get('type') if isinstance(entry, dict) else None
    if kind not in ('idle', 'retry', 'busy', 'offline', 'scheduled'):
        raise ValueError('unknown status')
    return kind


def unready(kind, steer):
    """The word refusing a send to a session in state [kind], else None:
    a steer joins only a turn at work, a send without a verb only an idle
    session. Nothing is turned into the other verb here."""
    if steer:
        return 'idle' if kind == 'idle' else None
    return None if kind == 'idle' else 'busy'


def parts(sent, files):
    """The prompt's parts: its text, then each verified staged file as a
    FilePartInput whose url is its data: URI. Never a host path: a file:
    url is read by Kilo after the 204 (session/prompt.ts:1079-1081, 1243-1330),
    behind its own read permission, where a failure is a note, not a refusal."""
    out = []
    if text(sent.get('text')):
        out.append({'type': 'text', 'text': sent['text']})
    for file in files:
        uri = file['uri']
        out.append({'type': 'file', 'mime': uri[len('data:'):uri.index(';base64,')], 'filename': file['name'], 'url': uri})
    return out or None


def refused_attachment(error):
    """A 400 naming an attachment (InvalidRequestError kind, errors.ts:3-11)."""
    try:
        found = json.loads(error.read(LIMIT + 1))
    except Exception:
        return False
    return isinstance(found, dict) and found.get('kind') == 'attachment'


def child(entry):
    return {'id': entry['id'], 'title': text(entry.get('title'))[:200]}


def main():
    try:
        base, headers, pid = registration()
    except NoServer:
        return 'no_server'
    except Exception:
        return 'discovery'
    try:
        directory = directory_of(base, headers)
        if op == 'state':
            # ingress::state's {idle, queued}. Kilo lists no waiting prompt
            # (see the routes above): the app shows its own steers.
            return 'state\t' + json.dumps({'idle': status(base, headers, directory) == 'idle', 'queued': []}, separators=(',', ':'))
        if op == 'status':
            message_id = json.loads(payload)['id']
            try:
                found = call(base, headers, 'GET', '/session/' + quoted(session) + '/message/' + quoted(message_id))
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    return 'unknown'
                raise
            return 'accepted' if found['info']['id'] == message_id else 'local'
        if op == 'children':
            found = call(base, headers, 'GET', '/session/' + quoted(session) + '/children')
            if not isinstance(found, list):
                raise ValueError('not a list')
            # A list naming another parent, an id that is no string, or one
            # seen twice is no list at all: nothing is dropped quietly.
            ids = [entry.get('id') if isinstance(entry, dict) and entry.get('parentID') == session else None for entry in found]
            if not all(isinstance(child_id, str) and child_id for child_id in ids) or len(set(ids)) != len(ids):
                raise ValueError('not this session\'s children')
            # Past MAX_CHILDREN the first ones go, marked as such (opencode_ctl's word).
            word = 'children_more' if len(found) > MAX_CHILDREN else 'children'
            return word + '\t' + json.dumps([child(entry) for entry in found[:MAX_CHILDREN]], separators=(',', ':'))
        if op == 'withdraw':
            message_id = json.loads(payload)['id']
            if not isinstance(message_id, str) or not message_id.startswith('msg'):
                return 'local'
        if op == 'send':
            sent = json.loads(payload)
            message_id = sent.get('id')
            if sent.get('delivery', 'steer') != 'steer':
                # Kilo has no other delivery: nothing is asked of it.
                return 'local'
            steer = sent.get('delivery') == 'steer'
            if not isinstance(message_id, str) or not message_id.startswith('msg') or not text(sent.get('text')):
                return 'invalid'
            # Without a verb a prompt starts a turn; a session at work is
            # never sent one. A steer goes only into a turn at work.
            word = unready(status(base, headers, directory), steer)
            if word:
                return word
            files = []
            if MEDIA:
                # The staged records and their target, on the command's own
                # stdin (fd 3): read, checked and encoded here, in the
                # process that sends them.
                with os.fdopen(3, 'rb') as handle:
                    raw = handle.read(LIMIT + 1)
                if len(raw) > LIMIT:
                    return 'local'
                given = json.loads(raw)
                target = given['target']
                try:
                    files = prepare(given['records'], target, agent='kilo', limits=FILE_LIMITS)
                except MediaRefusal as refusal:
                    return 'media\t' + refusal.code
                # Reading the files took time: the session, its state and
                # the agent process are asked again just before the prompt.
                if directory_of(base, headers) != directory:
                    return 'local'
                word = unready(status(base, headers, directory), steer)
                if word:
                    return word
                try:
                    os.kill(target['instance']['pid'], 0)
                except ProcessLookupError:
                    return 'gone'
            body = parts(sent, files)
        if op not in ('send', 'withdraw'):
            return 'local'
        # Discovery was some reads ago, and a send's files took time: the
        # daemon must still be alive and in the pane's tree just before the
        # one request that changes anything. Else nothing is asked of it.
        if not ours(pid):
            return 'no_server'
    except urllib.error.HTTPError as error:
        return 'gone' if error.code == 404 else 'auth' if error.code == 401 else 'local'
    except Exception:
        return 'local'
    # From this boundary on, an unreadable reply may hide an admitted change.
    try:
        if op == 'withdraw':
            path = '/session/' + quoted(session) + '/message/' + quoted(message_id) + '?directory=' + quoted(directory) + '&queued=true'
            found = call(base, headers, 'DELETE', path)
            # Only Kilo's own boolean says what became of it.
            if found is True or found is False:
                return 'withdrawn\t' + json.dumps(found)
            return 'doubt'
        path = '/session/' + quoted(session) + '/prompt_async'
        try:
            call(base, headers, 'POST', path, {'messageID': message_id, 'parts': body}, timeout=30)
        except urllib.error.HTTPError as error:
            # Refused before anything was admitted (handlers/session.ts:358-359):
            # a file Kilo cannot take refuses the whole send, in its words.
            if error.code == 400 and files and refused_attachment(error):
                return 'media\tunsupported_type'
            if error.code in (400, 404):
                return 'invalid' if error.code == 400 else 'gone'
            raise
        return 'queued'
    except Exception:
        return 'doubt'


try:
    print(main())
except Exception:
    print('local')
