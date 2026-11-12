"""Resolve only the current pane's session within known process-configured
roots; with --blob-store, the blob store of an OMP process instead."""
import glob
import json
import os
import re
import subprocess
import sys

class OutsideHome(ValueError):
    """A root or file the agent uses lies outside this user's home."""

# Only the variables a root comes from are decoded: the rest of environ
# (tokens, keys) never sits in this script's memory as text.
WANTED = (b'HOME', b'OMP_PROFILE', b'PI_PROFILE', b'PI_CONFIG_DIR', b'PI_CODING_AGENT_DIR', b'XDG_DATA_HOME', b'CLAUDE_CONFIG_DIR', b'CODEX_HOME')

# omp 18.18.8 (oh-my-pi 6e48274) reads --profile before anything else, in
# extractProfileFlags (coding-agent/src/cli/profile-bootstrap.ts:77-229), so
# a string flag's value is never a --profile and a subcommand's own flags are
# its own (args.ts:347-352 is only the fallback). Its tables, copied:
# STRING_SETTERS (cli/flag-tables.ts:133-301), OPTIONAL_FLAGS (:303-308),
# EXTENSION_SHADOWABLE_STRING_FLAGS (:324), VALUELESS_FLAGS (:352-380), and
# the command names and aliases (cli-commands.ts:27-326, LAUNCH_FLAG_COMMANDS
# :330).
STRING_FLAGS = {'--cwd', '--config', '--add-dir', '--mode', '--fork', '--provider', '--model', '--smol', '--slow', '--goal', '--plan', '--prewalk-into', '--plan-yolo-into', '--max-time', '--warn-cost', '--max-cost', '--service-tier', '--spinner-interval', '--max-fps', '--motion', '--api-key', '--system-prompt', '--system-prompt-template', '--append-system-prompt', '--provider-session-id', '--prompt-cache-key', '--session-dir', '--models', '--tools', '--thinking', '--export', '--hook', '--extension', '-e', '--trusted-extension', '--plugin-dir', '--skills', '--approval-mode'}
OPTIONAL_FLAGS = {'--resume', '-r', '--restore', '--session'}
SHADOWABLE_FLAGS = {'--plan'}
VALUELESS_FLAGS = {'--help', '--version', '--allow-home', '--continue', '--from-claude', '--from-codex', '--no-session', '--no-tools', '--no-lsp', '--no-pty', '--hide-thinking', '--advisor', '--wait-async', '--external-thinking', '--prewalk', '--no-prewalk', '--plan-yolo', '--print', '--print-thoughts', '--no-extensions', '--no-skills', '--no-rules', '--no-context-files', '--no-title', '--no-ui', '--auto-approve', '--yolo'}
COMMANDS = {'launch', 'acp', 'auth', 'auth-broker', 'auth-gateway', 'agents', 'anonymize', 'bench', 'browser-relay', 'cleanse', 'collab', 'commit', 'completions', '__complete', 'compress', 'config', 'dry-balance', 'find', 'gc', 'grep', 'gallery', 'git', 'grievances', 'images', 'img', 'if-bench', 'install', 'join', 'login', 'models', 'plugin', 'plugins', 'predict', 'ps', 'say', 'clip', 'play', 'share', 'session', 'setup', 'shell', 'zsh', 'bash', 'sh', 'read', 'render', 'skill', 'skills', 'ssh', 'stats', 'stream', 'update', 'usage', 'tiny-models', 'token', 'toks', 'ttsr', 'worktree', 'wt', 'search', 'q', 'web-search'}
LAUNCH_COMMANDS = {'launch', 'acp'}

def profile_flag(argv):
    """The last --profile omp itself takes from `argv` (its arguments, no
    program name), or None. A --profile or --alias with no value is an error
    omp exits on."""
    profile = None
    dispatch = True
    index = 0
    while index < len(argv):
        arg = argv[index]
        after = argv[index + 1] if index + 1 < len(argv) else None
        valued = after is not None and not after.startswith('-')
        if arg == '--':
            break
        if arg in ('--profile', '--alias'):
            if not after or after.startswith('-'):
                raise ValueError('flag')
            if arg == '--profile': profile = after
            index += 2
            continue
        if arg.startswith(('--profile=', '--alias=')):
            value = arg.partition('=')[2]
            if not value:
                raise ValueError('flag')
            if arg.startswith('--profile='): profile = value
            index += 1
            continue
        if arg in SHADOWABLE_FLAGS:
            dispatch = False
            index += 2 if valued else 1
        elif arg in STRING_FLAGS:
            dispatch = False
            index += 2
        elif arg in OPTIONAL_FLAGS:
            dispatch = False
            index += 2 if valued and after != '' else 1
        elif arg.startswith('--') and '=' not in arg and arg not in VALUELESS_FLAGS:
            # Possibly an extension's string flag: it takes a value-like next token.
            dispatch = False
            index += 2 if valued else 1
        else:
            # Only the first token can name a command; from it on, nothing
            # is a global flag (omp grep --profile x greps for --profile).
            if dispatch and arg in COMMANDS and arg not in LAUNCH_COMMANDS:
                break
            dispatch = False
            index += 1
    return profile

def process(pid):
    """The root variables of `pid`'s environment and its arguments."""
    env = {}
    for field in open('/proc/%d/environ' % pid, 'rb').read(262144).split(b'\0'):
        key, separator, data = field.partition(b'=')
        if separator and key in WANTED:
            env[key.decode()] = data.decode(errors='replace')
    argv = [part.decode(errors='replace') for part in open('/proc/%d/cmdline' % pid, 'rb').read(65536).split(b'\0')]
    if argv and argv[-1] == '':
        argv.pop()
    return env, argv

def normalize_profile(name):
    name = (name or '').strip()
    if name == 'default': return ''
    if name and (not re.fullmatch(r'[a-z0-9][a-z0-9._-]{0,63}', name) or name.endswith('.') or re.match(r'^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\..*)?$', name, re.I)):
        raise ValueError('profile')
    return name

def omp_agent(env, argv, config):
    """The profile an OMP process with `env` and `argv` runs, and its
    PI_CODING_AGENT_DIR as OMP keeps it. The session roots and the blob
    store both come from here, so they never disagree."""
    from_env = env.get('OMP_PROFILE') if 'OMP_PROFILE' in env else env.get('PI_PROFILE')
    # The program, and for a bun or node launch its script, are not
    # omp's arguments (process.argv.slice(2)).
    start = 0 if not argv else 2 if os.path.basename(argv[0]) in ('bun', 'node') else 1
    flag = profile_flag(argv[start:])
    profile = normalize_profile(from_env if flag is None else flag)
    override = env.get('PI_CODING_AGENT_DIR')
    if not profile and override:
        def safe(name):
            try:
                return normalize_profile(name)
            except ValueError:
                return ''
        # dirs.ts:518-522: OMP drops a PI_CODING_AGENT_DIR derived from the
        # environment's profile, else PI_PROFILE's, even when a flag (as
        # --profile default) then runs without one. dirs.ts:133-139 compares
        # the raw value to path.join(os.homedir(), config, 'profiles', p,
        # 'agent'): no symlink is resolved.
        inherited = safe(from_env) or safe(env.get('PI_PROFILE'))
        derived = os.path.normpath(os.path.join(env.get('HOME') or home, config.lstrip('/'), 'profiles', inherited, 'agent')) if inherited else None
        if override == derived:
            override = None
    return profile, override

def blob_store(pid):
    """pi-utils getBlobsDir(), agentSubdir(undefined, 'blobs', 'data')
    (18.18.8): the profile's agent dir, or without one PI_CODING_AGENT_DIR,
    else ~/.omp/agent; an existing $XDG_DATA_HOME/omp (with a profile,
    .../profiles/<name>) takes its place. What it cannot pin down exactly
    (no /proc, another HOME, PI_CONFIG_DIR, a relative value) raises
    ValueError; a store outside HOME, OutsideHome."""
    env, argv = process(pid)
    if not env.get('HOME') or os.path.realpath(env['HOME']) != home:
        raise ValueError('home')
    if env.get('PI_CONFIG_DIR'):
        raise ValueError('config')
    profile, override = omp_agent(env, argv, '.omp')
    xdg = env.get('XDG_DATA_HOME') or ''
    if xdg and not os.path.isabs(xdg):
        raise ValueError('relative')
    if profile:
        root = os.path.join(home, '.omp', 'profiles', profile, 'agent')
        if xdg and os.path.exists(os.path.join(xdg, 'omp', 'profiles', profile)):
            root = os.path.join(xdg, 'omp', 'profiles', profile)
    elif not override or override == os.path.join(home, '.omp', 'agent'):
        root = os.path.join(home, '.omp', 'agent')
        if xdg and os.path.exists(os.path.join(xdg, 'omp')):
            root = os.path.join(xdg, 'omp')
    elif os.path.isabs(override):
        root = override
    else:
        raise ValueError('relative')
    store = os.path.realpath(os.path.join(root, 'blobs'))
    if not store.startswith(home + '/'):
        raise OutsideHome()
    return store

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
            env, argv = process(pid)
        except OSError:
            # Default roots require no environment guess. Configured roots do.
            pass
    if env.get('HOME') and os.path.realpath(env['HOME']) != home:
        raise OutsideHome()
    def absolute(path):
        # OMP, Claude and Codex never expand `~`: Node's path.resolve reads
        # `~/x` against the agent's cwd, so it is relative here too.
        if not os.path.isabs(path):
            raise ValueError('relative')
        canonical = os.path.realpath(path)
        if canonical == home:
            raise ValueError('home')
        if os.path.commonpath([home, canonical]) != home:
            raise OutsideHome()
        return canonical
    roots = []
    def add(path):
        canonical = absolute(path)
        if canonical not in roots:
            roots.append(canonical)
    def configured(path):
        # An override that is relative, or lands outside HOME, refuses only
        # itself: the default root and the other overrides still hold.
        try:
            return absolute(path)
        except ValueError:
            return None
    if agent in ('omp', 'pi') and kind == 'path':
        default = os.path.join(home, '.omp' if agent == 'omp' else '.pi', 'agent')
        add(os.path.join(default, 'sessions'))
        if agent == 'omp':
            config = env.get('PI_CONFIG_DIR') or '.omp'
            base = configured(os.path.join(home, config.lstrip('/')))
            profile, override = omp_agent(env, argv, config)
            if base:
                config_root = os.path.join(base, 'profiles', profile) if profile else base
                sessions = configured(os.path.join(config_root, 'agent', 'sessions'))
                if sessions: add(sessions)
            xdg = env.get('XDG_DATA_HOME')
            data = configured(xdg) if xdg and (profile or not override) else None
            if data:
                app = os.path.join(data, 'omp')
                xdg_root = os.path.join(app, 'profiles', profile) if profile else app
                sessions = configured(os.path.join(xdg_root, 'sessions')) if os.path.isdir(xdg_root) else None
                if sessions: add(sessions)
            if not profile and override:
                sessions = configured(os.path.join(override, 'sessions'))
                if sessions: add(sessions)
        elif env.get('PI_CODING_AGENT_DIR'):
            sessions = configured(os.path.join(env['PI_CODING_AGENT_DIR'], 'sessions'))
            if sessions: add(sessions)
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

if sys.argv[1] == '--blob-store':
    # Run by the media read's shell: 3 is unconfined, any other failure an
    # unknown store, never a guessed one.
    home = sys.argv[3]
    try:
        print(blob_store(int(sys.argv[2])))
    except OutsideHome:
        sys.exit(3)
    except Exception:
        sys.exit(9)
else:
    herdr, session, pane, wanted, home = sys.argv[1:]
    try:
        print(resolve())
    except OutsideHome:
        sys.exit(11)
    except Exception:
        sys.exit(3)
