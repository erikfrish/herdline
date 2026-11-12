"""One call to a discovered, already-running local OpenCode or Kilo server.

argv: flavor (opencode | kilo), op (send | state | status | withdraw |
promote | dialogs | submit | abort), session id, payload JSON. Prints one
line: a word, or a word, a tab and a JSON payload. Kilo takes only
dialogs, submit and abort; the rest are OpenCode's own inbox.

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
  {id, text, delivery?} -> {data: Inbox.User} (session.ts:339);
  GET /api/session/active -> {data: {id: {type}}}, absent = inactive
  (session.ts:222); GET /api/session/:id/inbox -> {data: [Inbox.Info]} in
  enqueue order (session.ts:542); DELETE /api/session/:id/inbox/:inboxID,
  a no-op once unavailable (session.ts:556); PATCH .../inbox/:inboxID
  {delivery} (session.ts:569); GET /api/session/:id/message/:messageID
  (session.ts:709); GET /api/session/:id/permission (permission.ts:114);
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
DELIVERIES = ('steer', 'queue')
# Shown in the queue panel only; a withdraw restores the app's own record.
MAX_QUEUED = 20
MAX_QUEUED_TEXT = 2000
KILO_OPS = ('dialogs', 'submit', 'abort')


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


def active(base, headers):
    found = call(base, headers, 'GET', '/api/session/active')['data']
    if not isinstance(found, dict):
        raise ValueError('not a map')
    return session in found


def inbox(base, headers):
    found = call(base, headers, 'GET', '/api/session/' + quoted(session) + '/inbox')['data']
    if not isinstance(found, list) or not all(isinstance(entry, dict) and entry.get('sessionID') == session for entry in found):
        raise ValueError('not this session')
    return found


def request_id(inbox_id):
    """The app's request id inside an inbox id it made (msg_<uuid>), else None."""
    if not isinstance(inbox_id, str) or not inbox_id.startswith('msg_'):
        return None
    rest = inbox_id[4:]
    shaped = len(rest) == 36 and all(c == '-' if at in (8, 13, 18, 23) else c in '0123456789abcdef' for at, c in enumerate(rest))
    return rest if shaped else None


def state(base, headers):
    queued = []
    for entry in inbox(base, headers):
        payload = entry.get('payload')
        queue = {'steer': 'steering', 'queue': 'followUp'}.get(entry.get('delivery'))
        if entry.get('type') != 'user' or queue is None or not isinstance(payload, dict):
            continue
        queued.append({'requestId': request_id(entry.get('id')), 'text': text(payload.get('text'))[:MAX_QUEUED_TEXT], 'queue': queue})
    return {'idle': not active(base, headers), 'queued': queued[:MAX_QUEUED]}


def listed(base, headers, inbox_id):
    return entry_of(base, headers, inbox_id) is not None


def entry_of(base, headers, inbox_id):
    """This session's inbox entry with this id, else None."""
    return next((entry for entry in inbox(base, headers) if entry.get('id') == inbox_id), None)



def in_history(base, headers, message_id):
    """Whether the session's history holds this message; a 404 is no."""
    try:
        found = call(base, headers, 'GET', '/api/session/' + quoted(session) + '/message/' + quoted(message_id))['data']
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return False
        raise
    if not isinstance(found, dict) or found.get('id') != message_id:
        raise ValueError('another message')
    return True


def main():
    if flavor == 'kilo' and op not in KILO_OPS:
        # Kilo's v1 server has no such route here: nothing is asked of it.
        return 'local'
    try:
        base, headers = registration()
    except NoServer:
        # No server for this user's defaults: said as such, for every op.
        return 'no_server'
    except Exception:
        # A registration that cannot be trusted as the one server (another
        # root, two of them, loose permissions): refused, never guessed.
        return 'discovery'
    try:
        directory = verified(base, headers)
        if op == 'state':
            return 'state\t' + json.dumps(state(base, headers), separators=(',', ':'))
        if op == 'status':
            message_id = json.loads(payload)['id']
            found = listed(base, headers, message_id) or in_history(base, headers, message_id)
            return 'accepted' if found else 'unknown'
        if op in ('withdraw', 'promote'):
            inbox_id = json.loads(payload)['id']
            if not listed(base, headers, inbox_id):
                # Already left the queue: nothing to take back or steer.
                return ('withdrawn' if op == 'withdraw' else 'promoted') + '\tfalse'
        if op == 'send':
            sent = json.loads(payload)
            if sent.get('delivery', 'steer') not in DELIVERIES:
                return 'local'
            # Without a verb the prompt starts a turn; a working session
            # takes only the verb the user chose, never one guessed here.
            if 'delivery' not in sent and active(base, headers):
                return 'busy'
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
            path = '/api/session/' + quoted(session) + '/prompt'
            result = call(base, headers, 'POST', path, sent, timeout=30)['data']
            if result.get('sessionID') == session and result.get('id') == sent['id']:
                return 'queued'
            return 'doubt'
        if op == 'withdraw':
            call(base, headers, 'DELETE', '/api/session/' + quoted(session) + '/inbox/' + quoted(inbox_id))
            # Cancel is a no-op on an item already delivered, so leaving the
            # inbox alone proves nothing: in the history means it ran.
            if listed(base, headers, inbox_id):
                return 'doubt'
            return 'withdrawn\t' + json.dumps(not in_history(base, headers, inbox_id))
        if op == 'promote':
            path = '/api/session/' + quoted(session) + '/inbox/' + quoted(inbox_id)
            try:
                call(base, headers, 'PATCH', path, {'delivery': 'steer'})
            except urllib.error.HTTPError as error:
                # Conflict or not found: not a pending item any more. Only
                # an entry gone from the inbox proves nothing was moved.
                if error.code in (404, 409) and not listed(base, headers, inbox_id):
                    return 'promoted\tfalse'
                return 'doubt'
            # A 204 alone is no proof: the update may meet an item that just
            # left or stayed (session.inbox.update, pinned v2.2.7). Only the
            # inbox listing it as a steer says it is one. Gone: it ran or
            # left, as a steer or not, which nothing here can tell.
            entry = entry_of(base, headers, inbox_id)
            if entry is not None and entry.get('delivery') == 'steer':
                return 'promoted\ttrue'
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
