"""One call to a discovered, already-running local OpenCode or Kilo server.

argv: flavor (opencode | kilo), op (send | dialogs | submit | abort),
session id, payload JSON. Prints one line: a word, or a word, a tab and a
JSON payload.

Discovery reads only this user's default state directories under $HOME,
never another process's environment:
- OpenCode: exactly one $HOME/.local/state/opencode/service*.json.
- Kilo: $HOME/.local/state/kilo/daemon.json, or its fallback
  $HOME/.local/share/kilo/state/daemon.json (kilo core/src/global.ts:25-26,
  kilocode/daemon/daemon.ts:97-99); exactly one of them may exist.
A server registered elsewhere (a custom XDG_STATE_HOME) is not found, and a
bare TUI registers nothing: its worker has no port (kilo cli/cmd/tui.ts:
435-450). Both answer 'no_server'.

Routes, pinned to source:
- OpenCode, anomalyco/opencode fedc6f2 packages/protocol/src/groups:
  GET /api/session/:id (session.ts:234); POST /api/session/:id/prompt
  (session.ts:339); GET /api/session/:id/permission (permission.ts:114);
  POST /api/session/:id/permission/:requestID/reply {decision}
  (permission.ts:142); POST /api/session/:id/interrupt -> {interrupted}
  (session.ts:676).
- Kilo, packages/opencode/src/server/routes/instance/httpapi/groups:
  GET /session/:id (session.ts SessionPaths.get); GET /permission?directory=
  (permission.ts:36); POST /permission/:requestID/reply?directory=
  {reply, interactive} (permission.ts:44); POST /session/:id/abort ->
  boolean (session.ts:285). Session routes resolve the session's own
  directory; the permission routes take it from the session as read.
"""
import base64
import glob
import json
import os
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request

flavor, op, session, payload = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
LIMIT = 65536
# Five full requests stay well inside the 64 KiB reply the host reads.
MAX_DIALOGS = 5
MAX_DETAIL = 4000
DECISIONS = (('once', 'Allow once'), ('always', 'Always allow'), ('reject', 'Reject'))


class NoServer(Exception):
    pass


class Refused(Exception):
    pass


class BoundRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, target):
        return None


transport = urllib.request.build_opener(urllib.request.ProxyHandler({}), BoundRedirect())


def registration():
    home = os.path.realpath(os.environ['HOME'])
    if flavor == 'opencode':
        root = os.path.join(home, '.local', 'state', 'opencode')
        if os.path.realpath(root) != root:
            raise Refused()
        files = glob.glob(os.path.join(root, 'service*.json'))
    else:
        files = []
        for root in (os.path.join(home, '.local', 'state', 'kilo'), os.path.join(home, '.local', 'share', 'kilo', 'state')):
            file = os.path.join(root, 'daemon.json')
            if os.path.lexists(file):
                if os.path.realpath(root) != root:
                    raise Refused()
                files.append(file)
    if not files:
        raise NoServer()
    if len(files) != 1:
        raise Refused()
    file = files[0]
    info = os.lstat(file)
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size > LIMIT:
        raise Refused()
    with open(file) as handle:
        data = json.load(handle)
    url = data['url']
    address = urllib.parse.urlsplit(url)
    if address.scheme != 'http' or address.hostname not in ('127.0.0.1', 'localhost', '::1') or address.username or address.password or address.path not in ('', '/') or address.query or address.fragment:
        raise Refused()
    pid = data.get('pid')
    if not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0:
        raise Refused()
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        # A registration its server outlived: nothing is listening for it.
        raise NoServer()
    headers = {'Content-Type': 'application/json'}
    if flavor == 'opencode':
        if data.get('password'):
            headers['Authorization'] = 'Basic ' + base64.b64encode(('opencode:' + data['password']).encode()).decode()
    else:
        user, password = data['username'], data['password']
        if not isinstance(user, str) or not isinstance(password, str):
            raise Refused()
        headers['Authorization'] = 'Basic ' + base64.b64encode((user + ':' + password).encode()).decode()
    return url.rstrip('/'), headers


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


def verified(base, headers):
    """The session as the server knows it: its directory for Kilo, '' for
    OpenCode. Before any change is asked, so every failure is a refusal."""
    if flavor == 'opencode':
        found = call(base, headers, 'GET', '/api/session/' + quoted(session))['data']
        if found['id'] != session:
            raise Refused()
        return ''
    found = call(base, headers, 'GET', '/session/' + quoted(session))
    directory = found['directory']
    if found['id'] != session or not isinstance(directory, str) or not os.path.isabs(directory):
        raise Refused()
    return directory


def open_requests(base, headers, directory):
    if flavor == 'opencode':
        found = call(base, headers, 'GET', '/api/session/' + quoted(session) + '/permission')['data']
    else:
        found = call(base, headers, 'GET', '/permission?directory=' + quoted(directory))
    if not isinstance(found, list):
        raise ValueError('not a list')
    return [entry for entry in found if isinstance(entry, dict) and entry.get('sessionID') == session and isinstance(entry.get('id'), str) and entry['id'].startswith('per')]


def text(value):
    return value if isinstance(value, str) else ''


def dialog(entry):
    if flavor == 'opencode':
        title, items = text(entry.get('action')), entry.get('resources')
    else:
        title, items = text(entry.get('permission')), entry.get('patterns')
    blocks = []
    message = text(entry.get('message'))
    if message:
        blocks.append({'kind': 'text', 'spans': [{'text': message[:MAX_DETAIL]}]})
    items = [item for item in items if isinstance(item, str)] if isinstance(items, list) else []
    if items:
        blocks.append({'kind': 'pre', 'text': '\n'.join(items)[:MAX_DETAIL]})
    blocks.append({'kind': 'field', 'field': {'name': 'decision'}})
    field = {
        'kind': 'choice',
        'name': 'decision',
        'label': [],
        'required': True,
        'options': [{'value': value, 'label': [{'text': label}]} for value, label in DECISIONS],
    }
    return {
        'dialogId': entry['id'],
        'kind': 'permission',
        'title': title[:200],
        'submittable': True,
        'form': {'sections': [{'title': [], 'blocks': blocks}], 'fields': [field], 'hidden': []},
    }


def main():
    try:
        base, headers = registration()
    except NoServer:
        # A send keeps the answer it always gave for an unreachable service.
        return 'local' if op == 'send' else 'no_server'
    try:
        directory = verified(base, headers)
        if op == 'dialogs':
            entries = open_requests(base, headers, directory)[:MAX_DIALOGS]
            return 'dialogs\t' + json.dumps([dialog(entry) for entry in entries], separators=(',', ':'))
        if op == 'submit':
            asked = json.loads(payload)
            request_id, decision = asked['dialogId'], asked['decision']
            if decision not in [value for value, _ in DECISIONS]:
                return 'invalid'
            if all(entry['id'] != request_id for entry in open_requests(base, headers, directory)):
                return 'not_open'
    except urllib.error.HTTPError as error:
        return 'gone' if error.code == 404 else 'auth' if error.code == 401 else 'local'
    except Exception:
        return 'local'
    # From this boundary on, unreadable transport/error responses may hide
    # an admitted change. Never downgrade them to a safe-to-retry refusal.
    try:
        if op == 'send':
            sent = json.loads(payload)
            path = '/api/session/' + quoted(session) + '/prompt'
            result = call(base, headers, 'POST', path, sent, timeout=30)['data']
            if result.get('sessionID') == session and result.get('id') == sent['id']:
                return 'queued'
            return 'doubt'
        if op == 'submit':
            if flavor == 'opencode':
                path = '/api/session/' + quoted(session) + '/permission/' + quoted(request_id) + '/reply'
                call(base, headers, 'POST', path, {'decision': decision})
                return 'submitted'
            path = '/permission/' + quoted(request_id) + '/reply?directory=' + quoted(directory)
            # A person answered this on the phone: Kilo's interactive flag.
            done = call(base, headers, 'POST', path, {'reply': decision, 'interactive': True})
            return 'submitted' if done is True else 'doubt'
        if op == 'abort':
            if flavor == 'opencode':
                stopped = call(base, headers, 'POST', '/api/session/' + quoted(session) + '/interrupt')['interrupted']
            else:
                stopped = call(base, headers, 'POST', '/session/' + quoted(session) + '/abort')
            if isinstance(stopped, bool):
                return 'aborted\t' + json.dumps(stopped)
            return 'doubt'
        return 'doubt'
    except urllib.error.HTTPError as error:
        # The request this reply names is no longer open: nothing changed.
        if op == 'submit' and error.code == 404:
            return 'not_open'
        return 'doubt'
    except Exception:
        return 'doubt'


try:
    print(main())
except Exception:
    print('local')
