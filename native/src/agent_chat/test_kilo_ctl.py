"""Consumer transport proof: kilo_ctl.py against an isolated HTTP server
shaped as Kilo v7.8.27's v1 routes (see kilo_ctl.py for each file:line)."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from test_opencode_ctl import Server, register, sleeper

SCRIPT = Path(__file__).with_name('kilo_ctl.py')
DIRECTORY = '/srv/project'
QUERY = '?directory=%2Fsrv%2Fproject'
SESSION = ('GET', '/session/ses_test')


def run(home, op, payload='{}', root=None):
    # No XDG_STATE_HOME: discovery uses the defaults under HOME only. The
    # pane's root process is this test's parent, so the daemon registered
    # as this test process is the pane's own, unless `root` says else.
    env = {'HOME': home, 'PATH': '/usr/bin:/bin', 'HERDLINE_PANE_ROOT': str(os.getppid() if root is None else root)}
    return subprocess.check_output([sys.executable, '-B', str(SCRIPT), op, 'ses_test', payload], env=env, text=True, timeout=10).strip()


class Daemon(unittest.TestCase):
    def serve(self, routes):
        """A registered Kilo daemon knowing ses_test in DIRECTORY."""
        home = tempfile.TemporaryDirectory(prefix='herdline-kilo-')
        self.addCleanup(home.cleanup)
        server = Server({SESSION: (200, {'id': 'ses_test', 'directory': DIRECTORY}), **routes})
        self.addCleanup(server.close)
        register(Path(home.name) / '.local/state/kilo/daemon.json', {'pid': os.getpid(), 'url': server.url, 'username': 'kilo', 'password': 'test-only'})
        return home.name, server

    def posts(self, server):
        return [seen[:3] for seen in server.seen if seen[0] == 'POST']


class Send(Daemon):
    # session.ts:361 prompt_async; schema/src/v1/session.ts:442-466 parts.
    def test_an_idle_session_takes_text_and_never_a_path_from_the_payload(self):
        home, server = self.serve({
            ('GET', '/session/status' + QUERY): (200, {'ses_other': {'type': 'busy'}}),
            ('POST', '/session/ses_test/prompt_async'): (204, None),
        })
        # A path in the payload authorizes nothing: only fd 3's staged records do.
        sent = {'id': 'msg_one', 'text': 'look', 'files': [{'path': '/etc/passwd', 'mime': 'text/plain'}]}
        self.assertEqual(run(home, 'send', json.dumps(sent)), 'queued')
        self.assertEqual(self.posts(server), [('POST', '/session/ses_test/prompt_async', {'messageID': 'msg_one', 'parts': [
            {'type': 'text', 'text': 'look'},
        ]})])
        self.assertEqual(server.seen[-1][3], 'Basic a2lsbzp0ZXN0LW9ubHk=')

    def test_staged_files_go_as_data_parts_or_the_whole_send_is_refused(self):
        # As agent_chat.rs kilo_step composes it: the media helper, then
        # this script, with {records, target} on fd 3.
        source = SCRIPT.with_name('opencode_media.py').read_text() + '\n' + SCRIPT.read_text()
        home, server = self.serve({
            ('GET', '/session/status' + QUERY): (200, {}),
            ('POST', '/session/ses_test/prompt_async'): (204, None),
        })
        folder = Path(home) / '.cache/herdline/attachments/ABCDEFGHIJ'
        folder.mkdir(parents=True)
        folder.parent.chmod(0o700)
        folder.chmod(0o700)
        png = b'\x89PNG\r\n\x1a\nfixture-pixels'
        staged = folder / 'shot.png'
        staged.write_bytes(png)
        staged.chmod(0o600)
        instance = {'pane_id': 'w1:p1', 'terminal_id': 't1', 'pid': os.getpid(), 'path': 'ses_test',
                    'agent': 'kilo', 'source': 'herdr:kilo', 'kind': 'id'}
        target = {'node': 'n', 'session': 'qa', 'boot': 'b1', 'pane': 'w1:p1', 'instance': instance}
        record = {**target, 'kind': 'image', 'name': 'shot.png', 'path': str(staged), 'size': len(png),
                  'sha256': hashlib.sha256(png).hexdigest()}
        script = Path(home) / 'ctl.py'
        script.write_text(source)

        def send(records, bound=target):
            body = json.dumps({'id': 'msg_one', 'text': 'look'})
            return subprocess.check_output(
                ['/bin/sh', '-c', '"$0" -B - send ses_test "$1" media 3<&0 < "$2"', sys.executable, body, str(script)],
                input=json.dumps({'records': records, 'target': bound}), env={'HOME': home, 'PATH': '/usr/bin:/bin', 'HERDLINE_PANE_ROOT': str(os.getppid())},
                text=True, timeout=10).strip()

        self.assertEqual(send([record]), 'queued')
        # The session and its idleness are asked again after the files are read.
        self.assertEqual([(m, p) for m, p, _, _ in server.seen],
                         [SESSION, ('GET', '/session/status' + QUERY), SESSION, ('GET', '/session/status' + QUERY),
                          ('POST', '/session/ses_test/prompt_async')])
        image = {'type': 'file', 'mime': 'image/png', 'filename': 'shot.png',
                 'url': 'data:image/png;base64,' + base64.b64encode(png).decode()}
        self.assertEqual(server.seen[-1][2]['parts'], [{'type': 'text', 'text': 'look'}, image])
        # Kilo's synchronous 400 for an attachment refuses the whole send in words.
        server.routes[('POST', '/session/ses_test/prompt_async')] = (400, {'_tag': 'InvalidRequestError', 'message': 'x', 'kind': 'attachment'})
        self.assertEqual(send([record]), 'media\tunsupported_type')
        server.routes[('POST', '/session/ses_test/prompt_async')] = (204, None)
        server.seen.clear()
        # A record for another pane, a target that is not Kilo, a file
        # changed since staging: no prompt at all.
        self.assertEqual(send([{**record, 'pane': 'w1:p2'}]), 'media\tattachment_target')
        self.assertEqual(send([{**record, 'instance': {**instance, 'agent': 'opencode'}}],
                              {**target, 'instance': {**instance, 'agent': 'opencode'}}), 'media\tattachment_needs_agent')
        staged.write_bytes(b'\x89PNG\r\n\x1a\nother--pixels')
        self.assertEqual(send([record]), 'media\tattachment_missing')
        self.assertEqual(self.posts(server), [])

    def test_only_a_daemon_in_the_panes_own_tree_is_asked(self):
        # The pane's-own-server rule for Kilo: daemon.json names a server outside the
        # pane's tree (this test process, under another root), so every op
        # is no_server, unasked.
        home, server = self.serve({
            ('GET', '/session/status' + QUERY): (200, {}),
            ('POST', '/session/ses_test/prompt_async'): (204, None),
        })
        other = sleeper()
        self.addCleanup(other.wait)
        self.addCleanup(other.kill)
        body = json.dumps({'id': 'msg_one', 'text': 'x'})
        for op in ('send', 'status', 'state', 'withdraw', 'children'):
            self.assertEqual(run(home, op, body, root=other.pid), 'no_server', op)
        self.assertEqual(server.seen, [])
        # `kilo daemon start --foreground &`, then `kilo`,
        # in one pane: the daemon is the TUI's sibling under the pane's shell
        # (this test process), not its child, and is the pane's own.
        tui, daemon = sleeper(), sleeper()
        for process in (tui, daemon):
            self.addCleanup(process.wait)
            self.addCleanup(process.kill)
        register(Path(home) / '.local/state/kilo/daemon.json', {'pid': daemon.pid, 'url': server.url, 'username': 'kilo', 'password': 'test-only'})
        self.assertEqual(run(home, 'send', body, root=os.getpid()), 'queued')
        self.assertEqual(self.posts(server), [('POST', '/session/ses_test/prompt_async', {'messageID': 'msg_one', 'parts': [
            {'type': 'text', 'text': 'x'},
        ]})])

    # schema/src/session-status-event.ts:11-47: busy, retry, offline,
    # scheduled are all at work or waiting; only idle starts a turn.
    def test_a_session_not_idle_is_never_sent_a_prompt(self):
        for kind in ('busy', 'retry', 'offline', 'scheduled'):
            with self.subTest(kind):
                home, server = self.serve({('GET', '/session/status' + QUERY): (200, {'ses_test': {'type': kind}})})
                self.assertEqual(run(home, 'send', json.dumps({'id': 'msg_one', 'text': 'x'})), 'busy')
                self.assertEqual(self.posts(server), [])

    def test_files_without_text_an_unshaped_id_or_nothing_to_send_asks_nothing(self):
        home, server = self.serve({('GET', '/session/status' + QUERY): (200, {})})
        for sent in ({'id': 'msg_one', 'files': [{'path': '/srv/a.png', 'mime': 'image/png'}]},
                     {'id': 'one', 'text': 'x'},
                     {'id': 'msg_one', 'text': ''}):
            with self.subTest(sent):
                self.assertEqual(run(home, 'send', json.dumps(sent)), 'invalid')
        self.assertEqual(self.posts(server), [])

    # handlers/session.ts:315-324, 358-359: a refused attachment is a 400
    # before the prompt forks; nothing was admitted.
    def test_a_synchronous_refusal_is_not_a_doubt_but_a_dropped_reply_is(self):
        home, server = self.serve({
            ('GET', '/session/status' + QUERY): (200, {}),
            ('POST', '/session/ses_test/prompt_async'): (400, {'name': 'InvalidRequestError'}),
        })
        self.assertEqual(run(home, 'send', json.dumps({'id': 'msg_one', 'text': 'x'})), 'invalid')
        server.routes[('POST', '/session/ses_test/prompt_async')] = (500, {'name': 'Unknown'})
        self.assertEqual(run(home, 'send', json.dumps({'id': 'msg_one', 'text': 'x'})), 'doubt')


class Steer(Daemon):
    # prompt.ts:1430-1436, 1505-1515; prompt-queue.ts:213-288: a prompt to a
    # session at work waits for the turn's next step boundary.
    def test_a_steer_goes_into_a_turn_at_work_and_never_starts_one(self):
        steer = json.dumps({'id': 'msg_one', 'text': 'look', 'delivery': 'steer'})
        for kind in ('busy', 'retry', 'offline', 'scheduled'):
            with self.subTest(kind):
                home, server = self.serve({
                    ('GET', '/session/status' + QUERY): (200, {'ses_test': {'type': kind}}),
                    ('POST', '/session/ses_test/prompt_async'): (204, None),
                })
                self.assertEqual(run(home, 'send', steer), 'queued')
                self.assertEqual(self.posts(server), [('POST', '/session/ses_test/prompt_async', {'messageID': 'msg_one', 'parts': [
                    {'type': 'text', 'text': 'look'},
                ]})])
        # An idle session (absent from the map is idle) would take it as a turn of its own: refused, nothing sent.
        for listed in ({'ses_test': {'type': 'idle'}}, {}):
            with self.subTest(listed):
                home, server = self.serve({
                    ('GET', '/session/status' + QUERY): (200, listed),
                    ('POST', '/session/ses_test/prompt_async'): (204, None),
                })
                self.assertEqual(run(home, 'send', steer), 'idle')
                self.assertEqual(self.posts(server), [])

    def test_a_delivery_kilo_has_not_is_never_sent(self):
        home, server = self.serve({
            ('GET', '/session/status' + QUERY): (200, {'ses_test': {'type': 'busy'}}),
            ('POST', '/session/ses_test/prompt_async'): (204, None),
        })
        for delivery in ('queue', 'followUp', None, 1):
            with self.subTest(delivery):
                self.assertEqual(run(home, 'send', json.dumps({'id': 'msg_one', 'text': 'x', 'delivery': delivery})), 'local')
        # Only the session was read: not even its status was asked.
        self.assertEqual({(m, p) for m, p, _, _ in server.seen}, {SESSION})

    def test_a_dropped_steer_reply_is_a_doubt_never_a_resend(self):
        home, server = self.serve({
            ('GET', '/session/status' + QUERY): (200, {'ses_test': {'type': 'busy'}}),
            ('POST', '/session/ses_test/prompt_async'): (500, {'name': 'Unknown'}),
        })
        self.assertEqual(run(home, 'send', json.dumps({'id': 'msg_one', 'text': 'x', 'delivery': 'steer'})), 'doubt')
        self.assertEqual(len(self.posts(server)), 1)


class Withdraw(Daemon):
    # session.ts:441-453, handlers/session.ts:449-462: queued=true answers
    # whether the prompt was still waiting and is now dropped.
    DELETE = ('DELETE', '/session/ses_test/message/msg_one' + QUERY + '&queued=true')

    def deletes(self, server):
        return [seen[:2] for seen in server.seen if seen[0] == 'DELETE']

    def test_only_kilos_own_boolean_says_what_became_of_the_steer(self):
        for answer, word in ((True, 'withdrawn\ttrue'), (False, 'withdrawn\tfalse')):
            with self.subTest(answer):
                home, server = self.serve({self.DELETE: (200, answer)})
                self.assertEqual(run(home, 'withdraw', json.dumps({'id': 'msg_one'})), word)
                self.assertEqual(self.deletes(server), [self.DELETE])
        # An error or any other body may hide a drop: doubt, never "gone" or "kept".
        for reply in ((500, {'name': 'Unknown'}), (404, {'name': 'NotFound'}), (200, None), (200, 'true'), (200, {'ok': True})):
            with self.subTest(reply):
                home, server = self.serve({self.DELETE: reply})
                self.assertEqual(run(home, 'withdraw', json.dumps({'id': 'msg_one'})), 'doubt')

    def test_an_id_not_this_apps_is_never_deleted(self):
        home, server = self.serve({})
        for sent in ({'id': 'one'}, {'id': 7}, {}):
            with self.subTest(sent):
                self.assertEqual(run(home, 'withdraw', json.dumps(sent)), 'local')
        self.assertEqual(self.deletes(server), [])


class Status(Daemon):
    # session.ts:223 message -> {info, parts}; 404 = never stored.
    def test_a_stored_message_is_accepted_and_a_missing_one_unknown(self):
        home, _ = self.serve({('GET', '/session/ses_test/message/msg_one'): (200, {'info': {'id': 'msg_one'}, 'parts': []})})
        self.assertEqual(run(home, 'status', json.dumps({'id': 'msg_one'})), 'accepted')
        self.assertEqual(run(home, 'status', json.dumps({'id': 'msg_two'})), 'unknown')

    # session.ts:153 status map; session/status.ts:72 absent = idle. The
    # payload is ingress::state's {idle, queued}.
    def test_state_is_the_session_own_status_and_absent_is_idle(self):
        home, server = self.serve({('GET', '/session/status' + QUERY): (200, {'ses_test': {'type': 'retry', 'attempt': 1, 'message': 'm', 'next': 2}})})
        self.assertEqual(run(home, 'state'), 'state\t{"idle":false,"queued":[]}')
        server.routes[('GET', '/session/status' + QUERY)] = (200, {'ses_other': {'type': 'busy'}})
        self.assertEqual(run(home, 'state'), 'state\t{"idle":true,"queued":[]}')
        server.routes[('GET', '/session/status' + QUERY)] = (200, {'ses_test': {'type': 'paused'}})
        self.assertEqual(run(home, 'state'), 'local')

    def test_a_session_this_server_does_not_hold_is_gone(self):
        home, _ = self.serve({SESSION: (404, {'name': 'NotFoundError'})})
        self.assertEqual(run(home, 'state'), 'gone')


class Children(Daemon):
    # session.ts:176 children -> [Session.Info].
    def test_a_list_is_only_this_sessions_children_and_a_cut_one_says_so(self):
        kid = {'id': 'ses_kid', 'parentID': 'ses_test', 'title': 'Explore', 'directory': DIRECTORY}
        home, server = self.serve({('GET', '/session/ses_test/children'): (200, [kid])})
        routes = server.routes
        self.assertEqual(run(home, 'children'), 'children\t[{"id":"ses_kid","title":"Explore"}]')
        for bad in ({'id': 'ses_stray', 'parentID': 'ses_other'}, {'id': 7, 'parentID': 'ses_test'}, kid):
            routes[('GET', '/session/ses_test/children')] = (200, [kid, bad])
            self.assertEqual(run(home, 'children'), 'local', bad)
        routes[('GET', '/session/ses_test/children')] = (200, [dict(kid, id='ses_k%02d' % n) for n in range(51)])
        word, listed = run(home, 'children').split('\t')
        self.assertEqual((word, len(json.loads(listed))), ('children_more', 50))


class Discovery(unittest.TestCase):
    def test_no_registration_is_no_server_and_two_are_refused(self):
        with tempfile.TemporaryDirectory(prefix='herdline-kilo-') as home:
            self.assertEqual(run(home, 'state'), 'no_server')
            for root in ('.local/state/kilo', '.local/share/kilo/state'):
                register(Path(home) / root / 'daemon.json', {'pid': os.getpid(), 'url': 'http://127.0.0.1:9', 'username': 'kilo', 'password': 'x'})
            self.assertEqual(run(home, 'state'), 'discovery')


if __name__ == '__main__':
    unittest.main()
