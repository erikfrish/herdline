// Herdline's Gemini CLI session hook (gemini-cli v0.68.0, 5a5c7180), for
// SessionStart and SessionEnd in a QA sandbox's settings.json only:
//
//   {"hooks": {
//     "SessionStart": [{"hooks": [{"type": "command", "name": "herdline-session",
//                                  "command": "node <dir>/hook.js", "timeout": 5000}]}],
//     "SessionEnd":   [{"hooks": [{"type": "command", "name": "herdline-session",
//                                  "command": "node <dir>/hook.js", "timeout": 5000}]}]}}
//
// The hook runs with Gemini's environment (hookRunner.ts:348-356), so the
// pane, pid and nonce come only from the owned launcher, never from the
// input; transcript_path is never read. Nothing is reported unless the pane's
// private sidecar (sidecar.js) still holds this launcher's pid and nonce (a
// hook of an older run in the pane reports nothing), and unless Herdr shows
// this launcher's pid in that pane now (after a pane renumbering the
// launch-time pane id may name another pane: nothing is reported there).
//
// SessionStart first publishes the session uuid in this launch's private
// session file, then reports it with that pid and nonce as the pane's
// hl_gemini_id / hl_gemini_pid / hl_gemini_gen tokens (Herdr keys allow only
// letters, digits, `_` and `-`, api_helpers.rs:244-275). The reader requires
// the tokens' uuid to equal the published one, so a SessionStart Herdr did
// not take leaves the pane refused, never on the session before it.
// SessionEnd clears the tokens only while they are still exactly this
// session, pid and nonce, so a late SessionEnd of a session `/clear` or
// `/new` replaced never clears the new one; then it retracts the published
// session if it is still this one.
//
// Each report is `--agent gemini`, so Herdr drops it once it has seen this
// Gemini exit (terminal/metadata.rs:79-94), and its seq is the event's own
// timestamp, set when Gemini builds the event (createBaseInput, bundle
// chunk-2OHBIZ5H.js:372733-372742), not the hook's run time,
// with a SessionStart ordered after a SessionEnd of the same millisecond:
// Herdr takes only a seq above the last (metadata_tokens.rs:17-23). Herdr
// also drops, silently, a SessionStart that comes before it has detected
// this Gemini after an earlier one exited, so a SessionStart is retried until
// the tokens show it, for at most RETRY_MS.
//
// Anything missing or malformed reports nothing. stdout is only the final
// `{}` (docs/hooks/reference.md:16-17).
import { spawnSync } from 'node:child_process';
import { PANE, publish, read, retract } from './sidecar.js';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const PID = /^[1-9][0-9]{0,9}$/;
const GEN = /^[0-9a-f]{32}$/;
const MAX_INPUT = 1 << 20;
const RETRY_MS = 3000;
const PAUSE_MS = 200;

function herdr(env, args) {
  return spawnSync('herdr', args, { env, stdio: ['ignore', 'pipe', 'ignore'], timeout: 2000, maxBuffer: 1 << 20 });
}

/// The pane's tokens now, or null.
function tokens(env, pane) {
  const result = herdr(env, ['pane', 'get', pane]);
  if (result.status !== 0) return null;
  try {
    const value = JSON.parse(result.stdout)?.result?.pane?.tokens;
    return value && typeof value === 'object' ? value : {};
  } catch {
    return null;
  }
}

/// Whether Herdr shows `pid` among the pane's foreground processes.
function here(env, pane, pid) {
  const result = herdr(env, ['pane', 'process-info', '--pane', pane]);
  if (result.status !== 0) return false;
  try {
    const found = JSON.parse(result.stdout)?.result?.process_info?.foreground_processes;
    return Array.isArray(found) && found.some((entry) => entry?.pid === Number(pid));
  } catch {
    return false;
  }
}

function main(input) {
  const pane = process.env.GEMINI_CLI_HERDLINE_PANE;
  const pid = process.env.GEMINI_CLI_HERDLINE_PID;
  const gen = process.env.GEMINI_CLI_HERDLINE_GEN;
  if (!PANE.test(pane ?? '') || !PID.test(pid ?? '') || !GEN.test(gen ?? '')) return;
  let event;
  try {
    event = JSON.parse(input);
  } catch {
    return;
  }
  if (!event || typeof event !== 'object' || typeof event.session_id !== 'string' || !UUID.test(event.session_id)) {
    return;
  }
  const at = typeof event.timestamp === 'string' ? Date.parse(event.timestamp) : NaN;
  if (!Number.isSafeInteger(at) || at <= 0) return;
  let own;
  try {
    own = read(pane);
  } catch {
    return;
  }
  if (!own || own.pid !== Number(pid) || own.nonce !== gen) return;

  const env = { ...process.env };
  if (process.env.GEMINI_CLI_HERDLINE_SOCKET) env.HERDR_SOCKET_PATH = process.env.GEMINI_CLI_HERDLINE_SOCKET;
  const start = event.hook_event_name === 'SessionStart';
  if (!start && event.hook_event_name !== 'SessionEnd') return;
  if (!here(env, pane, pid)) return;
  const seq = String(at * 2 + (start ? 1 : 0));
  const args = ['pane', 'report-metadata', pane, '--agent', 'gemini', '--source', 'user:gemini-session', '--seq', seq];
  const mine = (now) =>
    now?.hl_gemini_id === event.session_id && now.hl_gemini_pid === pid && now.hl_gemini_gen === gen;
  if (start) {
    try {
      publish(pane, gen, event.session_id);
    } catch {
      return;
    }
    args.push(
      '--token', `hl_gemini_id=${event.session_id}`,
      '--token', `hl_gemini_pid=${pid}`,
      '--token', `hl_gemini_gen=${gen}`,
    );
    const until = Date.now() + RETRY_MS;
    for (;;) {
      herdr(env, args);
      if (mine(tokens(env, pane)) || Date.now() >= until) return;
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, PAUSE_MS);
    }
  }
  if (!mine(tokens(env, pane))) return;
  args.push('--clear-token', 'hl_gemini_id', '--clear-token', 'hl_gemini_pid', '--clear-token', 'hl_gemini_gen');
  herdr(env, args);
  try {
    retract(pane, gen, event.session_id);
  } catch {
    // Unreadable: the reader refuses it.
  }
}

let input = '';
process.stdin.setEncoding('utf8');
for await (const chunk of process.stdin) {
  input += chunk;
  if (input.length > MAX_INPUT) {
    input = '';
    break;
  }
}
try {
  main(input);
} finally {
  process.stdout.write('{}');
}
