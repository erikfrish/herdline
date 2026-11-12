"""Resolve only the current pane's session within known process-configured roots."""
import glob
import json
import os
import re
import subprocess
import sys

herdr, session, pane, wanted, home = sys.argv[1:]

def report(*args):
    result = subprocess.run([herdr, '--session', session, *args], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=20)
    if result.returncode or len(result.stdout) > 65536:
        raise ValueError('report')
    return json.loads(result.stdout)['result']

def resolve():
    info = report('pane', 'get', pane)['pane']
    ref = info['agent_session']
    agent, kind, value = ref['agent'], ref['kind'], ref['value']
    if value != wanted:
        raise ValueError('changed')
    processes = report('pane', 'process-info', '--pane', pane).get('process_info', {}).get('foreground_processes', [])
    pid = next((process.get('pid') for process in processes if process.get('name') == agent), None)
    env = {}
    argv = []
    if isinstance(pid, int) and pid > 0:
        try:
            for field in open('/proc/%d/environ' % pid, 'rb').read(262144).split(b'\0'):
                key, separator, data = field.partition(b'=')
                if separator:
                    env[key.decode(errors='replace')] = data.decode(errors='replace')
            argv = [part.decode(errors='replace') for part in open('/proc/%d/cmdline' % pid, 'rb').read(65536).split(b'\0') if part]
        except OSError:
            # Default roots require no environment guess. Configured roots do.
            env = {}
            argv = []
    if env.get('HOME') and os.path.realpath(env['HOME']) != home:
        raise ValueError('home')
    def absolute(path):
        if path == '~': path = home
        elif path.startswith('~/'): path = os.path.join(home, path[2:])
        if not os.path.isabs(path):
            raise ValueError('relative')
        canonical = os.path.realpath(path)
        if canonical == home or os.path.commonpath([home, canonical]) != home:
            raise ValueError('outside home')
        return canonical
    roots = []
    def add(path):
        canonical = absolute(path)
        if canonical not in roots:
            roots.append(canonical)
    if agent in ('omp', 'pi') and kind == 'path':
        default = os.path.join(home, '.omp' if agent == 'omp' else '.pi', 'agent')
        add(os.path.join(default, 'sessions'))
        override = env.get('PI_CODING_AGENT_DIR')
        if agent == 'omp':
            config = env.get('PI_CONFIG_DIR') or '.omp'
            base = absolute(os.path.join(home, config.lstrip('/')))
            profile = env.get('OMP_PROFILE') if 'OMP_PROFILE' in env else env.get('PI_PROFILE')
            for index, arg in enumerate(argv):
                if arg == '--': break
                if arg == '--profile' and index + 1 < len(argv): profile = argv[index + 1]
                elif arg.startswith('--profile='): profile = arg[len('--profile='):]
            def normalize_profile(name):
                name = (name or '').strip()
                if name == 'default': return ''
                if name and (not re.fullmatch(r'[a-z0-9][a-z0-9._-]{0,63}', name) or name.endswith('.') or re.match(r'^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\..*)?$', name, re.I)):
                    raise ValueError('profile')
                return name
            profile = normalize_profile(profile)
            if not profile and override:
                try:
                    inherited = normalize_profile(env.get('PI_PROFILE'))
                except ValueError:
                    inherited = ''
                derived = os.path.join(base, 'profiles', inherited, 'agent') if inherited else None
                if override == derived:
                    override = None
            config_root = os.path.join(base, 'profiles', profile) if profile else base
            add(os.path.join(config_root, 'agent', 'sessions'))
            xdg = env.get('XDG_DATA_HOME')
            if xdg and (profile or not override):
                app = os.path.join(absolute(xdg), 'omp')
                xdg_root = os.path.join(app, 'profiles', profile) if profile else app
                if os.path.isdir(xdg_root):
                    add(os.path.join(xdg_root, 'sessions'))
            if not profile and override:
                add(os.path.join(absolute(override), 'sessions'))
        elif override:
            add(os.path.join(absolute(override), 'sessions'))
        candidates = [value]
    elif agent == 'claude' and kind == 'id':
        add(os.path.join(absolute(env.get('CLAUDE_CONFIG_DIR') or os.path.join(home, '.claude')), 'projects'))
        candidates = glob.glob(os.path.join(roots[0], '*', glob.escape(value) + '.jsonl'))
    elif agent == 'codex' and kind == 'id':
        add(os.path.join(absolute(env.get('CODEX_HOME') or os.path.join(home, '.codex')), 'sessions'))
        candidates = glob.glob(os.path.join(roots[0], '**', 'rollout-*-' + glob.escape(value) + '.jsonl'), recursive=True)
    else:
        raise ValueError('agent')
    valid = []
    for candidate in candidates:
        canonical = absolute(candidate)
        if not any(os.path.commonpath([root, canonical]) == root for root in roots):
            raise ValueError('unconfined')
        if os.path.isfile(canonical) and canonical.endswith('.jsonl'):
            valid.append(canonical)
    if len(valid) != 1:
        raise ValueError('ambiguous or missing')
    return valid[0]

try:
    print(resolve())
except Exception:
    sys.exit(3)
