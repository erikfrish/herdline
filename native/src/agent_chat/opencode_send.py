"""One prompt to a discovered, already-running local OpenCode service."""
import base64
import glob
import json
import os
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request

pid, session, body = int(sys.argv[1]), sys.argv[2], sys.argv[3].encode()

def main():
    env = {}
    for field in open('/proc/%d/environ' % pid, 'rb').read(262144).split(b'\0'):
        key, sep, value = field.partition(b'=')
        if sep:
            env[key.decode(errors='replace')] = value.decode(errors='replace')
    home = os.path.realpath(os.environ['HOME'])
    if os.path.realpath(env.get('HOME') or home) != home:
        return 'local'
    root = os.path.realpath(os.path.join(env.get('XDG_STATE_HOME') or os.path.join(home, '.local', 'state'), 'opencode'))
    if os.path.commonpath([home, root]) != home:
        return 'local'
    files = glob.glob(os.path.join(root, 'service*.json'))
    if len(files) != 1:
        return 'local'
    file = files[0]
    info_stat = os.lstat(file)
    if not stat.S_ISREG(info_stat.st_mode) or info_stat.st_uid != os.getuid() or stat.S_IMODE(info_stat.st_mode) != 0o600 or info_stat.st_size > 65536:
        return 'local'
    registration = json.load(open(file))
    url = registration['url']
    address = urllib.parse.urlsplit(url)
    if address.scheme != 'http' or address.hostname not in ('127.0.0.1', 'localhost', '::1') or address.username or address.password:
        return 'local'
    server_pid = registration.get('pid')
    if not isinstance(server_pid, int) or server_pid <= 0:
        return 'local'
    os.kill(server_pid, 0)
    headers = {'Content-Type': 'application/json'}
    if registration.get('password'):
        headers['Authorization'] = 'Basic ' + base64.b64encode(('opencode:' + registration['password']).encode()).decode()
    path = url.rstrip('/') + '/api/session/' + urllib.parse.quote(session, safe='')
    try:
        request = urllib.request.Request(path, headers=headers)
        with urllib.request.urlopen(request, timeout=10) as response:
            raw = response.read(65537)
        if len(raw) > 65536 or json.loads(raw)['data']['id'] != session:
            return 'local'
    except urllib.error.HTTPError as error:
        return 'gone' if error.code == 404 else 'auth' if error.code == 401 else 'local'
    except Exception:
        return 'local'
    # From this boundary on, unreadable transport/error responses may hide
    # an admitted input. Never downgrade them to a safe-to-retry refusal.
    try:
        request = urllib.request.Request(path + '/prompt', data=body, headers=headers, method='POST')
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read(65537)
        result = json.loads(raw)['data']
        requested = json.loads(body)
        if len(raw) <= 65536 and result.get('sessionID') == session and result.get('id') == requested['id']:
            return 'queued'
        return 'doubt'
    except Exception:
        return 'doubt'

try:
    print(main())
except Exception:
    print('local')
