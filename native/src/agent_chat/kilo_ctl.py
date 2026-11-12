"""One call to the already-running local Kilo server, v1 routes only.

argv: op (send | status | state | questions | answer | children), session
id, payload JSON. Prints one line: a word, or a word, a tab and a JSON
payload. Kilo's Stop and permission dialogs stay in opencode_ctl.py.

Discovery is opencode_ctl.py's Kilo rule, and no process environment is
read: exactly one of $HOME/.local/state/kilo/daemon.json or
$HOME/.local/share/kilo/state/daemon.json (kilo core/src/global.ts:25-26,
kilocode/daemon/daemon.ts:97-99), this uid's, 0600, a loopback http url,
a live pid, Basic auth from its username and password.

Routes, pinned to Kilo v7.8.27 (03a6f07),
packages/opencode/src/server/routes/instance/httpapi/groups:
- GET /session/:id -> Session.Info (session.ts:164), read first: the
  session's own directory routes the instance routes below.
- POST /session/:id/prompt_async {messageID, parts} -> 204 (session.ts:361);
  a bad attachment or an unknown session is a synchronous 400/404 before
  anything is admitted (handlers/session.ts:315-324, 354-359). Parts are
  TextPartInput {type: text, text} and FilePartInput {type: file, mime,
  filename?, url} (schema/src/v1/session.ts:442-466); a file: url is read
  from the host's disk (session/prompt.ts:1079-1081). messageID starts with
  msg (schema/src/v1/session.ts:17-20).
- GET /session/status?directory= -> {sessionID: {type}} (session.ts:153);
  an absent session is idle (session/status.ts:72); types idle, retry,
  busy, offline, scheduled (schema/src/session-status-event.ts:11-47).
- GET /session/:id/message/:messageID -> {info, parts} (session.ts:223).
- GET /question?directory= -> [Question.Request] (question.ts:22); POST
  /question/:id/reply?directory= {answers} -> boolean (question.ts:32);
  Request {id, sessionID, questions: [{question, header, options: [{label,
  description}], multiple?, custom?}]}, each answer the selected labels
  (schema/src/v1/question.ts:16-73).
- GET /session/:id/children -> [Session.Info] (session.ts:176).
"""
import base64
import json
import os
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request

op, session, payload = sys.argv[1], sys.argv[2], sys.argv[3]
LIMIT = 65536
MAX_QUESTIONS = 5
MAX_TEXT = 4000
MAX_CHILDREN = 50
MAX_FILES = 10


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
    user, password = data['username'], data['password']
    if not isinstance(user, str) or not isinstance(password, str):
        raise ValueError('no credentials')
    headers = {
        'Content-Type': 'application/json',
        'Authorization': 'Basic ' + base64.b64encode((user + ':' + password).encode()).decode(),
    }
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


def parts(sent):
    """The prompt's parts: its text, then each file the host already holds."""
    out = []
    if text(sent.get('text')):
        out.append({'type': 'text', 'text': sent['text']})
    files = sent.get('files', [])
    if not isinstance(files, list) or len(files) > MAX_FILES:
        return None
    for file in files:
        path, mime = file.get('path') if isinstance(file, dict) else None, file.get('mime') if isinstance(file, dict) else None
        if not isinstance(path, str) or not os.path.isabs(path) or not isinstance(mime, str) or '/' not in mime:
            return None
        part = {'type': 'file', 'mime': mime, 'url': 'file://' + urllib.parse.quote(os.path.normpath(path))}
        if isinstance(file.get('filename'), str):
            part['filename'] = file['filename']
        out.append(part)
    return out or None


def open_questions(base, headers, directory):
    found = call(base, headers, 'GET', '/question?directory=' + quoted(directory))
    if not isinstance(found, list):
        raise ValueError('not a list')
    return [entry for entry in found if isinstance(entry, dict) and entry.get('sessionID') == session and isinstance(entry.get('id'), str) and entry['id'].startswith('que') and isinstance(entry.get('questions'), list)]


def labels(question):
    options = question.get('options') if isinstance(question, dict) else None
    return [text(option.get('label')) for option in options if isinstance(option, dict)] if isinstance(options, list) else []


def shown(entry):
    questions = []
    for question in entry['questions']:
        if not isinstance(question, dict):
            continue
        questions.append({
            'header': text(question.get('header'))[:200],
            'question': text(question.get('question'))[:MAX_TEXT],
            'options': [{'label': text(option.get('label'))[:200], 'description': text(option.get('description'))[:MAX_TEXT]}
                        for option in question.get('options', []) if isinstance(option, dict)],
            'multiple': question.get('multiple') is True,
            'custom': question.get('custom') is not False,
        })
    return {'id': entry['id'], 'questions': questions}


def fits(entry, answers):
    """Exactly one answer per question, each its labels or one typed answer."""
    questions = entry['questions']
    if not isinstance(answers, list) or len(answers) != len(questions):
        return False
    for question, answer in zip(questions, answers):
        if not isinstance(question, dict) or not isinstance(answer, list) or not answer or not all(isinstance(item, str) and item for item in answer):
            return False
        if question.get('multiple') is not True and len(answer) != 1:
            return False
        if question.get('custom') is False and not set(answer) <= set(labels(question)):
            return False
    return True


def child(entry):
    return {'id': entry['id'], 'title': text(entry.get('title'))[:200]}


def main():
    try:
        base, headers = registration()
    except NoServer:
        return 'no_server'
    except Exception:
        return 'discovery'
    try:
        directory = directory_of(base, headers)
        if op == 'state':
            # ingress::state's {idle, queued}; the app queues nothing on Kilo.
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
        if op == 'questions':
            entries = open_questions(base, headers, directory)[:MAX_QUESTIONS]
            return 'questions\t' + json.dumps([shown(entry) for entry in entries], separators=(',', ':'))
        if op == 'children':
            found = call(base, headers, 'GET', '/session/' + quoted(session) + '/children')
            if not isinstance(found, list):
                raise ValueError('not a list')
            mine = [entry for entry in found if isinstance(entry, dict) and entry.get('parentID') == session and isinstance(entry.get('id'), str)]
            return 'children\t' + json.dumps([child(entry) for entry in mine[:MAX_CHILDREN]], separators=(',', ':'))
        if op == 'answer':
            asked = json.loads(payload)
            request_id = asked['id']
            entry = next((entry for entry in open_questions(base, headers, directory) if entry['id'] == request_id), None)
            if entry is None:
                return 'not_open'
            if not fits(entry, asked.get('answers')):
                return 'invalid'
        if op == 'send':
            sent = json.loads(payload)
            message_id, body = sent.get('id'), parts(sent)
            if not isinstance(message_id, str) or not message_id.startswith('msg') or body is None:
                return 'invalid'
            # A prompt starts a turn; a session at work is never sent one here.
            if status(base, headers, directory) != 'idle':
                return 'busy'
        if op not in ('send', 'answer'):
            return 'local'
    except urllib.error.HTTPError as error:
        return 'gone' if error.code == 404 else 'auth' if error.code == 401 else 'local'
    except Exception:
        return 'local'
    # From this boundary on, an unreadable reply may hide an admitted change.
    try:
        if op == 'send':
            path = '/session/' + quoted(session) + '/prompt_async'
            try:
                call(base, headers, 'POST', path, {'messageID': message_id, 'parts': body}, timeout=30)
            except urllib.error.HTTPError as error:
                # Refused before anything was admitted (handlers/session.ts:358-359).
                if error.code in (400, 404):
                    return 'invalid' if error.code == 400 else 'gone'
                raise
            return 'queued'
        path = '/question/' + quoted(request_id) + '/reply?directory=' + quoted(directory)
        try:
            done = call(base, headers, 'POST', path, {'answers': asked['answers']})
        except urllib.error.HTTPError as error:
            # QuestionNotFoundError: no longer open, nothing changed.
            if error.code == 404:
                return 'not_open'
            raise
        return 'answered' if done is True else 'doubt'
    except Exception:
        return 'doubt'


try:
    print(main())
except Exception:
    print('local')
