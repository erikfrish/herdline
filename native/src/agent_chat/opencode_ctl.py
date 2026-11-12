"""One call to a discovered, already-running local OpenCode or Kilo server.

argv: flavor (opencode | kilo), op (send | state | status | withdraw |
promote | dialogs | submit | abort | children), session id, payload JSON, and `media`
for an OpenCode send with staged files: then opencode_media.py is part of
this script and {records, target} come on fd 3; prepare() turns them into
the prompt's files or a MediaRefusal ('media\t<code>') before anything is
sent. Prints one line: a word, or a word, a tab and a JSON payload. Kilo
takes only dialogs, submit and abort; the rest are OpenCode's own inbox.

Discovery reads only this user's default state directories under $HOME,
never another process's environment:
- OpenCode: exactly one $HOME/.local/state/opencode/service*.json.
- Kilo: $HOME/.local/state/kilo/daemon.json, or its fallback
  $HOME/.local/share/kilo/state/daemon.json (kilo core/src/global.ts:25-26,
  kilocode/daemon/daemon.ts:97-99); exactly one of them may exist.
A server registered elsewhere (a custom XDG_STATE_HOME) is not found, and a
bare TUI registers nothing: its worker has no port (kilo cli/cmd/tui.ts:
435-450). Both answer 'no_server'.

The registered server must also be the pane's own: its pid runs in the
pane's process tree, under the pane's root process (HERDLINE_PANE_ROOT:
Herdr's shell_pid from the same pane report the native gate has just
verified the instance against). Only pid ancestry is read (/proc/<pid>/stat
or ps), never another process's environment or command line. Kilo's daemon
started in the pane beside its TUI is the TUI's sibling under that shell,
and the pane's own. A server of another pane on the
same data root, such as a `serve --service` next to an `opencode
--standalone` whose own server listens on stdio only, answers 'no_server'
before any request: nothing is asked of a server outside the pane, for any
op.

Routes, pinned to source:
- OpenCode, anomalyco/opencode fedc6f2 packages/protocol/src/groups:
  GET /api/session/:id (session.ts:234); POST /api/session/:id/prompt
  {id, text, delivery?, files?} -> {data: Inbox.User} (session.ts:339;
  files are PromptInput {uri, name?, source?}, schema/prompt-input.ts:9-18);
  GET /api/session?parentID=&limit= -> {data: [Session.Info], cursor:
  {previous?, next?}} (session.ts:114-120, :128-145), then ?cursor=&limit=
  with the opaque cursor.next only: it carries the filters (core/src/
  session.ts:125-131) and is made for every non-empty page, the last one
  too (:403-427), so only an empty page ends the list;
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
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

flavor, op, session, payload = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
# A send whose staged files come on fd 3; opencode_media.py is then
# prepended to this script by the native caller.
MEDIA = sys.argv[5:] == ['media']
LIMIT = 65536
# Five full requests stay well inside the 64 KiB reply the host reads.
MAX_DIALOGS = 5
MAX_DETAIL = 4000
DECISIONS = (('once', 'Allow once'), ('always', 'Always allow'), ('reject', 'Reject'))
DELIVERIES = ('steer', 'queue')
# Shown in the queue panel only; a withdraw restores the app's own record.
MAX_QUEUED = 20
MAX_QUEUED_TEXT = 2000
# Children shown by title; one more read says whether there are others.
MAX_CHILDREN = 50
MAX_CHILD_TITLE = 200
# Small pages keep each reply far inside LIMIT.
CHILD_PAGE = 10
KILO_OPS = ('dialogs', 'submit', 'cancel', 'abort')
# One form or question set per dialog: five stay inside the 64 KiB reply.
MAX_DIALOG = 12000
MAX_FIELDS = 50
MAX_OPTIONS = 100
# The text formats a phone keyboard and the app's checks know.
FORMATS = {None: 'text', 'email': 'email', 'uri': 'url', 'date': 'date', 'date-time': 'datetime-local'}


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
    if not in_pane(pid):
        # Another pane's server on the same data root: never this pane's.
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


def in_pane(pid):
    """Whether `pid` runs in the pane's process tree, under its root."""
    try:
        root = int(os.environ.get('HERDLINE_PANE_ROOT', ''))
    except ValueError:
        return False
    if root <= 1:
        return False
    for _ in range(64):
        pid = parent(pid)
        if pid == root:
            return True
        if pid is None or pid <= 1:
            return False
    return False


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


def forms(base, headers):
    """OpenCode's pending forms of this session (session.ts:722). The
    `global` owner (MCP elicitation) is no session's: never shown here."""
    found = call(base, headers, 'GET', '/api/session/' + quoted(session) + '/form')['data']
    if not isinstance(found, list):
        raise ValueError('not a list')
    return [entry for entry in found if isinstance(entry, dict) and entry.get('sessionID') == session and isinstance(entry.get('id'), str) and entry['id'].startswith('frm_')]


def form_detail(base, headers, form_id):
    """The form and its state (session.ts:753), or None when the server has none."""
    try:
        found = call(base, headers, 'GET', '/api/session/' + quoted(session) + '/form/' + quoted(form_id))['data']
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise
    if not isinstance(found, dict) or found.get('id') != form_id or found.get('sessionID') != session:
        raise ValueError('another form')
    return found


def settled(detail):
    """A word for a form no longer pending, else None."""
    if detail is None:
        return 'not_open'
    status = (detail.get('state') or {}).get('status')
    if status == 'pending':
        return None
    return {'answered': 'answered', 'cancelled': 'not_open'}.get(status, 'not_open')


def questions(base, headers, directory):
    """Kilo's pending questions of this session (groups/question.ts list:
    every session's, so filtered here)."""
    found = call(base, headers, 'GET', '/question?directory=' + quoted(directory))
    if not isinstance(found, list):
        raise ValueError('not a list')
    return [entry for entry in found if isinstance(entry, dict) and entry.get('sessionID') == session and isinstance(entry.get('id'), str) and entry['id'].startswith('que')]


class Unsupported(Exception):
    """A form Chat can't show whole: said in words, never a subset."""
    def __init__(self, reason, subject=''):
        super().__init__(reason)
        self.reason, self.subject = reason, subject[:120]


def spans(value, extra=''):
    out = [{'text': text(value)[:MAX_DETAIL]}] if text(value) else []
    if text(extra):
        out.append({'text': ' — ' + text(extra)[:MAX_DETAIL], 'style': 'em'})
    return out


def options_of(field, checked):
    options = field.get('options')
    if not isinstance(options, list) or len(options) > MAX_OPTIONS or not all(isinstance(o, dict) and isinstance(o.get('value'), str) for o in options):
        raise Unsupported('field', text(field.get('title')) or text(field.get('key')))
    return [{'value': o['value'], 'label': spans(o.get('label') or o['value'], o.get('description')), 'checked': o['value'] in checked} for o in options]


def form_field(field):
    """One OpenCode field as the app's field (schema/form.ts:43-114)."""
    kind, key = field.get('type'), field['key']
    name = text(field.get('title')) or key
    out = {'name': key, 'label': spans(name), 'required': field.get('required') is True}
    if text(field.get('description')):
        out['help'] = spans(field['description'])
    default = field.get('default')
    if kind == 'string' and 'options' in field:
        out.update(kind='choice', other=field.get('custom') is True, options=options_of(field, [default]))
    elif kind == 'string':
        if field.get('format') not in FORMATS:
            raise Unsupported('field', name)
        out.update(kind='text', type=FORMATS[field.get('format')], value=text(default), placeholder=text(field.get('placeholder')))
        for limit in ('minLength', 'maxLength'):
            if isinstance(field.get(limit), int):
                out[limit] = field[limit]
        if text(field.get('pattern')):
            out['pattern'] = field['pattern']
    elif kind in ('number', 'integer'):
        number = default if isinstance(default, (int, float)) and not isinstance(default, bool) else None
        out.update(kind='text', type=kind, value='' if number is None else json.dumps(number), placeholder='')
        for limit, to in (('minimum', 'min'), ('maximum', 'max')):
            if isinstance(field.get(limit), (int, float)) and not isinstance(field.get(limit), bool):
                out[to] = field[limit]
    elif kind == 'boolean':
        checked = [json.dumps(default)] if isinstance(default, bool) else []
        out.update(kind='choice', other=False, options=[
            {'value': 'true', 'label': [{'text': 'Yes'}], 'checked': 'true' in checked},
            {'value': 'false', 'label': [{'text': 'No'}], 'checked': 'false' in checked},
        ])
    elif kind == 'multiselect':
        out.update(kind='choice', multi=True, other=field.get('custom') is True,
                   options=options_of(field, default if isinstance(default, list) else []))
        for limit in ('minItems', 'maxItems'):
            if isinstance(field.get(limit), int):
                out[limit] = field[limit]
    else:
        raise Unsupported('field', name)
    return out


def form_dialog(entry):
    """An OpenCode form as the app's form, whole or refused in words."""
    dialog = {'dialogId': entry['id'], 'kind': 'form', 'title': text(entry.get('title'))[:200], 'submittable': False, 'cancellable': True}
    try:
        fields, blocks, kinds = entry.get('fields'), [], {}
        if not isinstance(fields, list) or not 0 < len(fields) <= MAX_FIELDS or not all(isinstance(f, dict) and isinstance(f.get('key'), str) for f in fields):
            raise Unsupported('size')
        shown = []
        for field in fields:
            key, kind = field['key'], field.get('type')
            name = text(field.get('title')) or key
            when = field.get('when') or []
            # A condition on a field Chat does not answer (hidden, a link, a
            # later or unknown one) can't be checked on the phone.
            if not isinstance(when, list) or any(not isinstance(c, dict) or kinds.get(c.get('key')) not in ('answered',) or c.get('op') not in ('eq', 'neq') for c in when):
                raise Unsupported('condition', name)
            if kind == 'external':
                url = text(field.get('url'))
                if urllib.parse.urlsplit(url).scheme not in ('http', 'https'):
                    raise Unsupported('link', name)
                kinds[key] = 'external'
                blocks.append({'kind': 'link', 'url': url, 'label': spans(name, field.get('description'))})
                continue
            if field.get('hidden') is True:
                # Skips the prompt: the server takes its default (form.ts:47-49).
                kinds[key] = 'hidden'
                continue
            mapped = form_field(field)
            if when:
                mapped['when'] = [{'key': c['key'], 'op': c['op'], 'value': c.get('value')} for c in when]
            kinds[key] = 'answered'
            shown.append(mapped)
            blocks.append({'kind': 'field', 'field': {'name': key}})
        form = {'sections': [{'title': [], 'blocks': blocks}], 'fields': shown, 'hidden': []}
        whole = dict(dialog, submittable=True, form=form)
        if len(json.dumps(whole, separators=(',', ':'))) > MAX_DIALOG:
            raise Unsupported('size')
        return whole
    except Unsupported as why:
        return dict(dialog, unsupported={'reason': why.reason, 'subject': why.subject})


def question_dialog(entry):
    """A Kilo question set as one form: field qN per question, its option
    labels as values, since a reply names labels (schema/v1/question.ts)."""
    dialog = {'dialogId': entry['id'], 'kind': 'form', 'title': '', 'submittable': False, 'cancellable': True}
    asked = entry.get('questions')
    try:
        if not isinstance(asked, list) or not 0 < len(asked) <= MAX_FIELDS or not all(isinstance(q, dict) for q in asked):
            raise Unsupported('size')
        fields, blocks = [], []
        for index, question in enumerate(asked):
            options = question.get('options')
            if not isinstance(options, list) or len(options) > MAX_OPTIONS or not all(isinstance(o, dict) and isinstance(o.get('label'), str) for o in options):
                raise Unsupported('field', text(question.get('header')))
            multi = question.get('multiple') is True
            # The default is a label, for one-choice questions only.
            default = question.get('default') if not multi else None
            field = {
                'kind': 'choice', 'name': 'q%d' % index, 'label': spans(question.get('header')), 'required': True,
                'multi': multi, 'other': question.get('custom') is not False,
                'options': [{'value': o['label'], 'label': spans(o['label'], o.get('description')), 'checked': o['label'] == default} for o in options],
            }
            if text(question.get('question')):
                field['help'] = spans(question['question'])
            fields.append(field)
            blocks.append({'kind': 'field', 'field': {'name': field['name']}})
        whole = dict(dialog, title=text(asked[0].get('header'))[:200], submittable=True,
                     form={'sections': [{'title': [], 'blocks': blocks}], 'fields': fields, 'hidden': []})
        if len(json.dumps(whole, separators=(',', ':'))) > MAX_DIALOG:
            raise Unsupported('size')
        return whole
    except Unsupported as why:
        return dict(dialog, unsupported={'reason': why.reason, 'subject': why.subject})


class Invalid(Exception):
    pass


def holds(condition, answers):
    """schema/form.ts:28-41: unanswered is false for eq and neq alike;
    against a multiselect, eq is "includes"."""
    if condition['key'] not in answers:
        return False
    value, wanted = answers[condition['key']], condition.get('value')
    if isinstance(value, list):
        if not isinstance(wanted, str):
            return False
        hit = wanted in value
    elif number(value) and number(wanted) or type(value) is type(wanted):
        hit = value == wanted
    else:
        # A value not of the field's type matches neither way.
        return False
    return hit if condition['op'] == 'eq' else not hit


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def coerced(field, value):
    """The phone's value as the field's type, else Invalid; None is no answer."""
    kind = field.get('type')
    if value is None or value == '' or value == []:
        return None
    if kind == 'string':
        if not isinstance(value, str):
            raise Invalid()
        if 'options' in field and field.get('custom') is not True and value not in [o.get('value') for o in field['options']]:
            raise Invalid()
        return value
    if kind in ('number', 'integer'):
        try:
            number = float(value) if isinstance(value, str) else value
        except ValueError:
            raise Invalid()
        if isinstance(number, bool) or not isinstance(number, (int, float)) or number != number or number in (float('inf'), float('-inf')):
            raise Invalid()
        if kind == 'integer':
            if number != int(number):
                raise Invalid()
            return int(number)
        return number
    if kind == 'boolean':
        if value in (True, 'true'):
            return True
        if value in (False, 'false'):
            return False
        raise Invalid()
    if kind == 'multiselect':
        values = [value] if isinstance(value, str) else value
        if not isinstance(values, list) or not all(isinstance(v, str) for v in values):
            raise Invalid()
        known = [o.get('value') for o in field.get('options') or []]
        if field.get('custom') is not True and any(v not in known for v in values):
            raise Invalid()
        return values
    raise Invalid()


def form_answer(detail, data):
    """The answer the server takes, re-checked against its own fields: only
    active, answerable keys; a required active one must be answered."""
    if not isinstance(data, dict):
        raise Invalid()
    fields = detail.get('fields') if isinstance(detail.get('fields'), list) else []
    answerable = {f.get('key') for f in fields if isinstance(f, dict) and f.get('type') != 'external' and f.get('hidden') is not True}
    if any(key not in answerable for key in data):
        raise Invalid()
    answers = {}
    for field in fields:
        key = field.get('key')
        if key not in answerable or not all(holds(c, answers) for c in field.get('when') or []):
            continue
        value = coerced(field, data.get(key))
        if value is None:
            if field.get('required') is True:
                raise Invalid()
            continue
        answers[key] = value
    return answers


def question_answers(entry, data):
    """Label lists in question order (question.ts ReplyPayload)."""
    if not isinstance(data, dict):
        raise Invalid()
    asked = entry.get('questions') or []
    if set(data) - {'q%d' % i for i in range(len(asked))}:
        raise Invalid()
    answers = []
    for index, question in enumerate(asked):
        value = data.get('q%d' % index)
        labels = [value] if isinstance(value, str) else value
        if not isinstance(labels, list) or not labels or not all(isinstance(l, str) and l for l in labels):
            raise Invalid()
        if question.get('multiple') is not True and len(labels) != 1:
            raise Invalid()
        known = [o.get('label') for o in question.get('options') or []]
        if question.get('custom') is False and any(l not in known for l in labels):
            raise Invalid()
        answers.append(labels)
    return answers


def active(base, headers):
    """Whether the server runs this session: protocol groups/session.ts
    SessionActive {type: 'running'} (:43-45), absent = inactive (:222-229).
    Any other shape is unreadable, never taken as idle."""
    found = call(base, headers, 'GET', '/api/session/active')['data']
    if not isinstance(found, dict) or not all(entry == {'type': 'running'} for entry in found.values()):
        raise ValueError('not SessionActive')
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


def children(base, headers):
    """The sessions this one started, most recently updated first as the
    server orders them, and whether more than MAX_CHILDREN exist. Only the returned
    cursor is followed, never decoded or made here; an entry naming another
    parent, or one seen twice, is no list at all."""
    found, seen = [], set()
    query = 'parentID=' + quoted(session)
    while len(found) <= MAX_CHILDREN:
        want = min(CHILD_PAGE, MAX_CHILDREN + 1 - len(found))
        page = call(base, headers, 'GET', '/api/session?' + query + '&limit=' + str(want))
        data, cursor = page['data'], page['cursor']
        if not isinstance(data, list) or not isinstance(cursor, dict) or len(data) > want:
            raise ValueError('not SessionsResponse')
        if not data:
            return found, False
        for entry in data:
            if not isinstance(entry, dict) or entry.get('parentID') != session:
                raise ValueError('another parent')
            child, title = entry.get('id'), entry.get('title', '')
            if not isinstance(child, str) or not child or child in seen or not isinstance(title, str):
                raise ValueError('not a child')
            seen.add(child)
            found.append({'id': child, 'title': title[:MAX_CHILD_TITLE]})
        following = cursor.get('next')
        if not isinstance(following, str) or not following:
            raise ValueError('a page without its next cursor')
        query = 'cursor=' + quoted(following)
    return found[:MAX_CHILDREN], True


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
        if op == 'children':
            listed_children, more = children(base, headers)
            return ('children_more' if more else 'children') + '\t' + json.dumps(listed_children, separators=(',', ':'))
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
            if MEDIA:
                if flavor != 'opencode':
                    return 'local'
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
                    sent['files'] = prepare(given['records'], target)
                except MediaRefusal as refusal:
                    return 'media\t' + refusal.code
                # Reading the files took time: the session and the agent
                # process are asked again just before the prompt goes.
                verified(base, headers)
                try:
                    os.kill(target['instance']['pid'], 0)
                except ProcessLookupError:
                    return 'gone'
        if op == 'dialogs':
            entries = [dialog(entry) for entry in open_requests(base, headers, directory)]
            asked = forms(base, headers) if flavor == 'opencode' else questions(base, headers, directory)
            entries += [form_dialog(entry) if flavor == 'opencode' else question_dialog(entry) for entry in asked]
            # Past MAX_DIALOGS the first ones go, marked as such: a pending
            # permission or question past them is never hidden as none.
            word = 'dialogs_more' if len(entries) > MAX_DIALOGS else 'dialogs'
            return word + '\t' + json.dumps(entries[:MAX_DIALOGS], separators=(',', ':'))
        if op in ('submit', 'cancel'):
            asked = json.loads(payload)
            request_id = asked['dialogId']
            if op == 'submit' and request_id.startswith('per'):
                decision = asked['decision']
                if decision not in [value for value, _ in DECISIONS]:
                    return 'invalid'
                if all(entry['id'] != request_id for entry in open_requests(base, headers, directory)):
                    return 'not_open'
            elif flavor == 'opencode' and request_id.startswith('frm_'):
                # The server's own form, re-read: the phone's copy may be old.
                detail = form_detail(base, headers, request_id)
                word = settled(detail)
                if word is not None:
                    return word
                if op == 'submit':
                    if not form_dialog(detail)['submittable']:
                        # A form Chat refused to show is never answered from it.
                        return 'invalid'
                    try:
                        reply = form_answer(detail, asked['answer'])
                    except Invalid:
                        return 'invalid'
            elif flavor == 'kilo' and request_id.startswith('que'):
                entry = next((entry for entry in questions(base, headers, directory) if entry['id'] == request_id), None)
                if entry is None:
                    # Kilo drops a settled question: answered or not, it is gone.
                    return 'not_open'
                if op == 'submit':
                    if not question_dialog(entry)['submittable']:
                        return 'invalid'
                    try:
                        reply = question_answers(entry, asked['answer'])
                    except Invalid:
                        return 'invalid'
            else:
                return 'invalid'
    except urllib.error.HTTPError as error:
        return 'gone' if error.code == 404 else 'auth' if error.code == 401 else 'local'
    except Exception:
        return 'local'
    # From this boundary on, unreadable transport/error responses may hide
    # an admitted change. Never downgrade them to a safe-to-retry refusal.
    try:
        if op == 'send':
            path = '/api/session/' + quoted(session) + '/prompt'
            # One id end to end (pinned fedc6f2): the prompt id is the inbox
            # entry's id and the user message's id (core/session/session.ts:
            # 116-180 admit, inbox.ts:378-402 projectDelivered returns row.id,
            # projector.ts:1013-1039 inserts the user message with it). So
            # status, withdraw and promote ask by this same id.
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
                # 404/409 moved nothing (no session, or the entry is no longer
                # queued). An entry already steering conflicts too: the inbox
                # listing it as a steer says it is one.
                if error.code in (404, 409):
                    entry = entry_of(base, headers, inbox_id)
                    return 'promoted\t' + json.dumps(entry is not None and entry.get('delivery') == 'steer')
                return 'doubt'
            # The 204 is the steer (pinned fedc6f2): projectDelivery moves only
            # a row still queued, else LifecycleConflict rolls back to a 409
            # (core/session/inbox.ts:378-402, server/handlers/session.ts:555-566).
            # The wake may drain it before any re-read, so none is made. Unlike
            # DELETE, which swallows the conflict as a 204 (session.ts:545-554).
            return 'promoted\ttrue'
        if op == 'submit' and request_id.startswith('per'):
            if flavor == 'opencode':
                path = '/api/session/' + quoted(session) + '/permission/' + quoted(request_id) + '/reply'
                call(base, headers, 'POST', path, {'decision': decision})
                return 'submitted'
            path = '/permission/' + quoted(request_id) + '/reply?directory=' + quoted(directory)
            # A person answered this on the phone: Kilo's interactive flag.
            done = call(base, headers, 'POST', path, {'reply': decision, 'interactive': True})
            return 'submitted' if done is True else 'doubt'
        if op in ('submit', 'cancel') and flavor == 'opencode':
            path = '/api/session/' + quoted(session) + '/form/' + quoted(request_id)
            try:
                if op == 'submit':
                    # session.ts:768: 204 is this answer taken, once.
                    call(base, headers, 'POST', path + '/reply', {'answer': reply})
                    return 'submitted'
                # session.ts:784: the message tells the asker why.
                call(base, headers, 'DELETE', path + '?message=' + quoted('Declined in Herdline Chat'))
            except urllib.error.HTTPError as error:
                # A refusal changed nothing; the re-read says what holds now.
                word = settled(form_detail(base, headers, request_id))
                if word is not None:
                    return word
                return 'invalid' if op == 'submit' and error.code == 400 else 'doubt'
            # A cancel is confirmed by the state, not the 204.
            word = settled(form_detail(base, headers, request_id))
            if word == 'not_open':
                return 'cancelled'
            return word or 'doubt'
        if op in ('submit', 'cancel'):
            # groups/question.ts reply/reject: true is done; 404 is gone.
            path = '/question/' + quoted(request_id) + ('/reply' if op == 'submit' else '/reject') + '?directory=' + quoted(directory)
            try:
                done = call(base, headers, 'POST', path, {'answers': reply} if op == 'submit' else None)
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    return 'not_open'
                return 'invalid' if op == 'submit' and error.code == 400 else 'doubt'
            return ('submitted' if op == 'submit' else 'cancelled') if done is True else 'doubt'
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
