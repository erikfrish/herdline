"""Consumer transport proof: the actual control script against isolated HTTP servers."""
import base64
import hashlib
import http.server
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import unittest

SCRIPT = Path(__file__).with_name('opencode_ctl.py')
PERMISSION = 'per_test'


def run(home, flavor, op, payload='{}', root=None):
    # No XDG_STATE_HOME: discovery uses the defaults under HOME only. The
    # pane's root process is this test's parent, so the servers registered
    # under this test process are the pane's own, unless `root` says else.
    env = {'HOME': home, 'PATH': '/usr/bin:/bin', 'HERDLINE_PANE_ROOT': str(os.getppid() if root is None else root)}
    return subprocess.check_output([sys.executable, '-B', str(SCRIPT), flavor, op, 'ses_test', payload], env=env, text=True, timeout=10).strip()


def sleeper():
    """A live child of this test process: this process is not under it."""
    return subprocess.Popen(['sleep', '30'], stdin=subprocess.DEVNULL)


class Server:
    """One fake server; `routes` maps (method, path) to (status, body)."""

    def __init__(self, routes):
        self.routes = routes
        self.seen = []
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def handle_one(self, method):
                length = int(self.headers.get('Content-Length') or 0)
                body = json.loads(self.rfile.read(length)) if length else None
                outer.seen.append((method, self.path, body, self.headers.get('Authorization')))
                status, value = outer.routes.get((method, self.path), (404, {'name': 'NotFound'}))
                if callable(value):
                    status, value = value()
                data = b'' if value is None else json.dumps(value).encode()
                self.send_response(status)
                if status in (301, 302):
                    self.send_header('Location', value)
                    data = b''
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                self.handle_one('GET')

            def do_POST(self):
                self.handle_one('POST')

            def do_DELETE(self):
                self.handle_one('DELETE')

            def do_PATCH(self):
                self.handle_one('PATCH')

            def log_message(self, *args):
                pass

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = 'http://127.0.0.1:%d' % self.server.server_port

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


def register(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value))
    path.chmod(0o600)


class OpenCode(unittest.TestCase):
    def test_exact_admission_unknown_ack_and_redirect_boundary(self):
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            target = Server({('GET', '/api/session/ses_test'): (200, {'data': {'id': 'ses_test'}})})
            service = Server({
                ('GET', '/api/session/ses_test'): (200, {'data': {'id': 'ses_test'}}),
                ('GET', '/api/session/active'): (200, {'data': {}}),
                ('POST', '/api/session/ses_test/prompt'): (200, {'data': {'sessionID': 'ses_test', 'id': 'msg_test'}}),
            })
            try:
                register(Path(home) / '.local/state/opencode/service.json', {'url': service.url, 'pid': os.getpid(), 'password': 'test-only'})
                body = json.dumps({'id': 'msg_test', 'text': 'isolated'})
                self.assertEqual(run(home, 'opencode', 'send', body), 'queued')
                self.assertEqual([(m, p) for m, p, _, _ in service.seen], [('GET', '/api/session/ses_test'), ('GET', '/api/session/active'), ('POST', '/api/session/ses_test/prompt')])
                service.seen.clear()
                service.routes[('POST', '/api/session/ses_test/prompt')] = (200, {'data': {'sessionID': 'ses_test', 'id': 'msg_other'}})
                self.assertEqual(run(home, 'opencode', 'send', body), 'doubt')
                service.seen.clear()
                service.routes[('GET', '/api/session/ses_test')] = (302, target.url + '/api/session/ses_test')
                self.assertEqual(run(home, 'opencode', 'send', body), 'local', 'redirected discovery must never authorize a prompt')
                self.assertEqual([m for m, _, _, _ in service.seen], ['GET'])
                self.assertEqual(target.seen, [], 'no request may escape the explicitly registered endpoint')
            finally:
                target.close()
                service.close()

    def test_children_follow_only_the_returned_cursor_to_an_empty_page(self):
        def kid(n, parent='ses_test'):
            return {'id': 'ses_k%02d' % n, 'parentID': parent, 'title': 'Kid %d' % n, 'status': 'ignored'}

        def pages(total):
            # Opaque cursors the fake made; a short last page still has next.
            routes, query, at = {}, 'parentID=ses_test', 0
            while True:
                want = min(10, 51 - at)
                data = [kid(n) for n in range(at, min(at + want, total))]
                routes[('GET', '/api/session?%s&limit=%d' % (query, want))] = (200, {'data': data, 'cursor': {'previous': 'p', 'next': 'opaque%d' % at} if data else {}})
                if not data or at + want > 50:
                    return routes
                at += len(data)
                query = 'cursor=opaque%d' % (at - len(data))

        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            service = Server({('GET', '/api/session/ses_test'): (200, {'data': {'id': 'ses_test'}}), **pages(12)})
            try:
                register(Path(home) / '.local/state/opencode/service.json', {'url': service.url, 'pid': os.getpid(), 'password': 'test-only'})
                word, listed = run(home, 'opencode', 'children').split('\t')
                self.assertEqual(word, 'children')
                self.assertEqual(json.loads(listed), [{'id': 'ses_k%02d' % n, 'title': 'Kid %d' % n} for n in range(12)])
                # The short page's next is followed: only the empty page ends it.
                self.assertEqual(service.seen[-1][1], '/api/session?cursor=opaque10&limit=10')
                service.routes.update(pages(60))
                word, listed = run(home, 'opencode', 'children').split('\t')
                self.assertEqual((word, len(json.loads(listed))), ('children_more', 50))
                service.routes[('GET', '/api/session?parentID=ses_test&limit=10')] = (200, {'data': [kid(0, 'ses_other')], 'cursor': {'next': 'x'}})
                self.assertEqual(run(home, 'opencode', 'children'), 'local', 'another parent is no list')
            finally:
                service.close()

    def test_allow_once_and_stop_reach_only_this_session(self):
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            asked = {'id': PERMISSION, 'sessionID': 'ses_test', 'action': 'bash', 'resources': ['rm -rf build']}
            other = {'id': 'per_other', 'sessionID': 'ses_other', 'action': 'edit', 'resources': ['a.txt']}
            service = Server({
                ('GET', '/api/session/ses_test'): (200, {'data': {'id': 'ses_test'}}),
                ('GET', '/api/session/ses_test/permission'): (200, {'data': [asked, other]}),
                ('GET', '/api/session/ses_test/form'): (200, {'data': []}),
                ('POST', '/api/session/ses_test/permission/per_test/reply'): (204, None),
                ('POST', '/api/session/ses_test/interrupt'): (200, {'interrupted': True}),
            })
            try:
                register(Path(home) / '.local/state/opencode/service.json', {'url': service.url, 'pid': os.getpid()})
                word, _, payload = run(home, 'opencode', 'dialogs').partition('\t')
                self.assertEqual(word, 'dialogs')
                dialogs = json.loads(payload)
                self.assertEqual([d['dialogId'] for d in dialogs], [PERMISSION], 'another session’s request is never shown')
                field = dialogs[0]['form']['fields'][0]
                self.assertEqual([o['value'] for o in field['options']], ['once', 'always', 'reject'])
                self.assertEqual(run(home, 'opencode', 'submit', json.dumps({'dialogId': PERMISSION, 'decision': 'once'})), 'submitted')
                self.assertEqual(service.seen[-1][:3], ('POST', '/api/session/ses_test/permission/per_test/reply', {'decision': 'once'}))
                # A request that is not this session's open one is never answered.
                service.seen.clear()
                self.assertEqual(run(home, 'opencode', 'submit', json.dumps({'dialogId': 'per_other', 'decision': 'once'})), 'not_open')
                self.assertNotIn('POST', [m for m, _, _, _ in service.seen])
                self.assertEqual(run(home, 'opencode', 'submit', json.dumps({'dialogId': PERMISSION, 'decision': 'yes'})), 'invalid')
                self.assertEqual(run(home, 'opencode', 'abort'), 'aborted\ttrue')
                self.assertEqual(service.seen[-1][:2], ('POST', '/api/session/ses_test/interrupt'))
            finally:
                service.close()

    def test_inbox_queue_withdraw_and_promote_by_the_verb_the_user_chose(self):
        mine = 'msg_0b5d3c1e-8f2a-4c6d-9e7b-1a2b3c4d5e6f'
        queued = {'id': mine, 'sessionID': 'ses_test', 'type': 'user', 'delivery': 'queue', 'payload': {'text': 'next'}, 'time': {'created': 1}}
        desktop = {'id': 'msg_01JDESKTOP', 'sessionID': 'ses_test', 'type': 'user', 'delivery': 'steer', 'payload': {'text': 'from desktop'}, 'time': {'created': 2}}
        compaction = {'id': 'cmp_1', 'sessionID': 'ses_test', 'type': 'compaction', 'delivery': 'queue', 'payload': {}, 'time': {'created': 3}}
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            service = Server({
                ('GET', '/api/session/ses_test'): (200, {'data': {'id': 'ses_test'}}),
                ('GET', '/api/session/active'): (200, {'data': {'ses_test': {'type': 'running'}}}),
                ('GET', '/api/session/ses_test/inbox'): (200, {'data': [queued, desktop, compaction]}),
                ('POST', '/api/session/ses_test/prompt'): (200, {'data': {'sessionID': 'ses_test', 'id': mine}}),
                ('DELETE', '/api/session/ses_test/inbox/' + mine): (204, None),
                ('PATCH', '/api/session/ses_test/inbox/' + mine): (204, None),
            })
            try:
                register(Path(home) / '.local/state/opencode/service.json', {'url': service.url, 'pid': os.getpid()})
                # No verb while the session runs: refused before any prompt.
                self.assertEqual(run(home, 'opencode', 'send', json.dumps({'id': mine, 'text': 'x'})), 'busy')
                self.assertNotIn('POST', [m for m, _, _, _ in service.seen])
                self.assertEqual(run(home, 'opencode', 'send', json.dumps({'id': mine, 'text': 'x', 'delivery': 'queue'})), 'queued')
                self.assertEqual(service.seen[-1][2], {'id': mine, 'text': 'x', 'delivery': 'queue'})
                word, _, payload = run(home, 'opencode', 'state').partition('\t')
                self.assertEqual(word, 'state')
                self.assertEqual(json.loads(payload), {'idle': False, 'queued': [
                    {'requestId': mine[4:], 'text': 'next', 'queue': 'followUp'},
                    {'requestId': None, 'text': 'from desktop', 'queue': 'steering'},
                ]})
                # Status is the server's own: only this session counts as running,
                # and an entry not shaped as SessionActive is no answer at all.
                active = ('GET', '/api/session/active')
                service.routes[active] = (200, {'data': {'ses_other': {'type': 'running'}}})
                self.assertIs(json.loads(run(home, 'opencode', 'state').partition('\t')[2])['idle'], True)
                service.routes[active] = (200, {'data': {'ses_test': {'type': 'idle'}}})
                self.assertNotEqual(run(home, 'opencode', 'state').partition('\t')[0], 'state')
                service.routes[active] = (200, {'data': {'ses_test': {'type': 'running'}}})
                self.assertEqual(run(home, 'opencode', 'status', json.dumps({'id': mine})), 'accepted')
                # Pinned fedc6f2: PATCH delivery steer is one atomic queue->steer
                # move (core/session/inbox.ts:378-402 projectDelivery updates only
                # a row still queued; 0 rows is LifecycleConflict, rolled back,
                # 409 at server/handlers/session.ts:555-566). So a 204 is the
                # steer, even with the entry drained by the wake before any
                # re-read; it is asked once and never re-read or resent.
                inbox, patch = ('GET', '/api/session/ses_test/inbox'), ('PATCH', '/api/session/ses_test/inbox/' + mine)
                listing = service.routes[inbox]
                self.assertEqual(run(home, 'opencode', 'promote', json.dumps({'id': mine})), 'promoted\ttrue')
                self.assertEqual(service.seen[-1][:3], ('PATCH', '/api/session/ses_test/inbox/' + mine, {'delivery': 'steer'}))

                def drained():
                    service.routes[inbox] = (200, {'data': [desktop]})
                    return 204, None

                service.routes[patch] = (0, drained)
                self.assertEqual(run(home, 'opencode', 'promote', json.dumps({'id': mine})), 'promoted\ttrue')
                self.assertEqual([m for m, _, _, _ in service.seen].count('PATCH'), 2)
                # 404/409 moved nothing: false, unless the inbox already lists
                # it as a steer (an earlier steer conflicts too).
                for after, word in ((listing, 'promoted\tfalse'), ((200, {'data': [desktop]}), 'promoted\tfalse'),
                                    ((200, {'data': [dict(queued, delivery='steer'), desktop]}), 'promoted\ttrue')):
                    for code in (404, 409):
                        def refused(after=after, code=code):
                            service.routes[inbox] = after
                            return code, {'name': 'Conflict'}

                        service.routes[inbox] = listing
                        service.routes[patch] = (0, refused)
                        self.assertEqual(run(home, 'opencode', 'promote', json.dumps({'id': mine})), word)
                # Any other answer may hide the move: doubt, never retried.
                service.routes[inbox] = listing
                service.routes[patch] = (500, {'name': 'Internal'})
                self.assertEqual(run(home, 'opencode', 'promote', json.dumps({'id': mine})), 'doubt')
                service.routes[patch] = (204, None)
                service.routes[inbox] = listing
                # A cancel the inbox still lists is not a withdrawal.
                self.assertEqual(run(home, 'opencode', 'withdraw', json.dumps({'id': mine})), 'doubt')
                # Gone from the inbox after the cancel but in the history: it ran.
                service.routes[('GET', '/api/session/ses_test/inbox')] = (200, {'data': [desktop]})
                service.routes[('GET', '/api/session/ses_test/message/' + mine)] = (200, {'data': {'id': mine}})
                self.assertEqual(run(home, 'opencode', 'withdraw', json.dumps({'id': mine})), 'withdrawn\tfalse')
                self.assertEqual(run(home, 'opencode', 'status', json.dumps({'id': mine})), 'accepted')
                del service.routes[('GET', '/api/session/ses_test/message/' + mine)]
                self.assertEqual(run(home, 'opencode', 'status', json.dumps({'id': mine})), 'unknown')
                # Nothing is cancelled or moved once the entry left the queue.
                service.seen.clear()
                self.assertEqual(run(home, 'opencode', 'promote', json.dumps({'id': mine})), 'promoted\tfalse')
                self.assertNotIn('PATCH', [m for m, _, _, _ in service.seen])
                # Idle: no verb starts the turn.
                service.routes[('GET', '/api/session/active')] = (200, {'data': {}})
                self.assertEqual(run(home, 'opencode', 'send', json.dumps({'id': mine, 'text': 'x'})), 'queued')
                self.assertEqual(service.seen[-1][2], {'id': mine, 'text': 'x'})
            finally:
                service.close()

    def test_no_registration_is_no_server(self):
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            for op in ('send', 'state', 'status', 'withdraw', 'promote', 'dialogs', 'abort'):
                self.assertEqual(run(home, 'opencode', op, json.dumps({'id': 'msg_test', 'text': 'x'})), 'no_server', op)
            # A registration whose server is gone is no server either.
            register(Path(home) / '.local/state/opencode/service.json', {'url': 'http://127.0.0.1:9', 'pid': 2 ** 22 + 7})
            self.assertEqual(run(home, 'opencode', 'send', json.dumps({'id': 'msg_test', 'text': 'x'})), 'no_server')

    def test_only_the_panes_own_server_is_ever_asked(self):
        # `opencode --standalone` (its server on stdio only, no
        # registration) in one pane, another pane's `serve --service` on the
        # same data root. The service runs as this test process; the
        # standalone pane's root is a process this one does not run under.
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            service = Server({
                ('GET', '/api/session/ses_test'): (200, {'data': {'id': 'ses_test'}}),
                ('GET', '/api/session/active'): (200, {'data': {}}),
                ('POST', '/api/session/ses_test/prompt'): (200, {'data': {'sessionID': 'ses_test', 'id': 'msg_test'}}),
                ('POST', '/api/session/ses_test/interrupt'): (200, {'data': {'interrupted': True}}),
            })
            standalone = sleeper()
            daemon = None
            try:
                register(Path(home) / '.local/state/opencode/service.json', {'url': service.url, 'pid': os.getpid(), 'password': 'test-only'})
                body = json.dumps({'id': 'msg_test', 'text': 'x', 'delivery': 'steer'})
                for op in ('send', 'state', 'status', 'withdraw', 'promote', 'dialogs', 'submit', 'cancel', 'abort', 'children'):
                    self.assertEqual(run(home, 'opencode', op, body, root=standalone.pid), 'no_server', op)
                for op in ('dialogs', 'submit', 'cancel', 'abort'):
                    self.assertEqual(run(home, 'kilo', op, body, root=standalone.pid), 'no_server', op)
                # No root reported: no pane tree, no server.
                self.assertEqual(run(home, 'opencode', 'send', body, root=0), 'no_server')
                self.assertEqual(service.seen, [], 'no request may reach another pane\'s server')
                # A server started in the pane beside the
                # agent is the agent's sibling under the pane's shell (this
                # test process): the pane's own, used.
                daemon = sleeper()
                register(Path(home) / '.local/state/opencode/service.json', {'url': service.url, 'pid': daemon.pid, 'password': 'test-only'})
                self.assertEqual(run(home, 'opencode', 'send', json.dumps({'id': 'msg_test', 'text': 'x'}), root=os.getpid()), 'queued')
                self.assertIn(('POST', '/api/session/ses_test/prompt'), [(m, p) for m, p, _, _ in service.seen])
            finally:
                for process in (standalone, daemon):
                    if process is not None:
                        process.kill()
                        process.wait()
                service.close()

    def test_a_server_that_dies_or_leaves_the_pane_mid_send_is_not_sent_to(self):
        # The server passes discovery, then while the send reads it is gone
        # (killed and reaped), or its parent exits and it is re-parented out
        # of the pane's tree. The prompt never goes out.
        body = json.dumps({'id': 'msg_test', 'text': 'x'})

        def attempt(server_pid, before_post):
            with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
                def session():
                    before_post()
                    return 200, {'data': {'id': 'ses_test'}}
                service = Server({
                    ('GET', '/api/session/ses_test'): (200, session),
                    ('GET', '/api/session/active'): (200, {'data': {}}),
                    ('POST', '/api/session/ses_test/prompt'): (200, {'data': {'sessionID': 'ses_test', 'id': 'msg_test'}}),
                })
                try:
                    register(Path(home) / '.local/state/opencode/service.json', {'url': service.url, 'pid': server_pid, 'password': 'test-only'})
                    word = run(home, 'opencode', 'send', body, root=os.getpid())
                    return word, [(m, p) for m, p, _, _ in service.seen if m == 'POST']
                finally:
                    service.close()

        dying = sleeper()
        try:
            self.assertEqual(attempt(dying.pid, lambda: (dying.kill(), dying.wait())), ('no_server', []))
        finally:
            dying.kill()
            dying.wait()
        # A shell under this process, its own child the registered server.
        shell = subprocess.Popen(['sh', '-c', 'sleep 30 & echo $!; read x'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        orphan = int(shell.stdout.readline())
        try:
            self.assertEqual(attempt(orphan, lambda: (shell.stdin.close(), shell.wait())), ('no_server', []))
        finally:
            os.kill(orphan, signal.SIGKILL)
            if shell.poll() is None:
                shell.kill()
                shell.wait()
            shell.stdout.close()

    def test_a_server_execd_as_the_panes_command_is_its_own(self):
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            service = Server({
                ('GET', '/api/session/ses_test'): (200, {'data': {'id': 'ses_test'}}),
                ('GET', '/api/session/active'): (200, {'data': {}}),
                ('POST', '/api/session/ses_test/prompt'): (200, {'data': {'sessionID': 'ses_test', 'id': 'msg_test'}}),
            })
            serve = sleeper()
            try:
                register(Path(home) / '.local/state/opencode/service.json', {'url': service.url, 'pid': serve.pid, 'password': 'test-only'})
                self.assertEqual(run(home, 'opencode', 'send', json.dumps({'id': 'msg_test', 'text': 'x'}), root=serve.pid), 'queued')
            finally:
                serve.kill()
                serve.wait()
                service.close()

    def test_staged_files_go_on_the_prompt_or_the_whole_send_is_refused(self):
        # As agent_chat.rs control_step composes it: the media helper, then
        # this script, with {records, target} on fd 3.
        source = Path(__file__).with_name('opencode_media.py').read_text() + '\n' + SCRIPT.read_text()
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            folder = Path(home) / '.cache/herdline/attachments/ABCDEFGHIJ'
            folder.mkdir(parents=True)
            folder.parent.chmod(0o700)
            folder.chmod(0o700)
            png = b'\x89PNG\r\n\x1a\nfixture-pixels'
            staged = folder / 'shot.png'
            staged.write_bytes(png)
            staged.chmod(0o600)
            instance = {'pane_id': 'w1:p1', 'terminal_id': 't1', 'pid': os.getpid(), 'path': 'ses_test',
                        'agent': 'opencode', 'source': 'herdr:opencode', 'kind': 'id'}
            target = {'node': 'n', 'session': 'qa', 'boot': 'b1', 'pane': 'w1:p1', 'instance': instance}
            record = {**target, 'kind': 'image', 'name': 'shot.png', 'path': str(staged), 'size': len(png),
                      'sha256': hashlib.sha256(png).hexdigest()}
            service = Server({
                ('GET', '/api/session/ses_test'): (200, {'data': {'id': 'ses_test'}}),
                ('GET', '/api/session/active'): (200, {'data': {}}),
                ('POST', '/api/session/ses_test/prompt'): (200, {'data': {'sessionID': 'ses_test', 'id': 'msg_test'}}),
            })

            def send(records):
                # The command's stdin carries the records; the script is fd 0.
                script = Path(home) / 'ctl.py'
                script.write_text(source)
                body = json.dumps({'id': 'msg_test', 'text': 'look'})
                return subprocess.check_output(
                    ['/bin/sh', '-c', '"$0" -B - opencode send ses_test "$1" media 3<&0 < "$2"', sys.executable, body, str(script)],
                    input=json.dumps({'records': records, 'target': target}), env={'HOME': home, 'PATH': '/usr/bin:/bin', 'HERDLINE_PANE_ROOT': str(os.getppid())},
                    text=True, timeout=10).strip()

            try:
                register(Path(home) / '.local/state/opencode/service.json', {'url': service.url, 'pid': os.getpid()})
                self.assertEqual(send([record]), 'queued')
                # The session is asked again after the files are read, just before the prompt.
                self.assertEqual([(m, p) for m, p, _, _ in service.seen],
                                 [('GET', '/api/session/ses_test'), ('GET', '/api/session/active'),
                                  ('GET', '/api/session/ses_test'), ('POST', '/api/session/ses_test/prompt')])
                files = service.seen[-1][2]['files']
                self.assertEqual([(f['name'], f['source']) for f in files], [('shot.png', {'type': 'inline'})])
                self.assertEqual(base64.b64decode(files[0]['uri'].split(',', 1)[1]), png)
                # A file changed since it was staged refuses the whole send: no prompt at all.
                service.seen.clear()
                staged.write_bytes(b'\x89PNG\r\n\x1a\nother--pixels')
                self.assertEqual(send([record]), 'media\tattachment_missing')
                self.assertNotIn('POST', [m for m, _, _, _ in service.seen])
                # A record bound to another pane is never read.
                self.assertEqual(send([{**record, 'pane': 'w1:p2'}]), 'media\tattachment_target')
                self.assertNotIn('POST', [m for m, _, _, _ in service.seen])
            finally:
                service.close()


FORM = 'frm_test'


class Forms(unittest.TestCase):
    """OpenCode forms (pinned 2.2.7 schema/form.ts, groups/session.ts:722-797)."""

    def test_a_form_is_shown_whole_answered_once_and_declined_by_its_id(self):
        fields = [
            {'type': 'boolean', 'key': 'deploy', 'title': 'Deploy?', 'required': True},
            {'type': 'string', 'key': 'env', 'title': 'Where', 'options': [{'value': 'prod', 'label': 'Production', 'description': 'live'}, {'value': 'stage', 'label': 'Stage'}],
             'required': True, 'when': [{'key': 'deploy', 'op': 'eq', 'value': True}]},
            {'type': 'integer', 'key': 'count', 'minimum': 1, 'maximum': 9, 'default': 2, 'description': 'How many'},
            {'type': 'multiselect', 'key': 'tags', 'options': [{'value': 'a', 'label': 'A'}, {'value': 'b', 'label': 'B'}], 'maxItems': 1},
            {'type': 'string', 'key': 'token', 'hidden': True, 'default': 'x'},
            {'type': 'external', 'key': 'docs', 'url': 'https://example.test/d', 'title': 'Read the docs'},
        ]
        form = {'id': FORM, 'sessionID': 'ses_test', 'title': 'Ship it', 'fields': fields}
        detail = dict(form, state={'status': 'pending'})
        base = '/api/session/ses_test/form/' + FORM
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            service = Server({
                ('GET', '/api/session/ses_test'): (200, {'data': {'id': 'ses_test'}}),
                ('GET', '/api/session/ses_test/permission'): (200, {'data': []}),
                ('GET', '/api/session/ses_test/form'): (200, {'data': [form, dict(form, id='frm_other', sessionID='global')]}),
                ('GET', base): (200, {'data': detail}),
                ('POST', base + '/reply'): (204, None),
            })
            try:
                register(Path(home) / '.local/state/opencode/service.json', {'url': service.url, 'pid': os.getpid()})
                word, _, payload = run(home, 'opencode', 'dialogs').partition('\t')
                self.assertEqual(word, 'dialogs')
                [shown] = json.loads(payload)
                self.assertEqual((shown['dialogId'], shown['kind'], shown['submittable'], shown['cancellable']), (FORM, 'form', True, True), 'a global form is no session’s')
                byname = {f['name']: f for f in shown['form']['fields']}
                self.assertEqual(list(byname), ['deploy', 'env', 'count', 'tags'], 'hidden takes its default; a link is a block')
                self.assertEqual(byname['env']['when'], [{'key': 'deploy', 'op': 'eq', 'value': True}])
                self.assertEqual([o['label'] for o in byname['env']['options']][0], [{'text': 'Production'}, {'text': ' — live', 'style': 'em'}])
                self.assertEqual((byname['count']['type'], byname['count']['value'], byname['count']['min'], byname['count']['max']), ('integer', '2', 1, 9))
                self.assertEqual((byname['tags']['multi'], byname['tags']['maxItems']), (True, 1))
                self.assertIn({'kind': 'link', 'url': 'https://example.test/d', 'label': [{'text': 'Read the docs'}]}, shown['form']['sections'][0]['blocks'])

                def submit(data):
                    return run(home, 'opencode', 'submit', json.dumps({'dialogId': FORM, 'answer': data}))
                # Re-checked against the server's own fields, coerced to their types.
                self.assertEqual(submit({'deploy': 'true', 'env': 'stage', 'count': '3', 'tags': ['a']}), 'submitted')
                self.assertEqual(service.seen[-1][:3], ('POST', base + '/reply', {'answer': {'deploy': True, 'env': 'stage', 'count': 3, 'tags': ['a']}}))
                # An inactive field is not answerable: neither required nor sent.
                self.assertEqual(submit({'deploy': 'false', 'count': ''}), 'submitted')
                self.assertEqual(service.seen[-1][2], {'answer': {'deploy': False}})
                replies = [m for m, p, _, _ in service.seen].count('POST')
                for bad in ({'deploy': 'true'}, {'deploy': 'true', 'env': 'mars'}, {'deploy': 'false', 'count': '2.5'},
                            {'deploy': 'false', 'token': 'y'}, {'deploy': 'false', 'docs': 'x'}, {'deploy': 'maybe'}):
                    self.assertEqual(submit(bad), 'invalid', bad)
                self.assertEqual([m for m, p, _, _ in service.seen].count('POST'), replies, 'an invalid answer is never sent')
                # Settled before the phone asked: said, never sent.
                service.routes[('GET', base)] = (200, {'data': dict(form, state={'status': 'answered', 'answer': {}})})
                self.assertEqual(submit({'deploy': 'false'}), 'answered')
                self.assertEqual(run(home, 'opencode', 'cancel', json.dumps({'dialogId': FORM})), 'answered')
                service.routes[('GET', base)] = (404, {'name': 'FormNotFound'})
                self.assertEqual(submit({'deploy': 'false'}), 'not_open')
                self.assertEqual([m for m, p, _, _ in service.seen].count('POST'), replies)
                # Refused at the reply: the re-read says what holds.
                service.routes[('GET', base)] = (200, {'data': detail})
                service.routes[('POST', base + '/reply')] = (0, lambda: (service.routes.__setitem__(('GET', base), (200, {'data': dict(form, state={'status': 'answered', 'answer': {}})})), (409, {'name': 'FormAlreadySettled'}))[1])
                self.assertEqual(submit({'deploy': 'false'}), 'answered')
                service.routes[('GET', base)] = (200, {'data': detail})
                service.routes[('POST', base + '/reply')] = (500, {'name': 'Internal'})
                self.assertEqual(submit({'deploy': 'false'}), 'doubt')
                # Decline is the DELETE with a reason, confirmed by the state.
                settle = lambda: (service.routes.__setitem__(('GET', base), (200, {'data': dict(form, state={'status': 'cancelled'})})), (204, None))[1]
                service.routes[('DELETE', base + '?message=Declined%20in%20Herdline%20Chat')] = (0, settle)
                self.assertEqual(run(home, 'opencode', 'cancel', json.dumps({'dialogId': FORM})), 'cancelled')
                self.assertEqual(service.seen[-2][:2], ('DELETE', base + '?message=Declined%20in%20Herdline%20Chat'))
                self.assertEqual(run(home, 'opencode', 'cancel', json.dumps({'dialogId': FORM})), 'not_open')
                service.routes[('GET', base)] = (200, {'data': detail})
                service.routes[('DELETE', base + '?message=Declined%20in%20Herdline%20Chat')] = (204, None)
                self.assertEqual(run(home, 'opencode', 'cancel', json.dumps({'dialogId': FORM})), 'doubt', 'a 204 that left it pending is no decline')
                # A permission is never declined by cancel; an id of no kind is refused.
                self.assertEqual(run(home, 'opencode', 'cancel', json.dumps({'dialogId': PERMISSION})), 'invalid')
                self.assertEqual(run(home, 'opencode', 'submit', json.dumps({'dialogId': 'que_x', 'answer': {}})), 'invalid')
            finally:
                service.close()

    def test_a_form_chat_cannot_show_whole_is_refused_in_words(self):
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            service = Server({
                ('GET', '/api/session/ses_test'): (200, {'data': {'id': 'ses_test'}}),
                ('GET', '/api/session/ses_test/permission'): (200, {'data': []}),
            })
            try:
                register(Path(home) / '.local/state/opencode/service.json', {'url': service.url, 'pid': os.getpid()})
                cases = [
                    ([{'type': 'color', 'key': 'c', 'title': 'Colour'}], {'reason': 'field', 'subject': 'Colour'}),
                    ([{'type': 'string', 'key': 's', 'format': 'ipv4'}], {'reason': 'field', 'subject': 's'}),
                    ([{'type': 'string', 'key': 'h', 'hidden': True}, {'type': 'string', 'key': 'x', 'title': 'X', 'when': [{'key': 'h', 'op': 'eq', 'value': 'a'}]}], {'reason': 'condition', 'subject': 'X'}),
                    ([{'type': 'external', 'key': 'e', 'url': 'file:///etc/passwd', 'title': 'Open'}], {'reason': 'link', 'subject': 'Open'}),
                    ([{'type': 'string', 'key': 'k%d' % i, 'description': 'd' * 400} for i in range(40)], {'reason': 'size', 'subject': ''}),
                ]
                for fields, why in cases:
                    form = {'id': FORM, 'sessionID': 'ses_test', 'title': 't', 'fields': fields}
                    service.routes[('GET', '/api/session/ses_test/form')] = (200, {'data': [form]})
                    service.routes[('GET', '/api/session/ses_test/form/' + FORM)] = (200, {'data': dict(form, state={'status': 'pending'})})
                    [shown] = json.loads(run(home, 'opencode', 'dialogs').partition('\t')[2])
                    self.assertEqual((shown['submittable'], 'form' in shown, shown['cancellable'], shown['unsupported']), (False, False, True, why))
                    self.assertEqual(run(home, 'opencode', 'submit', json.dumps({'dialogId': FORM, 'answer': {}})), 'invalid')
                self.assertNotIn('POST', [m for m, _, _, _ in service.seen])
            finally:
                service.close()


class Kilo(unittest.TestCase):
    def test_daemon_permission_reply_and_abort(self):
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            directory = '/srv/project'
            asked = {'id': PERMISSION, 'sessionID': 'ses_test', 'permission': 'bash', 'patterns': ['make'], 'metadata': {}, 'always': ['make *']}
            query = '?directory=%2Fsrv%2Fproject'
            daemon = Server({
                ('GET', '/session/ses_test'): (200, {'id': 'ses_test', 'directory': directory}),
                ('GET', '/permission' + query): (200, [asked, dict(asked, id='per_other', sessionID='ses_other')]),
                ('GET', '/question' + query): (200, []),
                ('POST', '/permission/per_test/reply' + query): (200, True),
                ('POST', '/session/ses_test/abort'): (200, True),
            })
            try:
                register(Path(home) / '.local/state/kilo/daemon.json', {'pid': os.getpid(), 'hostname': '127.0.0.1', 'port': daemon.server.server_port, 'url': daemon.url, 'username': 'kilo', 'password': 'test-only'})
                word, _, payload = run(home, 'kilo', 'dialogs').partition('\t')
                self.assertEqual(word, 'dialogs')
                self.assertEqual([d['dialogId'] for d in json.loads(payload)], [PERMISSION])
                self.assertEqual(run(home, 'kilo', 'submit', json.dumps({'dialogId': PERMISSION, 'decision': 'once'})), 'submitted')
                self.assertEqual(daemon.seen[-1][:3], ('POST', '/permission/per_test/reply' + query, {'reply': 'once', 'interactive': True}))
                self.assertEqual(daemon.seen[-1][3], 'Basic a2lsbzp0ZXN0LW9ubHk=')
                self.assertEqual(run(home, 'kilo', 'abort'), 'aborted\ttrue')
                # Both default locations registered: ambiguous, refused.
                register(Path(home) / '.local/share/kilo/state/daemon.json', {'pid': os.getpid(), 'url': daemon.url, 'username': 'kilo', 'password': 'x'})
                daemon.seen.clear()
                self.assertEqual(run(home, 'kilo', 'abort'), 'discovery')
                self.assertEqual(daemon.seen, [])
                # Kilo's text and checks are not asked of its server here.
                self.assertEqual(run(home, 'kilo', 'send', json.dumps({'id': 'msg_test', 'text': 'x'})), 'local')
                self.assertEqual(daemon.seen, [])
            finally:
                daemon.close()

    def test_a_question_is_answered_with_its_labels_and_rejected_by_its_id(self):
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            query = '?directory=%2Fsrv%2Fproject'
            asked = {'id': 'que_test', 'sessionID': 'ses_test', 'questions': [
                {'question': 'Which database?', 'header': 'Database', 'options': [{'label': 'Postgres', 'description': 'default'}, {'label': 'SQLite', 'description': 'local'}], 'default': 'SQLite'},
                {'question': 'Extras?', 'header': 'Extras', 'options': [{'label': 'Cache', 'description': ''}], 'multiple': True, 'custom': False},
            ]}
            daemon = Server({
                ('GET', '/session/ses_test'): (200, {'id': 'ses_test', 'directory': '/srv/project'}),
                ('GET', '/permission' + query): (200, []),
                ('GET', '/question' + query): (200, [asked, dict(asked, id='que_other', sessionID='ses_other')]),
                ('POST', '/question/que_test/reply' + query): (200, True),
                ('POST', '/question/que_test/reject' + query): (200, True),
            })
            try:
                register(Path(home) / '.local/state/kilo/daemon.json', {'pid': os.getpid(), 'url': daemon.url, 'username': 'kilo', 'password': 'test-only'})
                [shown] = json.loads(run(home, 'kilo', 'dialogs').partition('\t')[2])
                self.assertEqual((shown['dialogId'], shown['kind'], shown['submittable'], shown['cancellable'], shown['title']), ('que_test', 'form', True, True, 'Database'))
                first, second = shown['form']['fields']
                self.assertEqual((first['name'], first['multi'], first['other'], first['help']), ('q0', False, True, [{'text': 'Which database?'}]))
                self.assertEqual([(o['value'], o['checked']) for o in first['options']], [('Postgres', False), ('SQLite', True)])
                self.assertEqual((second['multi'], second['other']), (True, False))
                answer = lambda data: run(home, 'kilo', 'submit', json.dumps({'dialogId': 'que_test', 'answer': data}))
                self.assertEqual(answer({'q0': 'MySQL, my own', 'q1': ['Cache']}), 'submitted')
                self.assertEqual(daemon.seen[-1][:3], ('POST', '/question/que_test/reply' + query, {'answers': [['MySQL, my own'], ['Cache']]}))
                sent = len(daemon.seen)
                for bad in ({'q0': 'Postgres'}, {'q0': ['Postgres', 'SQLite'], 'q1': ['Cache']}, {'q0': 'Postgres', 'q1': ['Other']}, {'q0': 'Postgres', 'q1': [], 'q2': 'x'}):
                    self.assertEqual(answer(bad), 'invalid', bad)
                self.assertNotIn('POST', [m for m, _, _, _ in daemon.seen[sent:]])
                # Five permissions ahead of it: the question past them is marked, never hidden.
                daemon.routes[('GET', '/permission' + query)] = (200, [{'id': 'per_%d' % n, 'sessionID': 'ses_test', 'permission': 'bash', 'patterns': ['make'], 'metadata': {}, 'always': []} for n in range(5)])
                word, _, payload = run(home, 'kilo', 'dialogs').partition('\t')
                self.assertEqual((word, [d['dialogId'] for d in json.loads(payload)]), ('dialogs_more', ['per_%d' % n for n in range(5)]))
                daemon.routes[('GET', '/permission' + query)] = (200, [])
                self.assertEqual(run(home, 'kilo', 'dialogs').partition('\t')[0], 'dialogs')
                self.assertEqual(run(home, 'kilo', 'cancel', json.dumps({'dialogId': 'que_test'})), 'cancelled')
                self.assertEqual(daemon.seen[-1][:2], ('POST', '/question/que_test/reject' + query))
                daemon.routes[('POST', '/question/que_test/reject' + query)] = (404, {'name': 'QuestionNotFound'})
                self.assertEqual(run(home, 'kilo', 'cancel', json.dumps({'dialogId': 'que_test'})), 'not_open')
                daemon.routes[('POST', '/question/que_test/reply' + query)] = (200, False)
                self.assertEqual(answer({'q0': 'SQLite', 'q1': ['Cache']}), 'doubt')
                daemon.routes[('GET', '/question' + query)] = (200, [])
                self.assertEqual(answer({'q0': 'SQLite', 'q1': ['Cache']}), 'not_open')
                self.assertEqual(run(home, 'kilo', 'cancel', json.dumps({'dialogId': 'frm_x'})), 'invalid')
            finally:
                daemon.close()

    def test_a_registration_others_can_read_is_refused(self):
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            file = Path(home) / '.local/state/kilo/daemon.json'
            register(file, {'pid': os.getpid(), 'url': 'http://127.0.0.1:9', 'username': 'kilo', 'password': 'x'})
            file.chmod(0o644)
            self.assertEqual(run(home, 'kilo', 'abort'), 'discovery')


if __name__ == '__main__':
    unittest.main()
