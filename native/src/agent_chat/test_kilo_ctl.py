"""Consumer transport proof: kilo_ctl.py against an isolated HTTP server
shaped as Kilo v7.8.27's v1 routes (see kilo_ctl.py for each file:line)."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from test_opencode_ctl import Server, register

SCRIPT = Path(__file__).with_name('kilo_ctl.py')
DIRECTORY = '/srv/project'
QUERY = '?directory=%2Fsrv%2Fproject'
SESSION = ('GET', '/session/ses_test')


def run(home, op, payload='{}'):
    # No XDG_STATE_HOME: discovery uses the defaults under HOME only.
    env = {'HOME': home, 'PATH': '/usr/bin:/bin'}
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
    def test_an_idle_session_takes_text_and_host_files_as_parts(self):
        home, server = self.serve({
            ('GET', '/session/status' + QUERY): (200, {'ses_other': {'type': 'busy'}}),
            ('POST', '/session/ses_test/prompt_async'): (204, None),
        })
        sent = {'id': 'msg_one', 'text': 'look', 'files': [{'path': '/tmp/up/a b.png', 'mime': 'image/png', 'filename': 'a b.png'}]}
        self.assertEqual(run(home, 'send', json.dumps(sent)), 'queued')
        self.assertEqual(self.posts(server), [('POST', '/session/ses_test/prompt_async', {'messageID': 'msg_one', 'parts': [
            {'type': 'text', 'text': 'look'},
            {'type': 'file', 'mime': 'image/png', 'url': 'file:///tmp/up/a%20b.png', 'filename': 'a b.png'},
        ]})])
        self.assertEqual(server.seen[-1][3], 'Basic a2lsbzp0ZXN0LW9ubHk=')

    # schema/src/session-status-event.ts:11-47: busy, retry, offline,
    # scheduled are all at work or waiting; only idle starts a turn.
    def test_a_session_not_idle_is_never_sent_a_prompt(self):
        for kind in ('busy', 'retry', 'offline', 'scheduled'):
            with self.subTest(kind):
                home, server = self.serve({('GET', '/session/status' + QUERY): (200, {'ses_test': {'type': kind}})})
                self.assertEqual(run(home, 'send', json.dumps({'id': 'msg_one', 'text': 'x'})), 'busy')
                self.assertEqual(self.posts(server), [])

    def test_a_relative_file_an_unshaped_id_or_nothing_to_send_asks_nothing(self):
        home, server = self.serve({('GET', '/session/status' + QUERY): (200, {})})
        for sent in ({'id': 'msg_one', 'files': [{'path': 'a.png', 'mime': 'image/png'}]},
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


ASKED = {'id': 'que_one', 'sessionID': 'ses_test', 'questions': [
    {'question': 'Which?', 'header': 'Pick', 'options': [{'label': 'A', 'description': 'a'}, {'label': 'B', 'description': 'b'}], 'custom': False},
    {'question': 'Which ones?', 'header': 'Many', 'options': [{'label': 'X', 'description': 'x'}, {'label': 'Y', 'description': 'y'}], 'multiple': True},
]}


class Questions(Daemon):
    # question.ts:22 list, :32 reply; schema/src/v1/question.ts:51-73.
    def routes(self):
        return {
            ('GET', '/question' + QUERY): (200, [ASKED, dict(ASKED, id='que_other', sessionID='ses_other')]),
            ('POST', '/question/que_one/reply' + QUERY): (200, True),
        }

    def test_only_this_session_questions_are_listed(self):
        home, _ = self.serve(self.routes())
        word, _, payload = run(home, 'questions').partition('\t')
        self.assertEqual(word, 'questions')
        found = json.loads(payload)
        self.assertEqual([entry['id'] for entry in found], ['que_one'])
        self.assertEqual([(q['multiple'], q['custom']) for q in found[0]['questions']], [(False, False), (True, True)])

    def test_an_answer_per_question_is_replied_in_order(self):
        home, server = self.serve(self.routes())
        answers = [['A'], ['X', 'typed']]
        self.assertEqual(run(home, 'answer', json.dumps({'id': 'que_one', 'answers': answers})), 'answered')
        self.assertEqual(self.posts(server), [('POST', '/question/que_one/reply' + QUERY, {'answers': answers})])

    def test_an_answer_the_question_cannot_take_is_never_sent(self):
        home, server = self.serve(self.routes())
        for answers in ([['A']], [['A', 'B'], ['X']], [['typed'], ['X']], [['A'], []]):
            with self.subTest(answers):
                self.assertEqual(run(home, 'answer', json.dumps({'id': 'que_one', 'answers': answers})), 'invalid')
        self.assertEqual(run(home, 'answer', json.dumps({'id': 'que_other', 'answers': [['A'], ['X']]})), 'not_open')
        self.assertEqual(self.posts(server), [])

    # question handler: Question.NotFoundError -> 404, nothing changed.
    def test_a_question_answered_meanwhile_is_not_open(self):
        routes = self.routes()
        routes[('POST', '/question/que_one/reply' + QUERY)] = (404, {'name': 'QuestionNotFoundError'})
        home, _ = self.serve(routes)
        self.assertEqual(run(home, 'answer', json.dumps({'id': 'que_one', 'answers': [['A'], ['X']]})), 'not_open')


class Children(Daemon):
    # session.ts:176 children -> [Session.Info].
    def test_only_sessions_naming_this_one_as_parent_are_children(self):
        home, _ = self.serve({('GET', '/session/ses_test/children'): (200, [
            {'id': 'ses_kid', 'parentID': 'ses_test', 'title': 'Explore', 'directory': DIRECTORY},
            {'id': 'ses_stray', 'parentID': 'ses_other', 'title': 'Other'},
        ])})
        self.assertEqual(run(home, 'children'), 'children\t[{"id":"ses_kid","title":"Explore"}]')


class Discovery(unittest.TestCase):
    def test_no_registration_is_no_server_and_two_are_refused(self):
        with tempfile.TemporaryDirectory(prefix='herdline-kilo-') as home:
            self.assertEqual(run(home, 'state'), 'no_server')
            for root in ('.local/state/kilo', '.local/share/kilo/state'):
                register(Path(home) / root / 'daemon.json', {'pid': os.getpid(), 'url': 'http://127.0.0.1:9', 'username': 'kilo', 'password': 'x'})
            self.assertEqual(run(home, 'state'), 'discovery')


if __name__ == '__main__':
    unittest.main()
