"""Consumer transport proof: actual sender subprocess against isolated HTTP servers."""
import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

class SenderTransport(unittest.TestCase):
    def test_exact_admission_unknown_ack_and_redirect_boundary(self):
        with tempfile.TemporaryDirectory(prefix='herdline-send-') as home:
            root = Path(home)
            state = root / '.local/state/opencode'
            state.mkdir(parents=True)
            mode = {'value': 'admit'}
            seen = []
            class Target(http.server.BaseHTTPRequestHandler):
                def do_GET(self):
                    seen.append(('target', 'GET'))
                    self.respond({'data': {'id': 'ses_test'}})
                def do_POST(self):
                    seen.append(('target', 'POST'))
                    self.rfile.read(int(self.headers['Content-Length']))
                    self.respond({'data': {'sessionID': 'ses_test', 'id': 'msg_test'}})
                def respond(self, value):
                    data = json.dumps(value).encode()
                    self.send_response(200)
                    self.send_header('Content-Length', str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                def log_message(self, *args): pass
            target = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Target)
            class Service(Target):
                def do_GET(self):
                    seen.append(('service', 'GET'))
                    if mode['value'] == 'redirect':
                        self.send_response(302)
                        self.send_header('Location', 'http://127.0.0.1:%d/api/session/ses_test' % target.server_port)
                        self.end_headers()
                    else:
                        self.respond({'data': {'id': 'ses_test'}})
                def do_POST(self):
                    seen.append(('service', 'POST'))
                    self.rfile.read(int(self.headers['Content-Length']))
                    if mode['value'] == 'unknown':
                        self.respond({'data': {'sessionID': 'ses_test', 'id': 'msg_other'}})
                    else:
                        self.respond({'data': {'sessionID': 'ses_test', 'id': 'msg_test'}})
            service = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Service)
            threads = []
            for server in (target, service):
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                threads.append(thread)
            env = {'HOME': home, 'PATH': '/usr/bin:/bin', 'XDG_STATE_HOME': str(root / '.local/state')}
            pane = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'], env=env)
            registration = state / 'service.json'
            registration.write_text(json.dumps({'url': 'http://127.0.0.1:%d' % service.server_port, 'pid': os.getpid(), 'password': 'test-only'}))
            registration.chmod(0o600)
            script = Path(__file__).with_name('opencode_send.py')
            def send():
                return subprocess.check_output([sys.executable, str(script), str(pane.pid), 'ses_test', json.dumps({'id': 'msg_test', 'text': 'isolated'})], env=env, text=True, timeout=10).strip()
            try:
                self.assertEqual(send(), 'queued')
                self.assertEqual(seen, [('service', 'GET'), ('service', 'POST')])
                seen.clear()
                mode['value'] = 'unknown'
                self.assertEqual(send(), 'doubt')
                self.assertEqual(seen, [('service', 'GET'), ('service', 'POST')])
                seen.clear()
                mode['value'] = 'redirect'
                self.assertEqual(send(), 'local', 'redirected discovery must never authorize a prompt')
                self.assertEqual(seen, [('service', 'GET')], 'no request may escape the explicitly registered endpoint')
            finally:
                pane.terminate()
                pane.wait(timeout=5)
                for server in (target, service):
                    server.shutdown()
                    server.server_close()
                for thread in threads:
                    thread.join(timeout=5)

if __name__ == '__main__':
    unittest.main()
