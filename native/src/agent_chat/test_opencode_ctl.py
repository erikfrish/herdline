"""Consumer transport proof: the actual control script against isolated HTTP servers."""
import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

SCRIPT = Path(__file__).with_name('opencode_ctl.py')
PERMISSION = 'per_test'


def run(home, flavor, op, payload='{}'):
    # No XDG_STATE_HOME: discovery uses the defaults under HOME only.
    env = {'HOME': home, 'PATH': '/usr/bin:/bin'}
    return subprocess.check_output([sys.executable, '-B', str(SCRIPT), flavor, op, 'ses_test', payload], env=env, text=True, timeout=10).strip()


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
                ('POST', '/api/session/ses_test/prompt'): (200, {'data': {'sessionID': 'ses_test', 'id': 'msg_test'}}),
            })
            try:
                register(Path(home) / '.local/state/opencode/service.json', {'url': service.url, 'pid': os.getpid(), 'password': 'test-only'})
                body = json.dumps({'id': 'msg_test', 'text': 'isolated'})
                self.assertEqual(run(home, 'opencode', 'send', body), 'queued')
                self.assertEqual([(m, p) for m, p, _, _ in service.seen], [('GET', '/api/session/ses_test'), ('POST', '/api/session/ses_test/prompt')])
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

    def test_allow_once_and_stop_reach_only_this_session(self):
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            asked = {'id': PERMISSION, 'sessionID': 'ses_test', 'action': 'bash', 'resources': ['rm -rf build']}
            other = {'id': 'per_other', 'sessionID': 'ses_other', 'action': 'edit', 'resources': ['a.txt']}
            service = Server({
                ('GET', '/api/session/ses_test'): (200, {'data': {'id': 'ses_test'}}),
                ('GET', '/api/session/ses_test/permission'): (200, {'data': [asked, other]}),
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

    def test_no_registration_is_no_server(self):
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            self.assertEqual(run(home, 'opencode', 'dialogs'), 'no_server')
            self.assertEqual(run(home, 'opencode', 'abort'), 'no_server')
            # A send keeps its old answer for an unreachable service.
            self.assertEqual(run(home, 'opencode', 'send', json.dumps({'id': 'msg_test', 'text': 'x'})), 'local')
            # A registration whose server is gone is no server either.
            register(Path(home) / '.local/state/opencode/service.json', {'url': 'http://127.0.0.1:9', 'pid': 2 ** 22 + 7})
            self.assertEqual(run(home, 'opencode', 'abort'), 'no_server')


class Kilo(unittest.TestCase):
    def test_daemon_permission_reply_and_abort(self):
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            directory = '/srv/project'
            asked = {'id': PERMISSION, 'sessionID': 'ses_test', 'permission': 'bash', 'patterns': ['make'], 'metadata': {}, 'always': ['make *']}
            query = '?directory=%2Fsrv%2Fproject'
            daemon = Server({
                ('GET', '/session/ses_test'): (200, {'id': 'ses_test', 'directory': directory}),
                ('GET', '/permission' + query): (200, [asked, dict(asked, id='per_other', sessionID='ses_other')]),
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
                self.assertEqual(run(home, 'kilo', 'abort'), 'local')
                self.assertEqual(daemon.seen, [])
            finally:
                daemon.close()

    def test_a_registration_others_can_read_is_refused(self):
        with tempfile.TemporaryDirectory(prefix='herdline-ctl-') as home:
            file = Path(home) / '.local/state/kilo/daemon.json'
            register(file, {'pid': os.getpid(), 'url': 'http://127.0.0.1:9', 'username': 'kilo', 'password': 'x'})
            file.chmod(0o644)
            self.assertEqual(run(home, 'kilo', 'abort'), 'local')


if __name__ == '__main__':
    unittest.main()
